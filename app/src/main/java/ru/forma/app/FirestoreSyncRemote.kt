package ru.forma.app

import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import ru.forma.domain.MAX_BACKUP_BYTES
import ru.forma.domain.MAX_BACKUP_PROFILES

/** One explicitly configured Firebase app supplies both the Auth session and the Firestore client. */
internal class FirestoreSyncRemote(
    app: FirebaseApp,
    private val identity: VerifiedSyncIdentity,
) : AuthenticatedSyncRemote, AuthenticatedProfileRestore, AuthenticatedProfileChanges {
    private val auth = FirebaseAuth.getInstance(app)
    private val firestore = FirebaseFirestore.getInstance(app).apply {
        // Room already persists the outbox. Do not retain another account's cloud cache on disk.
        firestoreSettings = FirebaseFirestoreSettings.Builder(firestoreSettings)
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
    }

    override suspend fun read(lease: VerifiedSyncLease, row: SyncOutboxRow): SyncRemoteEcho? {
        return readDocument(lease, document(lease, SyncReviewRow(row.accountUid, row.profileId, row.kind, row.recordId)), row.profileId, row.kind, row.recordId)
    }

    override suspend fun readForReview(lease: VerifiedSyncLease, key: SyncReviewRow): SyncRemoteEcho? =
        readDocument(lease, document(lease, key), key.profileId, key.kind, key.recordId)

    private suspend fun readDocument(
        lease: VerifiedSyncLease, path: DocumentReference, profileId: String, kind: String, id: String,
    ): SyncRemoteEcho? {
        requireCurrent(lease)
        val document = path.get(Source.SERVER).await()
        requireCurrent(lease)
        check(!document.metadata.isFromCache && !document.metadata.hasPendingWrites())
        return document.echo(lease.uid, profileId, kind, id)
    }

    override suspend fun compareAndCommit(
        lease: VerifiedSyncLease,
        row: SyncOutboxRow,
        expected: SyncRemoteEcho?,
        expectedParent: SyncRemoteEcho?,
    ): Boolean {
        requireCurrent(lease)
        val target = document(lease, SyncReviewRow(row.accountUid, row.profileId, row.kind, row.recordId))
        val parent = when (row.kind) {
            "profile" -> {
                require(expectedParent == null)
                null
            }
            else -> {
                require(expectedParent != null && !expectedParent.tombstone)
                profile(lease.uid, row.profileId)
            }
        }
        val payload = FirestoreSyncPayload.document(row.kind, row.snapshot)
        val committed = firestore.runTransaction { transaction ->
            check(auth.currentUser?.uid == lease.uid &&
                (identity !is FirebaseSyncIdentity || identity.localLease() == lease)) {
                "Account changed during transaction"
            }
            if (parent != null) {
                val observed = transaction.get(parent).echo(lease.uid, row.profileId, "profile", row.profileId)
                if (observed != expectedParent) return@runTransaction false
            }
            val current = transaction.get(target)
            if (current.echo(lease.uid, row.profileId, row.kind, row.recordId) != expected) {
                return@runTransaction false
            }
            val createdAt = if (current.exists()) {
                checkNotNull(current.getTimestamp("createdAt"))
            } else FieldValue.serverTimestamp()
            transaction.set(target, mapOf(
                "ownerUid" to lease.uid,
                "profileId" to row.profileId,
                "id" to row.recordId,
                "schemaVersion" to 1L,
                "revision" to row.revision.toLong(),
                "opId" to row.opId,
                "createdAt" to createdAt,
                "updatedAt" to FieldValue.serverTimestamp(),
                "deleted" to row.tombstone,
                "payload" to payload,
            ))
            true
        }.await()
        requireCurrent(lease)
        return committed
    }

    override suspend fun listProfiles(lease: VerifiedSyncLease): List<CloudProfileChoice> {
        requireCurrent(lease)
        val page = profiles(lease.uid).limit(MAX_BACKUP_PROFILES.toLong() + 1).get(Source.SERVER).await()
        requireCurrent(lease)
        check(!page.metadata.isFromCache && !page.metadata.hasPendingWrites())
        require(page.size() <= MAX_BACKUP_PROFILES) { "Слишком много облачных профилей" }
        return page.documents.mapNotNull { document ->
            val record = checkNotNull(document.echo(lease.uid, document.id, "profile", document.id))
            if (record.tombstone) null else CloudProfileChoice(record.profileId, record.toRestoredProfile().name)
        }
    }

    override suspend fun readProfile(lease: VerifiedSyncLease, id: String): RemoteProfileBundle? {
        require(cloudDocumentId.matches(id))
        val parent = profile(lease.uid, id)
        val first = readDocument(lease, parent, id, "profile", id) ?: return null
        if (first.tombstone) return RemoteProfileBundle(first, emptyList(), emptyList())
        var remainingBytes = MAX_BACKUP_BYTES - first.snapshot.encodeToByteArray().size
        fun accountBytes(record: SyncRemoteEcho) {
            remainingBytes -= record.snapshot.encodeToByteArray().size
            require(remainingBytes >= 0) { "Облачная история превышает лимит восстановления" }
        }
        val measurements = readChildren(lease, id, "measurement", parent.collection("measurements"), ::accountBytes)
        val workouts = readChildren(lease, id, "workout", parent.collection("workouts"), ::accountBytes)
        check(readDocument(lease, parent, id, "profile", id) == first) {
            "Облачный профиль изменился во время чтения"
        }
        return RemoteProfileBundle(first, measurements, workouts)
    }

    override suspend fun readChanges(
        lease: VerifiedSyncLease, id: String, measurement: SyncPullRow?, workout: SyncPullRow?,
    ): BoundCloudChanges? {
        require(cloudDocumentId.matches(id))
        val parent = profile(lease.uid, id)
        val first = readDocument(lease, parent, id, "profile", id) ?: return null
        if (first.tombstone) return BoundCloudChanges(
            RemoteProfileBundle(first, emptyList(), emptyList()), emptyList(), false,
        )
        val measurements = readChangesPage(lease, id, "measurement", parent.collection("measurements"), measurement)
        val workouts = readChangesPage(lease, id, "workout", parent.collection("workouts"), workout)
        if (readDocument(lease, parent, id, "profile", id) != first) throw CloudRemoteChanged()
        val records = measurements.first + workouts.first
        require(records.sumOf { it.snapshot.encodeToByteArray().size.toLong() } <= MAX_BACKUP_BYTES)
        return BoundCloudChanges(
            RemoteProfileBundle(first, measurements.first, workouts.first),
            listOfNotNull(measurements.second, workouts.second),
            measurements.first.size == CHANGE_PAGE_SIZE || workouts.first.size == CHANGE_PAGE_SIZE,
        )
    }

    private suspend fun readChangesPage(
        lease: VerifiedSyncLease, id: String, kind: String, source: CollectionReference, cursor: SyncPullRow?,
    ): Pair<List<SyncRemoteEcho>, SyncPullRow?> {
        val ordered = source.orderBy("updatedAt").orderBy(FieldPath.documentId()).limit(CHANGE_PAGE_SIZE.toLong())
        val query = if (cursor == null) ordered else {
            require(cursor.accountUid == lease.uid && cursor.profileId == id && cursor.kind == kind &&
                cloudDocumentId.matches(cursor.recordId))
            ordered.startAfter(Timestamp(cursor.seconds, cursor.nanos), cursor.recordId)
        }
        requireCurrent(lease)
        val page = query.get(Source.SERVER).await()
        requireCurrent(lease)
        check(!page.metadata.isFromCache && !page.metadata.hasPendingWrites())
        val records = page.documents.map { checkNotNull(it.echo(lease.uid, id, kind, it.id)) }
        val next = page.documents.lastOrNull()?.let {
            val time = checkNotNull(it.getTimestamp("updatedAt"))
            SyncPullRow(lease.uid, id, kind, time.seconds, time.nanoseconds, it.id)
        }
        return records to next
    }

    private suspend fun readChildren(
        lease: VerifiedSyncLease, id: String, kind: String, source: CollectionReference,
        accountBytes: (SyncRemoteEcho) -> Unit,
    ): List<SyncRemoteEcho> {
        val records = mutableListOf<SyncRemoteEcho>()
        val ordered = source.orderBy(FieldPath.documentId()).limit(64)
        var last: DocumentSnapshot? = null
        do {
            requireCurrent(lease)
            val page = (last?.let { ordered.startAfter(it) } ?: ordered).get(Source.SERVER).await()
            requireCurrent(lease)
            check(!page.metadata.isFromCache && !page.metadata.hasPendingWrites())
            require(records.size + page.size() <= 10_000) { "Облачная история превышает лимит восстановления" }
            page.documents.forEach {
                records += checkNotNull(it.echo(lease.uid, id, kind, it.id)).also(accountBytes)
            }
            last = page.documents.lastOrNull()
        } while (page.size() == 64)
        return records
    }

    private suspend fun requireCurrent(lease: VerifiedSyncLease) {
        if (identity.currentLease() != lease || auth.currentUser?.uid != lease.uid) {
            throw LocalWorkoutAccessRevoked()
        }
    }

    private fun document(lease: VerifiedSyncLease, row: SyncReviewRow): DocumentReference {
        require(lease.uid == row.accountUid && cloudDocumentId.matches(lease.uid))
        require(cloudDocumentId.matches(row.profileId) && cloudDocumentId.matches(row.recordId))
        val parent = profile(lease.uid, row.profileId)
        return when (row.kind) {
            "profile" -> parent.also { require(row.recordId == row.profileId) }
            "measurement" -> parent.collection("measurements").document(row.recordId)
            "workout" -> parent.collection("workouts").document(row.recordId)
            else -> error("Unknown sync record kind")
        }
    }

    private fun profiles(uid: String): CollectionReference {
        require(cloudDocumentId.matches(uid))
        return firestore.collection("accounts").document(uid).collection("profiles")
    }

    private fun profile(uid: String, profileId: String): DocumentReference = profiles(uid).document(profileId)

    private fun DocumentSnapshot.echo(uid: String, profileId: String, kind: String, id: String): SyncRemoteEcho? {
        if (!exists()) return null
        val fields = checkNotNull(data)
        check(cloudDocumentId.matches(id) && fields.keys == envelopeFields && fields["ownerUid"] == uid &&
            fields["profileId"] == profileId && fields["id"] == id && fields["schemaVersion"] == 1L)
        check(fields["createdAt"] is Timestamp && fields["updatedAt"] is Timestamp)
        val revision = (fields["revision"] as? Long)?.takeIf { it in 1L..MAX_CLOUD_REVISION.toLong() }
        val opId = (fields["opId"] as? String)?.takeIf(cloudDocumentId::matches)
        val payload = checkNotNull(fields["payload"] as? Map<*, *>)
        return SyncRemoteEcho(
            uid, profileId, kind, id, checkNotNull(revision).toInt(), checkNotNull(opId),
            FirestoreSyncPayload.snapshot(kind, payload), checkNotNull(fields["deleted"] as? Boolean),
        )
    }

    private companion object {
        const val CHANGE_PAGE_SIZE = 16
        val envelopeFields = setOf("ownerUid", "profileId", "id", "schemaVersion", "revision", "opId",
            "createdAt", "updatedAt", "deleted", "payload")
    }
}
