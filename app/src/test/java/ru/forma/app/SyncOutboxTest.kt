package ru.forma.app

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.forma.domain.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SyncOutboxTest {
 @Test fun guestAndBackupImportNeverAutoBindOrQueue():Unit=runBlocking{
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  try{
   val repo=ProfileRepository(db)
   repo.saveProfile(Profile(id="guest",name="Guest"))
   repo.mutate("guest"){it.copy(weightKg=80.0)}
   val imported=repo.importBackup(repo.exportBackup())
   assertEquals(1,imported.size)
   assertNull(db.sync().binding("guest"))
   assertNull(db.sync().binding(imported.single()))
   assertTrue(db.sync().all().isEmpty())
   assertEquals(80.0,repo.load("guest")!!.weightKg,0.0)
  }finally{db.close()}
 }
 @Test fun syntheticConsentQueuesProfileAndFinishedWorkoutButNotActiveSession():Unit=runBlocking{
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  try{
   val repo=ProfileRepository(db)
   repo.saveProfile(Profile(id="a",name="A"))
   // Fixture only. No real Auth/guest-consent pathway is active yet.
   db.sync().bindForVerifiedConsent(SyncBindingRow("a","synthetic-uid-A",100))
   repo.mutate("a"){it.copy(weightKg=80.0)}
   val first=db.sync().pending().single()
   assertEquals("profile",first.kind)
   assertEquals("a",first.profileId)
   assertEquals("synthetic-uid-A",first.accountUid)
   assertEquals(1,first.revision)
   assertTrue(first.snapshot.contains("\"weightKg\":80.0"))
   assertFalse(first.snapshot.contains("\"history\""))
   assertFalse(first.snapshot.contains("\"measurements\""))
   assertFalse(first.snapshot.contains("\"active\""))
   repo.mutate("a"){it}
   assertEquals(1,db.sync().pending().size)
   val p=repo.load("a")!!
   val session=repo.start("a",generateProgram(p).first(),1000)
   repo.mutateSession("a",session.id){pauseSession(it,1100)}
   repo.mutateSession("a",session.id){stopSession(it,false,1200)}
   assertEquals(1,db.sync().pending().size)
   repo.mutate("a"){finishSession(it,it.active!!,1300)}
   val rows=db.sync().pending()
   assertEquals(2,rows.size)
   val workout=rows.single{it.kind=="workout"}
   assertEquals(session.id,workout.recordId)
   assertEquals(1,workout.revision)
   assertTrue(workout.snapshot.contains("\"status\":\"STOPPED\""))
   assertNull(repo.load("a")!!.active)
   assertEquals(1,repo.load("a")!!.history.size)
   repo.mutate("a"){it.copy(weightKg=81.0)}
   val revisions=db.sync().pending().filter{it.kind=="profile"}.sortedBy{it.revision}
   assertEquals(listOf(1,2),revisions.map{it.revision})
   assertEquals(2,revisions.map{it.opId}.distinct().size)
  }finally{db.close()}
 }
 @Test fun oneLocalProfileNeverQueuesIntoAnotherScope():Unit=runBlocking{
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  try{
   val repo=ProfileRepository(db)
   repo.saveProfile(Profile(id="a",name="A"));repo.saveProfile(Profile(id="b",name="B"))
   db.sync().bindForVerifiedConsent(SyncBindingRow("a","uid-A",100))
   repo.mutate("b"){it.copy(weightKg=92.0)}
   assertTrue(db.sync().pending().isEmpty())
   repo.mutate("a"){it.copy(weightKg=76.0)}
   assertEquals(listOf("uid-A"),db.sync().pending().map{it.accountUid})
   assertEquals(listOf("a"),db.sync().pending().map{it.profileId})
   assertEquals(92.0,repo.load("b")!!.weightKg,0.0)
  }finally{db.close()}
 }
 @Test fun outboxConstraintFailureRollsBackTheSameRoomProfileTransaction():Unit=runBlocking{
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  try{
   val repo=ProfileRepository(db)
   repo.saveProfile(Profile(id="a",name="A"))
   db.sync().bindForVerifiedConsent(SyncBindingRow("a","uid-A",100))
   val m=Measurement(id="duplicate-id",dateMs=1000L,values=mapOf("waist" to 80.0))
   val err=runCatching{repo.mutate("a"){it.copy(measurements=listOf(m,m))}}
   assertTrue(err.isFailure)
   assertTrue(repo.load("a")!!.measurements.isEmpty())
   assertTrue(db.sync().all().isEmpty())
  }finally{db.close()}
 }
 @Test fun editedMeasurementKeepsUuidAndBothOutboxRevisions():Unit=runBlocking{
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),FormaDatabase::class.java).build()
  try{
   val repo=ProfileRepository(db)
   repo.saveProfile(Profile(id="a",name="A"))
   db.sync().bindForVerifiedConsent(SyncBindingRow("a","uid-A",100))
   val m=Measurement(id="m-1",dateMs=1000L,values=mapOf("waist" to 80.0))
   repo.mutate("a"){it.copy(measurements=listOf(m))}
   repo.mutate("a"){it.copy(measurements=listOf(m.copy(values=mapOf("waist" to 81.0))))}
   val rows=db.sync().pending().filter{it.kind=="measurement"}.sortedBy{it.revision}
   assertEquals(listOf(1,2),rows.map{it.revision})
   assertEquals(listOf("m-1","m-1"),rows.map{it.recordId})
   assertTrue(rows.last().snapshot.contains("81.0"))
   assertEquals(1,repo.load("a")!!.measurements.size)
  }finally{db.close()}
 }
}
