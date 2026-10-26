package ru.forma.app
import androidx.room.Room
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.*
import ru.forma.domain.*
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class StorageTest {
 @Test fun isolatedProfilesSurviveDatabaseReopen():Unit=runBlocking {
  val ctx=RuntimeEnvironment.getApplication();val name="forma-isolation-test.db";ctx.deleteDatabase(name)
  var db=Room.databaseBuilder(ctx,FormaDatabase::class.java,name).build();var repo=ProfileRepository(db)
  repo.saveProfile(Profile(id="a",name="A"));repo.saveProfile(Profile(id="b",name="B"))
  repo.mutate("a"){it.copy(weightKg=85.0)};assertEquals(70.0,repo.load("b")!!.weightKg,0.0)
  db.close();db=Room.databaseBuilder(ctx,FormaDatabase::class.java,name).build();repo=ProfileRepository(db)
  assertEquals(85.0,repo.load("a")!!.weightKg,0.0);db.close();ctx.deleteDatabase(name)
 }
 @Test fun savingSameSessionTwiceProducesOneHistoryItem()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build();val repo=ProfileRepository(db)
  val p=Profile(id="a",name="A");repo.saveProfile(p);val s=startSession(p.id,generateProgram(p).first(),0).copy(phase=Phase.DONE)
  repo.mutate("a"){finishSession(it,s,1000)};repo.mutate("a"){finishSession(it,s,2000)}
  assertEquals(1,repo.load("a")!!.history.size);db.close()
 }
 @Test fun twoConcurrentStartsCannotReplaceSession()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  val repo=ProfileRepository(db);val p=Profile(id="a",name="A");repo.saveProfile(p)
  val outcomes=(1..2).map{async(Dispatchers.IO){runCatching{repo.start("a",generateProgram(p).first(),0)}}}.awaitAll()
  assertEquals(1,outcomes.count{it.isSuccess})
  assertEquals(outcomes.first{it.isSuccess}.getOrThrow().id,repo.load("a")!!.active!!.id)
  db.close()
 }

 @Test fun importCreatesIsolatedCopiesAndSurvivesReopen():Unit=runBlocking {
  val ctx=RuntimeEnvironment.getApplication();val name="forma-import-test.db";ctx.deleteDatabase(name)
  var db=Room.databaseBuilder(ctx,FormaDatabase::class.java,name).build();var repo=ProfileRepository(db)
  repo.saveProfile(Profile(id="a",name="A"))
  val backup=repo.exportBackup();val imported=repo.importBackup(backup)
  assertEquals(1,imported.size);assertNotEquals("a",imported.single())
  repo.mutate(imported.single()){it.copy(weightKg=90.0)};assertEquals(70.0,repo.load("a")!!.weightKg,0.0)
  db.close();db=Room.databaseBuilder(ctx,FormaDatabase::class.java,name).build();repo=ProfileRepository(db)
  assertEquals(90.0,repo.load(imported.single())!!.weightKg,0.0);db.close();ctx.deleteDatabase(name)
 }
 @Test fun invalidImportDoesNotPartiallyWrite()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build();val repo=ProfileRepository(db)
  repo.saveProfile(Profile(id="a",name="A"))
  assertTrue(runCatching{repo.importBackup("{\"version\":99,\"profiles\":[]}")}.isFailure)
  assertEquals(1,db.profiles().all().size);db.close()
 }
 @Test fun staleSessionCommandCannotChangeNewWorkout()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build();val repo=ProfileRepository(db);val p=Profile(id="a",name="A");repo.saveProfile(p)
  val old=repo.start("a",generateProgram(p).first(),0)
  repo.mutateSession("a",old.id){stopSession(it)};repo.mutate("a"){finishSession(it,it.active!!,1000)}
  val next=repo.start("a",generateProgram(p).first(),2000)
  repo.mutateSession("a",old.id){stopSession(it)}
  assertEquals(next,repo.load("a")!!.active);db.close()
 }
 @Test fun rapidResumeThenStopUsesSessionIdentityNotStalePauseStage()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  val repo=ProfileRepository(db);val p=Profile(id="a",name="A");repo.saveProfile(p)
  val s=repo.start(p.id,generateProgram(p).first(),0)
  repo.mutateSession(p.id,s.id){pauseSession(it,1000)}
  val visible=repo.load(p.id)!!.active!!
  assertTrue(visible.paused)
  repo.mutateSession(p.id,s.id,sessionStage(visible)){resumeSession(it,2000)}
  // Simulate Stop being tapped before the Compose snapshot has reflected
  // Resume. Guarding Stop by sessionStage(visible) would silently lose it.
  repo.mutateSession(p.id,s.id){stopSession(it,false,2100)}
  val stopped=repo.load(p.id)!!.active!!
  assertEquals(Phase.STOPPED,stopped.phase)
  assertEquals(s.id,stopped.id)
  db.close()
 }
 @Test fun stopBeforeAsyncRecoveryKeepsOfflineGapOutOfPersistedHistory()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  try {
   val repo=ProfileRepository(db);val p=Profile(id="cold-start",name="User")
   repo.saveProfile(p)
   val original=repo.start(p.id,generateProgram(p).first(),1000L)
   // The ViewModel recovery action has not yet run; STOP must recover
   // the old session inside the same transaction that persists STOPPED.
   repo.mutateSession(p.id,original.id){
    val now=110_000L
    stopSession(recoverIfPreviousProcess(it,100_000L,now),false,now)
   }
   val stopped=repo.load(p.id)!!.active!!
   assertEquals(Phase.STOPPED,stopped.phase)
   assertEquals(original.activeSeconds,stopped.activeSeconds)
   repo.mutate(p.id){finishSession(it,it.active!!,110_001L)}
   val saved=repo.load(p.id)!!
   assertEquals(1,saved.history.size)
   assertEquals(0,saved.history.single().activeSeconds)
  } finally { db.close() }
 }
 @Test fun queuedOldFocusLossCannotRepauseResumedRoomSession()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  try {
   val repo=ProfileRepository(db);val p=Profile(id="focus",name="User")
   repo.saveProfile(p)
   val started=repo.start(p.id,generateProgram(p).first(),1000L)
   repo.mutateSession(p.id,started.id){pauseSession(it,1200L)}
   val commands=AudioFocusEpoch();val gate=AudioFocusLeaseGate(commands)
   val queuedLoss=gate.open()
   // Resume was tapped before the queued focus callback acquired Room.
   commands.invalidate()
   repo.mutateSession(p.id,started.id){resumeSession(it,2000L)}
   repo.mutateSession(p.id,started.id){
    if(gate.isCurrent(queuedLoss))pauseSession(it,2100L) else it
   }
   val active=repo.load(p.id)!!.active!!
   assertFalse(active.paused)
   assertEquals(2000L,active.lastTickMs)
   // A genuine loss from the new request must still pause durably.
   val freshLoss=gate.open()
   repo.mutateSession(p.id,started.id){
    if(gate.isCurrent(freshLoss))pauseSession(it,2400L) else it
   }
   assertTrue(repo.load(p.id)!!.active!!.paused)
  }finally{db.close()}
 }
 @Test fun refusedForegroundStartPausesWithoutCreditingElapsedTimeAndSurvivesReopen():Unit=runBlocking {
  val ctx=RuntimeEnvironment.getApplication();val name="forma-service-start-failure-test.db"
  ctx.deleteDatabase(name)
  var db=Room.databaseBuilder(ctx,FormaDatabase::class.java,name).build()
  val repo=ProfileRepository(db)
  val p=Profile(id="start-failed",name="User");repo.saveProfile(p)
  val s=repo.start(p.id,generateProgram(p).first(),1_000L)
  val failed=runCatching{
   requestWorkoutServiceOrPause(repo,p.id,s.id,{101_000L},{true}){
    throw IllegalStateException("Synthetic Android foreground rejection")
   }
  }.exceptionOrNull()
  assertTrue(failed is IllegalStateException)
  assertTrue(failed!!.message!!.contains("поставлена на паузу"))
  val paused=repo.load(p.id)!!.active!!
  assertTrue(paused.paused)
  assertEquals(0,paused.activeSeconds)
  assertEquals(300_000L,paused.pausedRemainingMs)
  assertEquals(101_000L,paused.lastTickMs)
  db.close()
  db=Room.databaseBuilder(ctx,FormaDatabase::class.java,name).build()
  assertEquals(paused,ProfileRepository(db).load(p.id)!!.active)
  db.close();ctx.deleteDatabase(name)
 }
 @Test fun staleServiceFailureCannotPauseOwnerResumedSessionOrAnotherProfile():Unit=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  try{
   val repo=ProfileRepository(db)
   val p=Profile(id="profile-one",name="User")
   val other=Profile(id="profile-two",name="Other")
   repo.saveProfile(p);repo.saveProfile(other)
   val s=repo.start(p.id,generateProgram(p).first(),1_000L)
   val commands=AudioFocusEpoch()
   val epoch=commands.snapshot()
   repo.mutateSession(p.id,s.id){pauseSession(it,1_200L)}
   commands.invalidate()
   repo.mutateSession(p.id,s.id){resumeSession(it,2_000L)}
   val before=repo.load(p.id)!!.active!!
   repo.pauseWhenServiceUnavailable(p.id,s.id,100_000L){commands.isCurrent(epoch)}
   assertEquals(before,repo.load(p.id)!!.active)
   repo.pauseWhenServiceUnavailable(other.id,s.id,100_000L)
   assertEquals(other,repo.load(other.id))
  }finally{db.close()}
 }
 @Test fun serviceOnlyTeardownPausesPersistedRoomWithoutCreditForAbsence():Unit=runBlocking {
  val ctx=RuntimeEnvironment.getApplication()
  val name="forma-service-interrupted.db";ctx.deleteDatabase(name)
  var db=Room.databaseBuilder(ctx,FormaDatabase::class.java,name).build()
  val repo=ProfileRepository(db)
  val owner=Profile(id="owner",name="Owner")
  repo.saveProfile(owner)
  val s=repo.start(owner.id,generateProgram(owner).first(),1000L)
  // Android destroyed just the service; processStartedMs remains unchanged.
  val tracker=ServiceInterruptionTracker()
  tracker.record(owner.id,s.id)
  mutateWithServiceInterruption(repo,tracker,owner.id,s.id,now={100_000L}){it}
  val saved=repo.load(owner.id)!!.active!!
  assertTrue(saved.paused)
  assertEquals(0,saved.activeSeconds)
  assertEquals(300_000L,saved.pausedRemainingMs)
  assertEquals(100_000L,saved.lastTickMs)
  assertNull(tracker.pending(owner.id,s.id))
  db.close()
  db=Room.databaseBuilder(ctx,FormaDatabase::class.java,name).build()
  val persisted=ProfileRepository(db).load(owner.id)!!.active!!
  assertEquals(saved,persisted)
  db.close();ctx.deleteDatabase(name)
 }
 @Test fun immediateOwnerResumeConsumesPendingInterruptionBeforeOldWrite()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  try {
   val repo=ProfileRepository(db);val owner=Profile(id="owner",name="Owner")
   repo.saveProfile(owner)
   val s=repo.start(owner.id,generateProgram(owner).first(),1000L)
   val tracker=ServiceInterruptionTracker()
   tracker.record(owner.id,s.id)
   // UI Resume races with application-scope onDestroy persistence.
   mutateWithServiceInterruption(repo,tracker,owner.id,s.id,now={100_000L}){
    resumeSession(it,100_000L)
   }
   // Delayed onDestroy callback must not re-pause the owner's live workout.
   mutateWithServiceInterruption(repo,tracker,owner.id,s.id,now={120_000L}){it}
   val resumed=repo.load(owner.id)!!.active!!
   assertFalse(resumed.paused)
   assertEquals(0,resumed.activeSeconds)
   assertEquals(100_000L,resumed.lastTickMs)
   assertEquals(400_000L,resumed.deadlineMs)
  }finally{db.close()}
 }
 @Test fun staleInterruptionDoesNotTouchNewSessionOrOtherProfile()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  try {
   val repo=ProfileRepository(db)
   val owner=Profile(id="owner",name="Owner")
   val other=Profile(id="other",name="Other")
   repo.saveProfile(owner);repo.saveProfile(other)
   val old=repo.start(owner.id,generateProgram(owner).first(),1000L)
   repo.mutateSession(owner.id,old.id){stopSession(it,false,1100L)}
   repo.mutate(owner.id){finishSession(it,it.active!!,1200L)}
   val next=repo.start(owner.id,generateProgram(owner).first(),2000L)
   val tracker=ServiceInterruptionTracker()
   tracker.record(owner.id,old.id)
   mutateWithServiceInterruption(repo,tracker,owner.id,next.id,now={100_000L}){it}
   mutateWithServiceInterruption(repo,tracker,other.id,old.id,now={100_000L}){it}
   assertEquals(next,repo.load(owner.id)!!.active)
   assertEquals(other,repo.load(other.id))
   assertEquals(1,repo.load(owner.id)!!.history.size)
   // An explicit stop cannot be undone by any late teardown.
   repo.mutateSession(owner.id,next.id){stopSession(it,false,2500L)}
   val stopped=repo.load(owner.id)!!.active!!
   tracker.record(owner.id,next.id)
   mutateWithServiceInterruption(repo,tracker,owner.id,next.id,now={100_000L}){it}
   assertEquals(stopped,repo.load(owner.id)!!.active)
  }finally{db.close()}
 }
 @Test fun pausePersistsAcrossTimedCardioTicksAndDatabaseReopen():Unit=runBlocking {
  val ctx=RuntimeEnvironment.getApplication();val name="forma-cardio-pause-test.db";ctx.deleteDatabase(name)
  var db=Room.databaseBuilder(ctx,FormaDatabase::class.java,name).build()
  val repo=ProfileRepository(db);val p=Profile(id="a",name="A");repo.saveProfile(p)
  val day=TrainingDay(day=2,name="Кардио",cardio=CardioKind.WALK,durationSec=60)
  val start=repo.start(p.id,day,1000)
  repo.mutateSession(p.id,start.id){skipStage(it,2000)}
  assertEquals(Phase.CARDIO,repo.load(p.id)!!.active!!.phase)
  repo.mutateSession(p.id,start.id){pauseSession(it,2500)}
  val paused=repo.load(p.id)!!.active!!
  assertTrue(paused.paused)
  repo.mutateSession(p.id,start.id){advanceTimed(it,10000)}
  assertEquals(paused,repo.load(p.id)!!.active!!)
  // Reopen the actual on-disk Room DB; no in-memory StateFlow may satisfy this.
  db.close()
  db=Room.databaseBuilder(ctx,FormaDatabase::class.java,name).build()
  val restored=ProfileRepository(db).load(p.id)!!.active!!
  assertEquals(paused,restored)
  assertTrue(restored.paused)
  db.close();ctx.deleteDatabase(name)
 }
 @Test fun importCapacityExceededLeavesAllProfilesUnchanged()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  val repo=ProfileRepository(db)
  (1..MAX_BACKUP_PROFILES).forEach{index->repo.saveProfile(Profile(id="p$index",name="Профиль $index"))}
  val single=encodeBackup(Backup(profiles=listOf(Profile(id="incoming",name="Ещё один"))))
  assertTrue(runCatching{repo.importBackup(single)}.isFailure)
  assertEquals(MAX_BACKUP_PROFILES,db.profiles().all().size)
  assertNull(repo.load("incoming"))
  db.close()
 }

 @Test fun scopedBackupExcludesOtherLocalUsersAndRetainsMeasurements()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  try {
   val repo=ProfileRepository(db)
   val alice=Profile(id="alice",name="Alice",measurements=listOf(
    Measurement(id="a-measurement",dateMs=1000,values=mapOf("weight" to 77.0))
   ))
   val bob=Profile(id="bob",name="Bob",measurements=listOf(
    Measurement(id="b-measurement",dateMs=1000,values=mapOf("weight" to 91.0))
   ))
   repo.saveProfile(alice)
   repo.saveProfile(bob)
   val scoped=decodeBackup(repo.exportBackup("alice"))
   assertEquals(listOf("alice"),scoped.profiles.map{it.id})
   assertEquals(alice.measurements,scoped.profiles.single().measurements)
   assertEquals(2,decodeBackup(repo.exportBackup()).profiles.size)
   assertTrue(runCatching{repo.exportBackup("missing-profile")}.isFailure)
   assertEquals(2,db.profiles().all().size)
  } finally { db.close() }
 }

}
