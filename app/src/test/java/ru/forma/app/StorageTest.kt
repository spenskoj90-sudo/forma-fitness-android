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
 @Test fun pausePersistsAcrossTimedCardioTicksAndRecovery()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  val repo=ProfileRepository(db);val p=Profile(id="a",name="A");repo.saveProfile(p)
  val day=generateProgram(p).first{it.cardio!=null}.copy(durationSec=60)
  val start=repo.start(p.id,day,1000)
  repo.mutateSession(p.id,start.id){skipStage(it,2000)}
  assertEquals(Phase.CARDIO,repo.load(p.id)!!.active!!.phase)
  repo.mutateSession(p.id,start.id){pauseSession(it,2500)}
  val paused=repo.load(p.id)!!.active!!
  assertTrue(paused.paused)
  repo.mutateSession(p.id,start.id){advanceTimed(it,10000)}
  val afterTick=repo.load(p.id)!!.active!!
  assertEquals(paused,afterTick)
  // Reopen the database to prove the pause survives a new repository
  // instance rather than merely an in-memory StateFlow state.
  val nextRepo=ProfileRepository(db)
  assertTrue(nextRepo.load(p.id)!!.active!!.paused)
  db.close()
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

}
