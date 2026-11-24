package ru.forma.app

import androidx.room.withTransaction

internal data class BoundCloudChanges(
    val bundle: RemoteProfileBundle,
    val cursors: List<SyncPullRow>,
    val hasMore: Boolean,
)

internal interface AuthenticatedProfileChanges {
    suspend fun readForReview(lease: VerifiedSyncLease, key: SyncReviewRow): SyncRemoteEcho?
    suspend fun readChanges(
        lease: VerifiedSyncLease, id: String, measurement: SyncPullRow?, workout: SyncPullRow?,
    ): BoundCloudChanges?
}

internal class CloudRemoteChanged : IllegalStateException()

/** Poll only profiles already present and consented to in this account's Room workspace. */
internal class CloudInboundReconciler(
    private val database: FormaDatabase,
    private val identity: VerifiedSyncIdentity,
    private val remote: AuthenticatedProfileChanges,
    private val restorer: CloudProfileRestorer,
) {
    suspend fun run(): DeliveryBatchResult {
        val lease = identity.currentLease() ?: return DeliveryBatchResult.COMPLETE
        val review = database.syncReview()
        val profiles = database.sync().existingProfileIds(lease.uid)
        val last = review.cursor(lease.uid, "", "rotation")?.recordId
        val start = profiles.indexOfFirst { last == null || it > last }.coerceAtLeast(0)
        val batch = (profiles.drop(start) + profiles.take(start)).take(4)
        var hasMore = false
        var blocked = false
        for (id in batch) {
            if (identity.currentLease() != lease) return DeliveryBatchResult.COMPLETE
            try {
                val changes = remote.readChanges(
                    lease, id, review.cursor(lease.uid, id, "measurement"), review.cursor(lease.uid, id, "workout"),
                )
                if (changes != null) {
                    val result = restorer.applyChanges(lease, id, changes)
                    // A conflicted parent leaves its child cursor parked.
                    hasMore = hasMore || (result.changesApplied && changes.hasMore)
                }
            } catch (revoked: LocalWorkoutAccessRevoked) {
                throw revoked
            } catch (_: CloudRemoteChanged) {
                hasMore = true
            } catch (_: IllegalArgumentException) {
                blocked = true
            } catch (_: IllegalStateException) {
                blocked = true
            }
            database.withTransaction {
                if (identity.currentLease() != lease) throw LocalWorkoutAccessRevoked()
                review.saveCursor(SyncPullRow(lease.uid, "", "rotation", 0, 0, id))
                if (identity.currentLease() != lease) throw LocalWorkoutAccessRevoked()
            }
        }
        return when {
            hasMore -> DeliveryBatchResult.RETRY
            blocked -> DeliveryBatchResult.BLOCKED
            else -> DeliveryBatchResult.COMPLETE
        }
    }
}
