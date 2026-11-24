package ru.forma.app

import androidx.room.Room
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.forma.domain.Measurement
import ru.forma.domain.Profile
import ru.forma.domain.decodeBackup
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CloudSyncTest {
    private val json = Json { encodeDefaults = true }
    private val first = VerifiedSyncLease("account-a", 1)
    private val second = VerifiedSyncLease("account-b", 1)
    private val profile = Profile(id = "profile-a", name = "Synthetic cloud profile")
    private val measurement = Measurement("measure-a", 1, mapOf("weight" to 74.0))

    @Test fun schedulerDeduplicatesConnectedRequests() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(context.resources.getBoolean(R.bool.forma_cloud_sync_enabled))
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val manager = WorkManager.getInstance(context)
        val scheduler = CloudDeliveryScheduler(manager)
        repeat(2) {
            scheduler.enqueue().result.get(5, TimeUnit.SECONDS)
            scheduler.ensurePeriodic().result.get(5, TimeUnit.SECONDS)
            scheduler.ensureReconciliation().result.get(5, TimeUnit.SECONDS)
            scheduler.enqueueReconciliation().result.get(5, TimeUnit.SECONDS)
        }
        val oneTime = manager.getWorkInfosForUniqueWork(CloudDeliveryScheduler.IMMEDIATE).get(5, TimeUnit.SECONDS)
        val periodic = manager.getWorkInfosForUniqueWork(CloudDeliveryScheduler.PERIODIC).get(5, TimeUnit.SECONDS)
        assertEquals(NetworkType.CONNECTED, oneTime.single().constraints.requiredNetworkType)
        assertEquals(NetworkType.CONNECTED, periodic.single().constraints.requiredNetworkType)
        for (name in listOf(CloudDeliveryScheduler.RECONCILE_IMMEDIATE, CloudDeliveryScheduler.RECONCILE_PERIODIC)) {
            val scheduled = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).single()
            assertEquals(NetworkType.CONNECTED, scheduled.constraints.requiredNetworkType)
        }
    }

    @Test fun outboxRetryAndBatchStayOnTheLeaseFromTheFirstRecord() = runBlocking {
        withDatabase { db ->
            val repo = ProfileRepository.forGuest(db)
            val guestB = profile.copy(id = "profile-b", name = "Synthetic B")
            repo.saveProfile(profile)
            repo.saveProfile(guestB)
            repo.bindAndQueueInitialSnapshotForVerifiedConsent(profile.id, fixed(first), 1)
            repo.bindAndQueueInitialSnapshotForVerifiedConsent(guestB.id, fixed(second), 1)
            val identity = object : VerifiedSyncIdentity {
                override suspend fun currentLease() =
                    if (db.sync().headForAccount(first.uid, profile.id, "profile", profile.id)?.state == "ACKED")
                        second else first
            }
            var offline = true
            var observed: SyncRemoteEcho? = null
            val remote = object : AuthenticatedSyncRemote {
                override suspend fun read(lease: VerifiedSyncLease, row: SyncOutboxRow): SyncRemoteEcho? {
                    assertEquals(first, lease)
                    if (offline) throw FirebaseFirestoreException("Synthetic offline", FirebaseFirestoreException.Code.UNAVAILABLE)
                    return observed
                }
                override suspend fun compareAndCommit(
                    lease: VerifiedSyncLease, row: SyncOutboxRow, expected: SyncRemoteEcho?, expectedParent: SyncRemoteEcho?,
                ): Boolean {
                    observed = echo(row)
                    return true
                }
            }
            val batch = CloudDeliveryBatch(db, identity, remote)
            val pending = db.sync().nextPendingForAccount(first.uid)
            assertEquals(DeliveryBatchResult.RETRY, batch.run())
            assertEquals(pending, db.sync().nextPendingForAccount(first.uid))
            offline = false
            assertEquals(DeliveryBatchResult.COMPLETE, batch.run())
            assertNull(db.sync().nextPendingForAccount(first.uid))
            assertNotNull(db.sync().nextPendingForAccount(second.uid))
        }
    }

    @Test fun restoreKeepsLocalTombstoneConflictsAndRejectsAnotherOwnersSameId() = runBlocking {
        withDatabase { db ->
            val parent = SyncRemoteEcho(first.uid, profile.id, "profile", profile.id, 1,
                "profile-op", json.encodeToString(profile.cloudPayload()), false)
            val remoteMeasurement = SyncRemoteEcho(first.uid, profile.id, "measurement", measurement.id, 1,
                "measure-op", json.encodeToString(measurement.cloudPayload()), false)
            var bundle = RemoteProfileBundle(parent, listOf(remoteMeasurement), emptyList())
            val remote = object : AuthenticatedProfileRestore {
                override suspend fun listProfiles(lease: VerifiedSyncLease) = emptyList<CloudProfileChoice>()
                override suspend fun readProfile(lease: VerifiedSyncLease, id: String) = bundle
            }
            val restore = CloudProfileRestorer(db, fixed(first), remote)
            val repo = ProfileRepository(db)
            repo.saveProfile(profile)
            assertEquals(CloudRestoreResult(conflicts = 1), restore.restore(first, profile.id))
            assertNull(db.sync().binding(profile.id))
            repo.bindAndQueueInitialSnapshotForVerifiedConsent(profile.id, fixed(second), 1)
            assertEquals(CloudRestoreResult(conflicts = 1), restore.restore(first, profile.id))
            assertEquals(second.uid, db.sync().binding(profile.id)?.accountUid)
            assertEquals(profile, repo.load(profile.id))

            withDatabase { fresh ->
                val device = CloudProfileRestorer(fresh, fixed(first), remote)
                assertEquals(CloudRestoreResult(restored = 2), device.restore(first, profile.id))
                bundle = bundle.copy(measurements = listOf(remoteMeasurement.copy(
                    revision = 2, opId = "deleted-op", tombstone = true,
                )))
                assertEquals(CloudRestoreResult(conflicts = 1), device.restore(first, profile.id))
                assertEquals(listOf(measurement), ProfileRepository(fresh).load(profile.id)?.measurements)
                var workspaceOpen = true
                val scoped = ProfileRepository.forAccount(fresh, LocalWorkoutAccess(first) { workspaceOpen })
                assertEquals(listOf(measurement), decodeBackup(scoped.exportBackup(profile.id)).profiles.single().measurements)
                assertTrue(runCatching { scoped.exportBackup() }.isFailure)
                workspaceOpen = false
                assertTrue(runCatching { scoped.exportBackup(profile.id) }.exceptionOrNull() is LocalWorkoutAccessRevoked)

                withDatabase { withoutLocal ->
                    val emptyDevice = CloudProfileRestorer(withoutLocal, fixed(first), remote)
                    assertEquals(CloudRestoreResult(restored = 1), emptyDevice.restore(first, profile.id))
                    assertTrue(ProfileRepository(withoutLocal).load(profile.id)!!.measurements.isEmpty())
                    assertTrue(withoutLocal.sync().headForAccount(first.uid, profile.id, "measurement",
                        measurement.id)!!.tombstone)
                }
            }
        }
    }

    @Test fun restoreRollsBackNewBindingAndReceiptsWhenAccountChangesBeforeCommit() = runBlocking {
        withDatabase { db ->
            val parent = SyncRemoteEcho(first.uid, profile.id, "profile", profile.id, 1,
                "remote-profile", json.encodeToString(profile.cloudPayload()), false)
            val remote = object : AuthenticatedProfileRestore {
                override suspend fun listProfiles(lease: VerifiedSyncLease) = emptyList<CloudProfileChoice>()
                override suspend fun readProfile(lease: VerifiedSyncLease, id: String) =
                    RemoteProfileBundle(parent, emptyList(), emptyList())
            }
            var authorityChecks = 0
            val switching = object : VerifiedSyncIdentity {
                override suspend fun currentLease() = if (++authorityChecks < 3) first else second
            }
            val restore = CloudProfileRestorer(db, switching, remote)
            assertTrue(runCatching { restore.restore(first, profile.id) }.exceptionOrNull() is LocalWorkoutAccessRevoked)
            assertNull(ProfileRepository(db).load(profile.id))
            assertNull(db.sync().binding(profile.id))
            assertEquals(0, db.sync().totalRows())
        }
    }

    @Test fun reconciliationDoesNotAdvanceTheCursorAcrossAnOfflineReadOrAccountSwitch() = runBlocking {
        withDatabase { db ->
            val parent = SyncRemoteEcho(first.uid, profile.id, "profile", profile.id, 1,
                "remote-profile", json.encodeToString(profile.cloudPayload()), false)
            val record = SyncRemoteEcho(first.uid, profile.id, "measurement", measurement.id, 1,
                "remote-measure", json.encodeToString(measurement.cloudPayload()), false)
            val cursor = SyncPullRow(first.uid, profile.id, "measurement", 1L, 0, measurement.id)
            var signedIn = first
            var offline = true
            var switchAfterRead = false
            var reads = 0
            val identity = object : VerifiedSyncIdentity {
                override suspend fun currentLease() = signedIn
            }
            val remote = object : AuthenticatedProfileRestore, AuthenticatedProfileChanges {
                override suspend fun listProfiles(lease: VerifiedSyncLease) = emptyList<CloudProfileChoice>()
                override suspend fun readProfile(lease: VerifiedSyncLease, id: String) =
                    RemoteProfileBundle(parent, emptyList(), emptyList())
                override suspend fun readForReview(lease: VerifiedSyncLease, key: SyncReviewRow) = null
                override suspend fun readChanges(
                    lease: VerifiedSyncLease, id: String, measurement: SyncPullRow?, workout: SyncPullRow?,
                ): BoundCloudChanges {
                    assertEquals(first, lease)
                    assertEquals(profile.id, id)
                    reads++
                    assertNull(measurement)
                    assertNull(workout)
                    if (offline) throw FirebaseFirestoreException("Synthetic offline", FirebaseFirestoreException.Code.UNAVAILABLE)
                    if (switchAfterRead) signedIn = second
                    return BoundCloudChanges(RemoteProfileBundle(parent, listOf(record), emptyList()), listOf(cursor), false)
                }
            }
            val restorer = CloudProfileRestorer(db, identity, remote)
            restorer.restore(first, profile.id)
            val repository = ProfileRepository(db)
            repository.mutateForVerifiedAccount(profile.id, first, identity) { it.copy(recovery = 4) }
            val pending = checkNotNull(db.sync().nextPendingForAccount(first.uid))
            assertEquals("profile", pending.kind)
            val reconciler = CloudInboundReconciler(db, identity, remote, restorer)
            suspend fun run() = runCloudBatch { reconciler.run() }
            assertEquals(DeliveryBatchResult.RETRY, run())
            assertNull(db.syncReview().cursor(first.uid, profile.id, "measurement"))
            offline = false
            switchAfterRead = true
            assertEquals(DeliveryBatchResult.COMPLETE, run())
            assertNull(db.syncReview().cursor(first.uid, profile.id, "measurement"))
            assertTrue(ProfileRepository(db).load(profile.id)!!.measurements.isEmpty())
            assertEquals(DeliveryBatchResult.COMPLETE, run())
            assertEquals(2, reads)
            signedIn = first
            switchAfterRead = false
            assertEquals(DeliveryBatchResult.COMPLETE, run())
            assertEquals(cursor, db.syncReview().cursor(first.uid, profile.id, "measurement"))
            assertNull(db.syncReview().cursor(second.uid, profile.id, "measurement"))
            val updated = checkNotNull(repository.load(profile.id))
            assertEquals(listOf(measurement), updated.measurements)
            assertEquals(4, updated.recovery)
            assertEquals(pending, db.sync().nextPendingForAccount(first.uid))
            assertTrue(db.syncReview().reviews(first.uid, 1).isEmpty())
        }
    }

    private suspend fun withDatabase(block: suspend (FormaDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), FormaDatabase::class.java,
        ).build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }
    private fun fixed(lease: VerifiedSyncLease) = object : VerifiedSyncIdentity {
        override suspend fun currentLease() = lease
    }
    private fun echo(row: SyncOutboxRow) = SyncRemoteEcho(row.accountUid, row.profileId, row.kind, row.recordId,
        row.revision, row.opId, row.snapshot, row.tombstone)
}
