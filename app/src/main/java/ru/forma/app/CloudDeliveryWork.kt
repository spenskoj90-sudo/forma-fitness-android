package ru.forma.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

internal enum class DeliveryBatchResult { COMPLETE, RETRY, BLOCKED }

/** Bind an entire drain to its first verified account, even when every record rechecks auth. */
internal class CloudDeliveryBatch(
    private val database: FormaDatabase,
    private val identity: VerifiedSyncIdentity,
    private val remote: AuthenticatedSyncRemote,
) {
    suspend fun run(): DeliveryBatchResult = try {
        withTimeoutOrNull(45_000) { drain() } ?: DeliveryBatchResult.RETRY
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: FirebaseNetworkException) {
        DeliveryBatchResult.RETRY
    } catch (error: FirebaseFirestoreException) {
        if (error.code in retryableFirestoreCodes) DeliveryBatchResult.RETRY else DeliveryBatchResult.BLOCKED
    } catch (_: IllegalArgumentException) {
        DeliveryBatchResult.BLOCKED
    } catch (_: IllegalStateException) {
        DeliveryBatchResult.BLOCKED
    }

    private suspend fun drain(): DeliveryBatchResult {
        val expected = identity.currentLease() ?: return DeliveryBatchResult.COMPLETE
        val pinned = object : VerifiedSyncIdentity {
            override suspend fun currentLease() = identity.currentLease()?.takeIf { it == expected }
        }
        val sender = SyncDeliveryCoordinator(database, pinned, remote)
        repeat(32) {
            when (sender.deliverOne()) {
                SyncDeliveryStep.NO_AUTH, SyncDeliveryStep.SESSION_CHANGED, SyncDeliveryStep.EMPTY ->
                    return DeliveryBatchResult.COMPLETE
                SyncDeliveryStep.RETRY -> return DeliveryBatchResult.RETRY
                SyncDeliveryStep.ACKED, SyncDeliveryStep.CONFLICT -> Unit
            }
        }
        return DeliveryBatchResult.RETRY
    }

    private companion object {
        val retryableFirestoreCodes = setOf(
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
            FirebaseFirestoreException.Code.ABORTED,
            FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED,
            FirebaseFirestoreException.Code.INTERNAL,
        )
    }
}

/** Requests contain only a worker name and network constraint. Room owns the durable payload. */
internal class CloudDeliveryScheduler(private val manager: WorkManager) {
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun ensurePeriodic() = manager.enqueueUniquePeriodicWork(
        PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<CloudDeliveryWorker>(1, TimeUnit.HOURS)
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build(),
    )

    fun enqueue() = manager.enqueueUniqueWork(
        IMMEDIATE, ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<CloudDeliveryWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build(),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun watch(account: FirebaseAccount, database: FormaDatabase) {
        ensurePeriodic().await()
        account.localLeases.flatMapLatest { lease ->
            if (lease == null) emptyFlow()
            else database.sync().outboxInsertWatermark(lease.uid).distinctUntilChanged()
        }.collect { watermark ->
            if (watermark != null) enqueue().await()
        }
    }

    companion object {
        internal const val PERIODIC = "forma-cloud-delivery-periodic"
        internal const val IMMEDIATE = "forma-cloud-delivery"
    }
}

/** Public constructor allows WorkManager to recreate the worker after process death. */
class CloudDeliveryWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val runtime = (applicationContext as FormaApp).cloudSync ?: return Result.success()
        return when (runtime.deliver()) {
            DeliveryBatchResult.COMPLETE -> Result.success()
            DeliveryBatchResult.RETRY -> Result.retry()
            DeliveryBatchResult.BLOCKED -> Result.failure()
        }
    }
}
