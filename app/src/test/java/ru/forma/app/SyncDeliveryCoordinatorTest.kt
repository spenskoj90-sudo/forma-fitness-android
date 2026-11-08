package ru.forma.app

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.forma.domain.*
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SyncDeliveryCoordinatorTest {
 private class SyntheticIdentity(var active:VerifiedSyncLease?):VerifiedSyncIdentity {
  override suspend fun currentLease()=active
 }
 private class FakeRemote:AuthenticatedSyncRemote {
  val documents=mutableMapOf<String,SyncRemoteEcho>()
  var writes=0
  var reads=0
  var failOnRead=0
  var rejectWrite=false
  var afterRead:(()->Unit)?=null
  private fun key(row:SyncOutboxRow)=
   row.accountUid+"/"+row.profileId+"/"+row.kind+"/"+row.recordId
  override suspend fun read(lease:VerifiedSyncLease,row:SyncOutboxRow):SyncRemoteEcho? {
   require(lease.uid==row.accountUid)
   reads++
   if(reads==failOnRead)throw IOException("Synthetic offline after a remote write")
   val snapshot=documents[key(row)]
   afterRead?.invoke()
   return snapshot
  }
  override suspend fun compareAndCommit(
   lease:VerifiedSyncLease,row:SyncOutboxRow,expected:SyncRemoteEcho?
  ):Boolean {
   require(lease.uid==row.accountUid)
   if(rejectWrite||documents[key(row)]!=expected)return false
   documents[key(row)]=SyncRemoteEcho(
    row.accountUid,row.profileId,row.kind,row.recordId,row.revision,
    row.opId,row.snapshot,row.tombstone
   )
   writes++
   return true
  }
  fun overwrite(row:SyncOutboxRow,remote:SyncRemoteEcho){documents[key(row)]=remote}
  fun stored(row:SyncOutboxRow)=documents[key(row)]
 }

 @Test fun remoteWriteWithoutReadbackIsNotAckedAndReplayUsesExistingRemoteEcho():Unit=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  try {
   val repo=ProfileRepository(db)
   repo.saveProfile(Profile(id="offline-a",name="Alice",measurements=listOf(
    Measurement(id="metric-a",dateMs=1000L,values=mapOf("weight" to 75.0)))))
   repo.bindAndQueueInitialSnapshotForVerifiedConsent("offline-a","uid-A",2000L)
   val identity=SyntheticIdentity(VerifiedSyncLease("uid-A",1L))
   val remote=FakeRemote().apply{failOnRead=2}
   val coordinator=SyncDeliveryCoordinator(db,identity,remote)
   val first=db.sync().nextPendingForAccount("uid-A")!!
   assertEquals("profile",first.kind)
   assertTrue(runCatching{coordinator.deliverOne()}.exceptionOrNull() is IOException)
   assertEquals(1,remote.writes)
   assertEquals(first,db.sync().nextPendingForAccount("uid-A"))
   // The remote already accepted this opId. Do not create or charge it twice.
   assertEquals(SyncDeliveryStep.ACKED,coordinator.deliverOne())
   assertEquals(1,remote.writes)
   assertEquals("measurement",db.sync().nextPendingForAccount("uid-A")!!.kind)
   assertEquals(SyncDeliveryStep.ACKED,coordinator.deliverOne())
   assertEquals(SyncDeliveryStep.EMPTY,coordinator.deliverOne())
   assertEquals(2,remote.writes)
   assertEquals(2,db.sync().totalRows())
   assertTrue(db.sync().pendingForAccount("uid-A").isEmpty())
  }finally{db.close()}
 }

 @Test fun changingAccountOrEpochDuringRemoteReadPreventsWriteAndAck():Unit=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  try {
   val repo=ProfileRepository(db)
   repo.saveProfile(Profile(id="a",name="Alice"))
   repo.saveProfile(Profile(id="b",name="Bob"))
   repo.bindAndQueueInitialSnapshotForVerifiedConsent("a","uid-A",100L)
   repo.bindAndQueueInitialSnapshotForVerifiedConsent("b","uid-B",200L)
   val identity=SyntheticIdentity(VerifiedSyncLease("uid-A",1L))
   val remote=FakeRemote()
   remote.afterRead={identity.active=VerifiedSyncLease("uid-B",2L)}
   val coordinator=SyncDeliveryCoordinator(db,identity,remote)
   assertEquals(SyncDeliveryStep.SESSION_CHANGED,coordinator.deliverOne())
   assertEquals(0,remote.writes)
   assertEquals(1,db.sync().pendingForAccount("uid-A").size)
   remote.afterRead=null
   assertEquals(SyncDeliveryStep.ACKED,coordinator.deliverOne())
   assertEquals("uid-B",remote.documents.values.single().accountUid)
   assertEquals(1,db.sync().pendingForAccount("uid-A").size)
   identity.active=null
   assertEquals(SyncDeliveryStep.NO_AUTH,coordinator.deliverOne())
   identity.active=VerifiedSyncLease("uid-A",3L)
   assertEquals(SyncDeliveryStep.ACKED,coordinator.deliverOne())
   assertTrue(db.sync().pendingForAccount("uid-A").isEmpty())
  }finally{db.close()}
 }

 @Test fun changedOrStaleRemotePredecessorNeverSilentlyOverwritesHistory():Unit=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  try {
   val repo=ProfileRepository(db)
   repo.saveProfile(Profile(id="a",name="Alice"))
   repo.bindAndQueueInitialSnapshotForVerifiedConsent("a","uid-A",100L)
   val auth=SyntheticIdentity(VerifiedSyncLease("uid-A",1L))
   val remote=FakeRemote()
   val coordinator=SyncDeliveryCoordinator(db,auth,remote)
   val original=db.sync().nextPendingForAccount("uid-A")!!
   assertEquals(SyncDeliveryStep.ACKED,coordinator.deliverOne())
   repo.mutate("a"){it.copy(weightKg=80.0)}
   val revised=db.sync().nextPendingForAccount("uid-A")!!
   assertEquals(2,revised.revision)
   val firstRemote=remote.stored(revised)!!
   remote.overwrite(revised,firstRemote.copy(snapshot="conflicting edit"))
   assertEquals(SyncDeliveryStep.CONFLICT,coordinator.deliverOne())
   assertNull(db.sync().nextPendingForAccount("uid-A"))
   assertEquals(listOf(revised.copy(state="CONFLICT")),db.sync().conflictsForAccount("uid-A"))
   assertEquals(1,remote.writes)
   // Even if the remote later changes, never silently retry a conflict.
   remote.overwrite(revised,firstRemote)
   assertEquals(SyncDeliveryStep.EMPTY,coordinator.deliverOne())
   repo.mutate("a"){it.copy(weightKg=81.0)}
   assertEquals(3,db.sync().totalRows())
   assertNull(db.sync().nextPendingForAccount("uid-A"))
   assertTrue(db.sync().pendingForAccount("uid-A").isEmpty())
   assertEquals(1,db.sync().conflictsForAccount("uid-A").size)
   assertEquals(1,remote.writes)
   assertEquals(original.opId,firstRemote.opId)
  }finally{db.close()}
 }


 @Test fun conflictSurvivesRestartAndRemainsPrivateToItsVerifiedUid():Unit=runBlocking {
  val ctx=RuntimeEnvironment.getApplication()
  val name="forma-durable-conflict-test.db"
  ctx.deleteDatabase(name)
  var db=Room.databaseBuilder(ctx,FormaDatabase::class.java,name)
   .addMigrations(MIGRATION_1_2).build()
  try {
   var repo=ProfileRepository(db)
   repo.saveProfile(Profile(id="a",name="Alice"))
   repo.saveProfile(Profile(id="b",name="Bob"))
   repo.bindAndQueueInitialSnapshotForVerifiedConsent("a","uid-A",100L)
   repo.bindAndQueueInitialSnapshotForVerifiedConsent("b","uid-B",200L)
   val auth=SyntheticIdentity(VerifiedSyncLease("uid-A",1L))
   val remote=FakeRemote()
   val a=db.sync().nextPendingForAccount("uid-A")!!
   remote.overwrite(a,SyncRemoteEcho("uid-A","a","profile","a",1,
    "different-device-op","remote-content",false))
   assertEquals(SyncDeliveryStep.CONFLICT,SyncDeliveryCoordinator(db,auth,remote).deliverOne())
   val parked=db.sync().conflictsForAccount("uid-A").single()
   assertEquals(a.copy(state="CONFLICT"),parked)
   assertTrue(db.sync().conflictsForAccount("uid-B").isEmpty())
   assertEquals(0,remote.writes)
   db.close()
   db=Room.databaseBuilder(ctx,FormaDatabase::class.java,name)
    .addMigrations(MIGRATION_1_2).build()
   repo=ProfileRepository(db)
   assertEquals(listOf(parked),db.sync().conflictsForAccount("uid-A"))
   assertNull(db.sync().nextPendingForAccount("uid-A"))
   assertTrue(db.sync().conflictsForAccount("uid-B").isEmpty())
   assertEquals("Alice",repo.load("a")!!.name)
   auth.active=VerifiedSyncLease("uid-B",2L)
   assertEquals(SyncDeliveryStep.ACKED,SyncDeliveryCoordinator(db,auth,remote).deliverOne())
   assertEquals(1,remote.writes)
   assertEquals(listOf(parked),db.sync().conflictsForAccount("uid-A"))
   // Local edit after divergence remains in Room but cannot jump the
   // conflicting earlier document revision.
   repo.mutate("a"){it.copy(weightKg=88.0)}
   assertEquals(88.0,repo.load("a")!!.weightKg,0.0)
   assertTrue(db.sync().pendingForAccount("uid-A").isEmpty())
   assertEquals(3,db.sync().totalRows())
  }finally{db.close();ctx.deleteDatabase(name)}
 }

 @Test fun failedRemoteCompareAndSetLeavesQueuePending():Unit=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  try {
   val repo=ProfileRepository(db)
   repo.saveProfile(Profile(id="a",name="Alice"))
   repo.bindAndQueueInitialSnapshotForVerifiedConsent("a","uid-A",100L)
   val auth=SyntheticIdentity(VerifiedSyncLease("uid-A",1L))
   val remote=FakeRemote().apply{rejectWrite=true}
   val coordinator=SyncDeliveryCoordinator(db,auth,remote)
   assertEquals(SyncDeliveryStep.RETRY,coordinator.deliverOne())
   assertEquals(0,remote.writes)
   assertEquals(1,db.sync().pendingForAccount("uid-A").size)
   remote.rejectWrite=false
   assertEquals(SyncDeliveryStep.ACKED,coordinator.deliverOne())
   assertEquals(1,remote.writes)
  }finally{db.close()}
 }
}
