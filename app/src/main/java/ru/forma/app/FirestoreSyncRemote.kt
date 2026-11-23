package ru.forma.app

import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/** One explicitly configured Firebase app supplies both the Auth session and the Firestore client. */
internal class FirestoreSyncRemote(
    app: FirebaseApp,
    private val identity: VerifiedSyncIdentity,
) : AuthenticatedSyncRemote {
    private val auth = FirebaseAuth.getInstance(app)
    private val firestore = FirebaseFirestore.getInstance(app).apply {
        // Room already persists the outbox. Do not retain another account's cloud cache on disk.
        firestoreSettings = FirebaseFirestoreSettings.Builder(firestoreSettings)
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
    }

    override suspend fun read(lease: VerifiedSyncLease, row: SyncOutboxRow): SyncRemoteEcho? {
        requireCurrent(lease)
        val document = document(lease, row).get(Source.SERVER).await()
        requireCurrent(lease)
        check(!document.metadata.isFromCache && !document.metadata.hasPendingWrites())
        return document.echo(lease.uid, row.profileId, row.kind, row.recordId)
    }

    override suspend fun compareAndCommit(
        lease: VerifiedSyncLease,
        row: SyncOutboxRow,
        expected: SyncRemoteEcho?,
        expectedParent: SyncRemoteEcho?,
    ): Boolean {
        requireCurrent(lease)
        val target = document(lease, row)
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
            check(auth.currentUser?.uid == lease.uid) { "Account changed during transaction" }
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

    private suspend fun requireCurrent(lease: VerifiedSyncLease) {
        check(identity.currentLease() == lease && auth.currentUser?.uid == lease.uid) {
            "Verified account session changed"
        }
    }

    private fun document(lease: VerifiedSyncLease, row: SyncOutboxRow): DocumentReference {
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

    private fun profile(uid: String, profileId: String): DocumentReference =
        firestore.collection("accounts").document(uid).collection("profiles").document(profileId)

    private fun DocumentSnapshot.echo(uid: String, profileId: String, kind: String, id: String): SyncRemoteEcho? {
        if (!exists()) return null
        val fields = checkNotNull(data)
        check(fields.keys == envelopeFields && fields["ownerUid"] == uid &&
            fields["profileId"] == profileId && fields["id"] == id && fields["schemaVersion"] == 1L)
        check(fields["createdAt"] is Timestamp && fields["updatedAt"] is Timestamp)
        val revision = (fields["revision"] as? Long)?.takeIf { it in 1L until Int.MAX_VALUE.toLong() }
        val opId = (fields["opId"] as? String)?.takeIf(cloudDocumentId::matches)
        val payload = checkNotNull(fields["payload"] as? Map<*, *>)
        return SyncRemoteEcho(
            uid, profileId, kind, id, checkNotNull(revision).toInt(), checkNotNull(opId),
            FirestoreSyncPayload.snapshot(kind, payload), checkNotNull(fields["deleted"] as? Boolean),
        )
    }

    private companion object {
        val envelopeFields = setOf("ownerUid", "profileId", "id", "schemaVersion", "revision", "opId",
            "createdAt", "updatedAt", "deleted", "payload")
    }
}
