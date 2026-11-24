package ru.forma.app

import androidx.room.withTransaction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.forma.domain.Backup
import ru.forma.domain.Equipment
import ru.forma.domain.Experience
import ru.forma.domain.Goal
import ru.forma.domain.MAX_BACKUP_PROFILES
import ru.forma.domain.Measurement
import ru.forma.domain.Muscle
import ru.forma.domain.Phase
import ru.forma.domain.Profile
import ru.forma.domain.Workout
import ru.forma.domain.validateBackup

internal data class CloudProfileChoice(val id: String, val name: String)

internal data class RemoteProfileBundle(
    val parent: SyncRemoteEcho,
    val measurements: List<SyncRemoteEcho>,
    val workouts: List<SyncRemoteEcho>,
)

internal interface AuthenticatedProfileRestore {
    suspend fun listProfiles(lease: VerifiedSyncLease): List<CloudProfileChoice>
    suspend fun readProfile(lease: VerifiedSyncLease, id: String): RemoteProfileBundle?
}

internal data class CloudRestoreResult(
    val restored: Int = 0, val conflicts: Int = 0, val changesApplied: Boolean = false,
)

private enum class RestoreDecision { KEEP, ACCEPT, CONFLICT }

internal val restoreJson = Json { encodeDefaults = true }
internal val localProfileJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

internal fun SyncRemoteEcho.toRestoredProfile(): Profile {
    val record = restoreJson.decodeFromString<CloudProfilePayload>(snapshot)
    require(record.equipment.distinct().size == record.equipment.size &&
        record.priorities.distinct().size == record.priorities.size &&
        record.days.distinct().size == record.days.size)
    return Profile(
        id = profileId, name = record.name, age = record.age, heightCm = record.heightCm,
        weightKg = record.weightKg, experience = Experience.valueOf(record.experience),
        equipment = record.equipment.map(Equipment::valueOf).toSet(), limitations = record.limitations,
        primary = Goal.valueOf(record.primary), secondary = record.secondary?.let(Goal::valueOf),
        priorities = record.priorities.map(Muscle::valueOf).toSet(),
        days = record.days.toSet(), recovery = record.recovery,
    ).also { require(it.valid()) }
}

internal fun SyncRemoteEcho.toMeasurement(): Measurement {
    val record = restoreJson.decodeFromString<CloudMeasurementPayload>(snapshot)
    return Measurement(recordId, record.dateMs, record.values)
}

internal fun SyncRemoteEcho.toWorkout(): Workout {
    val record = restoreJson.decodeFromString<CloudWorkoutPayload>(snapshot)
    return Workout(recordId, record.day, record.startedMs, record.finishedMs, record.sets,
        Phase.valueOf(record.status), record.activeSeconds, record.painReported)
}

