package ru.forma.app

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.*

/** Real Android/SQLite v1->v2 migration, not a new-v2 in-memory DB test.
 * All records are synthetic; no production database is opened or erased.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationInstrumentedTest {
 @Test fun populatedV1PreservesHistoryMeasurementsPausedSessionAndNoImplicitSync():Unit=runBlocking {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val name="forma-room-v1-to-v2-instrumentation.db"
  ctx.deleteDatabase(name) // only this uniquely named synthetic test database
  try {
   val first=Profile(id="legacy-first",name="Legacy",
    measurements=listOf(Measurement(id="legacy-m",dateMs=1000L,
     values=mapOf("weight" to 82.0))))
   val completed=finishSession(first,stopSession(
    startSession(first.id,generateProgram(first).first(),1200L),false,1800L),2000L)
   val second=Profile(id="legacy-paused",name="Paused")
   val paused=second.copy(active=pauseSession(
    startSession(second.id,generateProgram(second).first(),2500L),3000L))
   val snapshots=listOf(completed,paused)
   val encoded=snapshots.associate{it.id to Json.encodeToString(Profile.serializer(),it)}
   val v1=ctx.openOrCreateDatabase(name,Context.MODE_PRIVATE,null)
   try {
    v1.execSQL("CREATE TABLE profiles (id TEXT NOT NULL, name TEXT NOT NULL, " +
     "document TEXT NOT NULL, PRIMARY KEY(id))")
    v1.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
    v1.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES " +
     "(42, '4863845aa8163bf8ac2c52e4fc5f0304')")
    snapshots.forEach { p ->
     v1.execSQL("INSERT INTO profiles(id,name,document) VALUES(?,?,?)",
      arrayOf(p.id,p.name,encoded.getValue(p.id)))
    }
    v1.version=1
   }finally {v1.close()}
   fun openV2()=Room.databaseBuilder(ctx,FormaDatabase::class.java,name)
    .addMigrations(MIGRATION_1_2).build()
   var db=openV2()
   try {
    val repo=ProfileRepository(db)
    assertEquals(snapshots.size,db.profiles().all().size)
    snapshots.forEach{p ->
     assertEquals(encoded.getValue(p.id),db.profiles().get(p.id)!!.document)
     assertEquals(p,repo.load(p.id))
    }
    assertTrue(repo.load(second.id)!!.active!!.paused)
    assertEquals(1,repo.load(first.id)!!.history.size)
    assertEquals(first.measurements,repo.load(first.id)!!.measurements)
    assertEquals(0,db.sync().totalRows())
    assertNull(db.sync().binding(first.id))
    assertNull(db.sync().binding(second.id))
   }finally {db.close()}
   db=openV2()
   try {
    val repo=ProfileRepository(db)
    assertEquals(completed,repo.load(first.id))
    assertEquals(paused,repo.load(second.id))
    assertEquals(0,db.sync().totalRows())
    // Updating the opted-out legacy profile must remain Room-local after restart.
    repo.mutate(first.id){it.copy(weightKg=83.0)}
    assertEquals(83.0,repo.load(first.id)!!.weightKg,0.0)
    assertEquals(first.measurements,repo.load(first.id)!!.measurements)
    assertEquals(completed.history,repo.load(first.id)!!.history)
    assertEquals(paused,repo.load(second.id))
    assertEquals(0,db.sync().totalRows())
   }finally {db.close()}
  }finally {ctx.deleteDatabase(name)}
 }
}
