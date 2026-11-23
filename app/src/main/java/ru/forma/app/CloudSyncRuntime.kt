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

    suspend fun deliver(): DeliveryBatchResult = transfer.withLock {
        if (account.identity.localLease() == null) return@withLock DeliveryBatchResult.COMPLETE
        CloudDeliveryBatch(database, account.identity, remote).run()
    }

    suspend fun status(lease: VerifiedSyncLease, stillOpen: () -> Boolean): LocalSyncSummary =
        SyncStatusReader(database, object : VerifiedSyncIdentity {
            override suspend fun currentLease() = account.identity.localLease()
                ?.takeIf { it == lease && stillOpen() }
        }).read()

    suspend fun list(lease: VerifiedSyncLease, stillOpen: () -> Boolean): List<CloudProfileChoice> =
        restorer(stillOpen).list(lease)

    suspend fun restore(lease: VerifiedSyncLease, id: String, stillOpen: () -> Boolean): CloudRestoreResult =
        transfer.withLock { restorer(stillOpen).restore(lease, id) }

    private fun restorer(stillOpen: () -> Boolean): CloudProfileRestorer =
        CloudProfileRestorer(database, object : VerifiedSyncIdentity {
            override suspend fun currentLease() = if (!stillOpen()) null else
                account.identity.currentLease()?.takeIf { stillOpen() }
        }, remote)
}