/** Stage fresh server data, then use Room's current record and revision before applying it. */
internal class CloudProfileRestorer(
    private val database: FormaDatabase,
    private val identity: VerifiedSyncIdentity,
    private val remote: AuthenticatedProfileRestore,
) {
    suspend fun list(lease: VerifiedSyncLease): List<CloudProfileChoice> {
        requireLease(lease)
        return remote.listProfiles(lease).also { requireLease(lease) }
    }

    suspend fun restore(lease: VerifiedSyncLease, id: String): CloudRestoreResult {
        requireLease(lease)
        require(cloudDocumentId.matches(id))
        val bundle = remote.readProfile(lease, id) ?: return CloudRestoreResult(conflicts = 1)
        return apply(lease, id, bundle)
    }

    suspend fun applyChanges(lease: VerifiedSyncLease, id: String, changes: BoundCloudChanges): CloudRestoreResult =
        apply(lease, id, changes.bundle, changes.cursors)

    private suspend fun apply(
        lease: VerifiedSyncLease,
        id: String,
        bundle: RemoteProfileBundle,
        cursors: List<SyncPullRow>? = null,
    ): CloudRestoreResult {
        require(bundle.parent.validFor(lease, id, "profile") && bundle.parent.recordId == id)
        require(bundle.measurements.all { it.validFor(lease, id, "measurement") } &&
            bundle.workouts.all { it.validFor(lease, id, "workout") })
        // Validate incoming data independently of existing local legacy records.
        val profile = bundle.parent.toRestoredProfile()
        val measures = bundle.measurements.associate { it.recordId to it.toMeasurement() }
        val workouts = bundle.workouts.associate { it.recordId to it.toWorkout() }
        require(measures.size == bundle.measurements.size && workouts.size == bundle.workouts.size)
        validateBackup(Backup(profiles = listOf(profile.copy(
            measurements = measures.values.toList(), history = workouts.values.toList(),
        ))))

        return database.withTransaction {
            requireLease(lease)
            val dao = database.sync()
            val stored = database.profiles().get(id)
            val binding = dao.binding(id)
            if ((cursors != null && (stored == null || binding == null)) ||
                (binding != null && (binding.accountUid != lease.uid || binding.consentAtMs <= 0)) ||
                (stored != null && binding == null)) {
                return@withTransaction CloudRestoreResult(conflicts = 1)
            }
            val local = stored?.let {
                localProfileJson.decodeFromString<Profile>(it.document).also { value -> require(value.id == id) }
            }
            val review = database.syncReview()
            val parentDecision = if (bundle.parent.tombstone) RestoreDecision.CONFLICT else
                decide(dao, bundle.parent, local?.let { restoreJson.encodeToString(it.cloudPayload()) })
            if (parentDecision == RestoreDecision.CONFLICT) {
                if (local != null && binding != null) review.mark(bundle.parent.reviewKey())
                requireLease(lease)
                return@withTransaction CloudRestoreResult(conflicts = 1)
            }
            review.clear(lease.uid, id, "profile", id)
            if (stored == null) {
                require(database.profiles().count() < MAX_BACKUP_PROFILES) {"Лимит локальных профилей достигнут"}
                if (binding == null) dao.bindForVerifiedConsent(SyncBindingRow(id, lease.uid, System.currentTimeMillis()))
            }
            val localMeasures = local?.measurements.orEmpty().associateByTo(linkedMapOf()) { it.id }
            val localWorkouts = local?.history.orEmpty().associateByTo(linkedMapOf()) { it.id }
            // Never collapse preexisting duplicate data, even if it came from a legacy backup.
            require(localMeasures.size == local?.measurements.orEmpty().size &&
                localWorkouts.size == local?.history.orEmpty().size)
            var restored = 0
            var conflicts = 0
            if (parentDecision == RestoreDecision.ACCEPT) {
                recordReceipt(dao, bundle.parent)
                if (local == null || local.cloudPayload() != profile.cloudPayload()) restored++
            }
            suspend fun merge(record: SyncRemoteEcho, current: String?, materialize: () -> Unit) {
                val decision = decide(dao, record, current)
                if (decision == RestoreDecision.CONFLICT) review.mark(record.reviewKey())
                else review.clear(lease.uid, id, record.kind, record.recordId)
                when (decision) {
                    RestoreDecision.ACCEPT -> {
                        recordReceipt(dao, record)
                        if (!record.tombstone && current != record.snapshot) {
                            materialize()
                            restored++
                        }
                    }
                    RestoreDecision.CONFLICT -> conflicts++
                    RestoreDecision.KEEP -> Unit
                }
            }
            for (record in bundle.measurements) {
                val value = localMeasures[record.recordId]?.let { restoreJson.encodeToString(it.cloudPayload()) }
                merge(record, value) { localMeasures[record.recordId] = measures.getValue(record.recordId) }
            }
            for (record in bundle.workouts) {
                val value = localWorkouts[record.recordId]?.let { restoreJson.encodeToString(it.cloudPayload()) }
                if (local?.active?.id == record.recordId) {
                    review.mark(record.reviewKey())
                    conflicts++
                } else {
                    merge(record, value) { localWorkouts[record.recordId] = workouts.getValue(record.recordId) }
                }
            }
            val next = (if (parentDecision == RestoreDecision.ACCEPT) profile else checkNotNull(local)).copy(
                measurements = localMeasures.values.toList(), history = localWorkouts.values.toList(),
                active = local?.active,
            )
            require(next.measurements.size <= 10_000 && next.history.size <= 10_000)
            if (next != local) database.profiles().put(ProfileRow(id, next.name, localProfileJson.encodeToString(next)))
            cursors?.forEach { cursor ->
                require(cursor.accountUid == lease.uid && cursor.profileId == id &&
                    cursor.kind in setOf("measurement", "workout"))
                review.saveCursor(cursor)
            }
            requireLease(lease)
            CloudRestoreResult(restored, conflicts, changesApplied = cursors != null)
        }
    }

    private suspend fun requireLease(expected: VerifiedSyncLease) {
        if (identity.currentLease() != expected) throw LocalWorkoutAccessRevoked()
    }
}

internal fun SyncRemoteEcho.validFor(lease: VerifiedSyncLease, id: String, expectedKind: String): Boolean =
    accountUid == lease.uid && profileId == id && kind == expectedKind &&
        cloudDocumentId.matches(recordId) && cloudDocumentId.matches(opId) && revision in 1..MAX_CLOUD_REVISION

private suspend fun decide(dao: SyncOutboxDao, remote: SyncRemoteEcho, current: String?): RestoreDecision {
    val previous = dao.headForAccount(remote.accountUid, remote.profileId, remote.kind, remote.recordId)
    val target = remote.snapshot.takeUnless { remote.tombstone }
    // Local history survives a remote deletion until the owner resolves that conflict.
    if (remote.tombstone && current != null) return RestoreDecision.CONFLICT
    if (previous == null) {
        return if (current == null || current == target) RestoreDecision.ACCEPT else RestoreDecision.CONFLICT
    }
    if (remote.matches(previous) && current == target) {
        return when (previous.state) {
            "ACKED" -> RestoreDecision.KEEP
            "PENDING" -> RestoreDecision.ACCEPT
            else -> RestoreDecision.CONFLICT
        }
    }
    if (previous.state != "ACKED" || previous.revision >= remote.revision ||
        (previous.tombstone && !remote.tombstone) ||
        current != previous.snapshot.takeUnless { previous.tombstone }) {
        return RestoreDecision.CONFLICT
    }
    return RestoreDecision.ACCEPT
}

internal suspend fun recordReceipt(dao: SyncOutboxDao, echo: SyncRemoteEcho) {
    val previous = dao.revisionForAccount(echo.accountUid, echo.profileId, echo.kind, echo.recordId, echo.revision)
    if (previous != null) {
        check(echo.matches(previous))
        if (previous.state == "ACKED") return
        check(dao.acknowledgeExactRemoteEcho(echo.accountUid, echo.opId, echo.profileId, echo.kind,
            echo.recordId, echo.revision, echo.snapshot, echo.tombstone) == 1)
    } else {
        dao.enqueue(SyncOutboxRow(echo.opId, echo.accountUid, echo.profileId, echo.kind,
            echo.recordId, echo.revision, echo.snapshot, "ACKED", echo.tombstone, System.currentTimeMillis()))
    }
}

internal fun SyncRemoteEcho.reviewKey() = SyncReviewRow(accountUid, profileId, kind, recordId)
