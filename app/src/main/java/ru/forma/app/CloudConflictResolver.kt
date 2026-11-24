package ru.forma.app

import androidx.room.withTransaction
import kotlinx.serialization.encodeToString
import ru.forma.domain.Backup
import ru.forma.domain.Profile
import ru.forma.domain.validateBackup
import java.util.UUID

internal enum class CloudConflictChoice { DEVICE, CLOUD }

internal data class CloudConflictPreview(
    val lease: VerifiedSyncLease,
    val key: SyncReviewRow,
    val profileName: String,
    val localSnapshot: String?,
    val cloud: SyncRemoteEcho?,
    val parent: SyncRemoteEcho?,
    val receipt: SyncOutboxRow?,
    val queued: List<SyncOutboxRow>,
    val blockedReason: String?,
) {
    val canUseCloud: Boolean get() = cloud != null && blockedReason == null
    val canKeepDevice: Boolean get() = canUseCloud && cloud?.let {
        !it.tombstone && (it.revision < MAX_CLOUD_REVISION || it.snapshot == localSnapshot)
    } == true
}

internal class CloudConflictResolver(
    private val database: FormaDatabase,
    private val identity: VerifiedSyncIdentity,
    private val remote: AuthenticatedProfileChanges,
) {
    suspend fun reviews(lease: VerifiedSyncLease): List<SyncReviewRow> {
        requireLease(lease)
        return database.syncReview().reviews(lease.uid, 50).also { requireLease(lease) }
    }

    suspend fun archives(lease: VerifiedSyncLease): List<SyncResolutionArchiveRow> {
        requireLease(lease)
        return database.syncReview().archiveForAccount(lease.uid, 30).also { requireLease(lease) }
    }

    suspend fun preview(lease: VerifiedSyncLease, key: SyncReviewRow): CloudConflictPreview {
        requireLease(lease)
        require(key.accountUid == lease.uid && key.kind in setOf("profile", "measurement", "workout"))
        database.withTransaction { loadProfile(lease, key) }
        val cloud = remote.readForReview(lease, key)
        require(cloud == null || (cloud.validFor(lease, key.profileId, key.kind) && cloud.reviewKey() == key))
        val parent = if (key.kind == "profile") null else remote.readForReview(
            lease, SyncReviewRow(lease.uid, key.profileId, "profile", key.profileId),
        )
        return database.withTransaction {
            requireLease(lease)
            val profile = loadProfile(lease, key)
            describe(lease, key, profile, cloud, parent).also { requireLease(lease) }
        }
    }

    suspend fun resolve(
        lease: VerifiedSyncLease, preview: CloudConflictPreview, choice: CloudConflictChoice,
    ) {
        if (preview.lease != lease) throw LocalWorkoutAccessRevoked()
        val fresh = preview(lease, preview.key)
        if (fresh != preview) throw CloudRemoteChanged()
        check(if (choice == CloudConflictChoice.DEVICE) fresh.canKeepDevice else fresh.canUseCloud)
        val cloud = checkNotNull(fresh.cloud)
        database.withTransaction {
            requireLease(lease)
            val profile = loadProfile(lease, fresh.key)
            if (describe(lease, fresh.key, profile, cloud, fresh.parent) != fresh) throw CloudRemoteChanged()
            val dao = database.sync()
            val review = database.syncReview()
            val time = System.currentTimeMillis()
            review.archive(SyncResolutionArchiveRow(
                UUID.randomUUID().toString(), lease.uid, cloud.profileId, cloud.kind, cloud.recordId,
                fresh.localSnapshot, cloud.snapshot, cloud.tombstone, cloud.revision, cloud.opId,
                restoreJson.encodeToString(fresh.queued), choice.name, time,
            ))
            check(dao.retireReviewedRecord(lease.uid, cloud.profileId, cloud.kind, cloud.recordId) == fresh.queued.size)
            recordReceipt(dao, cloud)
            when (choice) {
                CloudConflictChoice.CLOUD -> {
                    val next = applyCloud(profile, cloud)
                    if (next != profile) database.profiles().put(
                        ProfileRow(profile.id, next.name, localProfileJson.encodeToString(next)),
                    )
                }
                CloudConflictChoice.DEVICE -> if (fresh.localSnapshot != cloud.snapshot) {
                    dao.enqueue(SyncOutboxRow(
                        UUID.randomUUID().toString(), lease.uid, cloud.profileId, cloud.kind, cloud.recordId,
                        cloud.revision + 1, fresh.localSnapshot ?: cloud.snapshot, "PENDING",
                        fresh.localSnapshot == null, time,
                    ))
                }
            }
            review.clear(lease.uid, cloud.profileId, cloud.kind, cloud.recordId)
            requireLease(lease)
        }
    }

    private suspend fun loadProfile(lease: VerifiedSyncLease, key: SyncReviewRow): Profile {
        val binding = database.sync().binding(key.profileId)
        check(binding?.accountUid == lease.uid && binding.consentAtMs > 0)
        if (!database.syncReview().contains(lease.uid, key.profileId, key.kind, key.recordId)) {
            throw CloudRemoteChanged()
        }
        val row = checkNotNull(database.profiles().get(key.profileId))
        return localProfileJson.decodeFromString<Profile>(row.document).also { check(it.id == key.profileId) }
    }

    private suspend fun describe(
        lease: VerifiedSyncLease, key: SyncReviewRow, profile: Profile, cloud: SyncRemoteEcho?, parent: SyncRemoteEcho?,
    ): CloudConflictPreview {
        val dao = database.sync()
        val receipt = dao.latestReceipt(key.accountUid, key.profileId, key.kind, key.recordId)
        val parentReceipt = if (key.kind == "profile") null else
            dao.latestReceipt(key.accountUid, key.profileId, "profile", key.profileId)
        val reason = when {
            cloud == null -> "Облачная запись недоступна. Данные сохранены на устройстве."
            key.kind == "profile" && cloud.tombstone ->
                "Профиль удалён в облаке. Локальный профиль и его история сохранены."
            receipt != null && (receipt.revision > cloud.revision ||
                (receipt.revision == cloud.revision && !cloud.matches(receipt))) ->
                "Облачная версия не совпадает с ранее подтверждёнными данными."
            key.kind != "profile" && (parent == null || parent.tombstone ||
                parentReceipt == null || !parent.matches(parentReceipt)) ->
                "Сначала синхронизируйте сам профиль и разрешите его конфликт, если он есть."
            key.kind == "workout" && profile.active?.id == key.recordId ->
                "Завершите текущую тренировку перед выбором версии записи."
            else -> null
        }
        val snapshot = when (key.kind) {
            "profile" -> restoreJson.encodeToString(profile.cloudPayload())
            "measurement" -> profile.measurements.filter { it.id == key.recordId }.also { check(it.size <= 1) }.singleOrNull()
                ?.let { restoreJson.encodeToString(it.cloudPayload()) }
            else -> profile.history.filter { it.id == key.recordId }.also { check(it.size <= 1) }.singleOrNull()
                ?.let { restoreJson.encodeToString(it.cloudPayload()) }
        }
        return CloudConflictPreview(lease, key, profile.name, snapshot, cloud, parent, receipt,
            dao.unresolvedRecord(key.accountUid, key.profileId, key.kind, key.recordId), reason)
    }

    private fun applyCloud(profile: Profile, cloud: SyncRemoteEcho): Profile = when (cloud.kind) {
        "profile" -> cloud.toRestoredProfile().copy(
            measurements = profile.measurements, history = profile.history, active = profile.active,
        )
        "measurement" -> {
            val item = cloud.toMeasurement()
            validateBackup(Backup(profiles = listOf(profile.copy(measurements = listOf(item), history = emptyList(), active = null))))
            val items = profile.measurements.associateByTo(linkedMapOf()) { it.id }
            check(items.size == profile.measurements.size)
            if (cloud.tombstone) items.remove(item.id) else items[item.id] = item
            require(items.size <= 10_000)
            profile.copy(measurements = items.values.toList())
        }
        else -> {
            val item = cloud.toWorkout()
            validateBackup(Backup(profiles = listOf(profile.copy(history = listOf(item), measurements = emptyList(), active = null))))
            val items = profile.history.associateByTo(linkedMapOf()) { it.id }
            check(items.size == profile.history.size)
            if (cloud.tombstone) items.remove(item.id) else items[item.id] = item
            require(items.size <= 10_000)
            profile.copy(history = items.values.toList())
        }
    }

    private suspend fun requireLease(lease: VerifiedSyncLease) {
        if (identity.currentLease() != lease) throw LocalWorkoutAccessRevoked()
    }
}
