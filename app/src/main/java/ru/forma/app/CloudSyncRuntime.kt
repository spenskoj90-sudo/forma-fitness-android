package ru.forma.app

import android.content.Context
import androidx.work.WorkManager
import androidx.work.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One transport and one mutation path for a configured app process. */
internal class CloudSyncRuntime(
    context: Context,
    private val database: FormaDatabase,
    private val account: FirebaseAccount,
) {
    private val scheduler = CloudDeliveryScheduler(WorkManager.getInstance(context))
    private val remote by lazy(account::firestoreTransport)
    private val transfer = Mutex()

    suspend fun watch() = scheduler.watch(account, database)

    suspend fun enqueue() {
        scheduler.enqueue().await()
    }

    suspend fun deliver(): DeliveryBatchResult = runVerifiedBatch { identity ->
        CloudDeliveryBatch(database, identity, remote).run()
    }

    suspend fun enqueueReconciliation() {
        scheduler.enqueueReconciliation().await()
    }

    suspend fun reconcile(): DeliveryBatchResult = runVerifiedBatch { identity ->
        CloudInboundReconciler(database, identity, remote, CloudProfileRestorer(database, identity, remote)).run()
    }

    private suspend fun runVerifiedBatch(run: suspend (VerifiedSyncIdentity) -> DeliveryBatchResult): DeliveryBatchResult =
        transfer.withLock {
            val lease = account.identity.localLease() ?: return@withLock DeliveryBatchResult.COMPLETE
            runCloudBatch {
                if (account.identity.verifyOnlineLease(lease) != lease) return@runCloudBatch DeliveryBatchResult.COMPLETE
                run(openScope { account.identity.localLease() == lease })
            }
        }

    suspend fun reviews(lease: VerifiedSyncLease, stillOpen: () -> Boolean): List<SyncReviewRow> =
        resolver(stillOpen).reviews(lease)

    suspend fun archives(lease: VerifiedSyncLease, stillOpen: () -> Boolean): List<SyncResolutionArchiveRow> =
        resolver(stillOpen).archives(lease)

    suspend fun preview(
        lease: VerifiedSyncLease, key: SyncReviewRow, stillOpen: () -> Boolean,
    ): CloudConflictPreview = transfer.withLock {
        CloudConflictResolver(database, verifyScope(lease, stillOpen), remote).preview(lease, key)
    }

    suspend fun resolve(
        lease: VerifiedSyncLease, preview: CloudConflictPreview, choice: CloudConflictChoice, stillOpen: () -> Boolean,
    ) {
        transfer.withLock {
            CloudConflictResolver(database, verifyScope(lease, stillOpen), remote).resolve(lease, preview, choice)
        }
        scheduler.enqueue().await()
        scheduler.enqueueReconciliation().await()
    }

    suspend fun status(lease: VerifiedSyncLease, stillOpen: () -> Boolean): LocalSyncSummary =
        SyncStatusReader(database, object : VerifiedSyncIdentity {
            override suspend fun currentLease() = account.identity.localLease()
                ?.takeIf { it == lease && stillOpen() }
        }).read()

    suspend fun list(lease: VerifiedSyncLease, stillOpen: () -> Boolean): List<CloudProfileChoice> =
        CloudProfileRestorer(database, verifyScope(lease, stillOpen), remote).list(lease)

    suspend fun restore(lease: VerifiedSyncLease, id: String, stillOpen: () -> Boolean): CloudRestoreResult =
        transfer.withLock {
            CloudProfileRestorer(database, verifyScope(lease, stillOpen), remote).restore(lease, id)
        }

    private suspend fun verifyScope(lease: VerifiedSyncLease, stillOpen: () -> Boolean): VerifiedSyncIdentity {
        if (!stillOpen() || account.identity.verifyOnlineLease(lease) != lease || !stillOpen()) {
            throw LocalWorkoutAccessRevoked()
        }
        return openScope { stillOpen() && account.identity.localLease() == lease }
    }

    private fun resolver(stillOpen: () -> Boolean) =
        CloudConflictResolver(database, openScope(stillOpen), remote)

    private fun openScope(stillOpen: () -> Boolean): VerifiedSyncIdentity = object : VerifiedSyncIdentity {
        override suspend fun currentLease() = if (!stillOpen()) null else
            account.identity.currentLease()?.takeIf { stillOpen() }
    }
}
