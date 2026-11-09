package ru.forma.app

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.forma.domain.Measurement
import ru.forma.domain.Profile

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SyncStatusReaderTest {
 private class ScriptedIdentity(private var responses:List<VerifiedSyncLease?>):
  VerifiedSyncIdentity {
  private var index=0
  override suspend fun currentLease():VerifiedSyncLease? {
   val result=responses[index.coerceAtMost(responses.lastIndex)]
   index++
   return result
  }
 }
 private fun identity(vararg leases:VerifiedSyncLease?)=ScriptedIdentity(leases.toList())
 private suspend fun transition(
  db:FormaDatabase,row:SyncOutboxRow,toConflict:Boolean
 ):Int=if(toConflict)db.sync().parkExactConflict(
  row.accountUid,row.opId,row.profileId,row.kind,row.recordId,
  row.revision,row.snapshot,row.tombstone
 ) else db.sync().acknowledgeExactRemoteEcho(
  row.accountUid,row.opId,row.profileId,row.kind,row.recordId,
  row.revision,row.snapshot,row.tombstone
 )

 @Test fun statusCountsNeverExposeOtherUidOrPayloadAndRecheckAuthEpoch():Unit=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
   FormaDatabase::class.java).build()
  try {
   val repo=ProfileRepository(db)
   repo.saveProfile(Profile(id="offline-a",name="Alice private",
    measurements=listOf(Measurement(id="m-a",dateMs=100L,
     values=mapOf("weight" to 75.0)))))
   repo.saveProfile(Profile(id="offline-b",name="Bob private"))
   repo.bindAndQueueInitialSnapshotForVerifiedConsent("offline-a","uid-A",100L)
   repo.bindAndQueueInitialSnapshotForVerifiedConsent("offline-b","uid-B",200L)
   val first=db.sync().pendingForAccount("uid-A").first()
   val second=db.sync().pendingForAccount("uid-A").last()
   assertEquals("profile",first.kind)
   assertEquals("measurement",second.kind)
   assertEquals(1,transition(db,first,false))
   assertEquals(1,transition(db,second,true))
   repo.mutate("offline-a"){it.copy(weightKg=76.0)}
   assertEquals(4,db.sync().totalRows())
   val a=VerifiedSyncLease("uid-A",1L)
   val b=VerifiedSyncLease("uid-B",2L)
   val full=SyncStatusReader(db,identity(a,a)).read()
   assertEquals(LocalSyncSummary(LocalSyncState.READY,1,1,1),full)
   assertTrue(full.accessibleMessage().contains("ручного разбора — 1"))
   assertFalse(full.accessibleMessage().contains("Alice"))
   assertFalse(full.accessibleMessage().contains("uid-A"))
   assertFalse(full.accessibleMessage().contains("m-a"))
   assertFalse(full.accessibleMessage().contains("75.0"))
   assertEquals(LocalSyncSummary(LocalSyncState.READY,1,0,0),
    SyncStatusReader(db,identity(b,b)).read())
   assertEquals(LocalSyncSummary(LocalSyncState.NO_AUTH),
    SyncStatusReader(db,identity(null)).read())
   assertEquals(LocalSyncSummary(LocalSyncState.NO_AUTH),
    SyncStatusReader(db,identity(VerifiedSyncLease("",3L))).read())
   assertEquals(LocalSyncSummary(LocalSyncState.SESSION_CHANGED),
    SyncStatusReader(db,identity(a,b)).read())
   assertEquals(LocalSyncSummary(LocalSyncState.SESSION_CHANGED),
    SyncStatusReader(db,identity(a,VerifiedSyncLease("uid-A",2L))).read())
   assertEquals(4,db.sync().totalRows())
   assertEquals(1,db.sync().conflictsForAccount("uid-A").size)
   assertTrue(db.sync().conflictsForAccount("uid-B").isEmpty())
  } finally { db.close() }
 }

 @Test fun parkedConflictStatusSurvivesDatabaseReopenWithoutDestructiveResolution():Unit=runBlocking {
  val ctx=RuntimeEnvironment.getApplication()
  val name="forma-sync-status-restart-test.db"
  ctx.deleteDatabase(name)
  var db=Room.databaseBuilder(ctx,FormaDatabase::class.java,name)
   .addMigrations(MIGRATION_1_2).build()
  try {
   val repo=ProfileRepository(db)
   repo.saveProfile(Profile(id="persisted",name="Keep history"))
   repo.bindAndQueueInitialSnapshotForVerifiedConsent("persisted","uid-p",200L)
   val pending=db.sync().nextPendingForAccount("uid-p")!!
   assertEquals(1,transition(db,pending,true))
   val lease=VerifiedSyncLease("uid-p",4L)
   val before=SyncStatusReader(db,identity(lease,lease)).read()
   assertEquals(LocalSyncSummary(LocalSyncState.READY,0,1,0),before)
   db.close()
   db=Room.databaseBuilder(ctx,FormaDatabase::class.java,name)
    .addMigrations(MIGRATION_1_2).build()
   val after=SyncStatusReader(db,identity(lease,lease)).read()
   assertEquals(before,after)
   assertEquals(pending.copy(state="CONFLICT"),db.sync().conflictsForAccount("uid-p").single())
   assertEquals("Keep history",ProfileRepository(db).load("persisted")!!.name)
   assertNull(db.sync().nextPendingForAccount("uid-p"))
  } finally { db.close();ctx.deleteDatabase(name) }
 }

 @Test fun unknownStoredStatusIsNotPresentedAsFullySynchronized():Unit=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
   FormaDatabase::class.java).build()
  try {
   db.sync().enqueue(SyncOutboxRow(
    "fake-op","uid-A","profile","profile","profile",1,
    """{"notExported":true}""","UNKNOWN",false,1L
   ))
   val lease=VerifiedSyncLease("uid-A",1L)
   val result=runCatching{SyncStatusReader(db,identity(lease,lease)).read()}
   assertTrue(result.exceptionOrNull() is IllegalArgumentException)
   assertEquals(1,db.sync().totalRows())
   // Even for a corrupt synthetic state, no account B data or fallback
   // success is emitted. The local row is not deleted or rewritten.
   assertEquals(LocalSyncSummary(LocalSyncState.READY),
    SyncStatusReader(db,identity(VerifiedSyncLease("uid-B",2L),
     VerifiedSyncLease("uid-B",2L))).read())
  }finally{db.close()}
 }
}
