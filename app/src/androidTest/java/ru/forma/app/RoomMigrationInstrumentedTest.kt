package ru.forma.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.*

/** Real Android/SQLite migrations on populated on-disk databases.
 * All records are synthetic; no production database is opened or erased.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationInstrumentedTest {
 @Test fun populatedV1PreservesHistoryMeasurementsPausedSessionAndNoImplicitSync():Unit=runBlocking {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val name="forma-room-v1-to-v3-instrumentation.db"
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
   fun openCurrent()=Room.databaseBuilder(ctx,FormaDatabase::class.java,name)
    .addMigrations(MIGRATION_1_2,MIGRATION_2_3).build()
   var db=openCurrent()
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
   db=openCurrent()
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

 @Test fun populatedV2PreservesConsentReceiptAndPendingOperation():Unit=runBlocking {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val name="forma-room-v2-to-v3-instrumentation.db"
  val profile=Profile(id="migration-v2",name="Synthetic v2",measurements=listOf(
   Measurement("v2-measure",1000L,mapOf("weight" to 80.0))))
  val completed=finishSession(profile,stopSession(
   startSession(profile.id,generateProgram(profile).first(),1200L),false,1800L),2000L)
  val document=Json.encodeToString(Profile.serializer(),completed)
  val uid="migration-v2-account"
  val pending=SyncOutboxRow("measure-op",uid,profile.id,"measurement","v2-measure",1,"fixture-measurement","PENDING",false,2L)
  val receipt=SyncOutboxRow("profile-op",uid,profile.id,"profile",profile.id,1,"fixture-profile","ACKED",false,1L)
  ctx.deleteDatabase(name)
  try {
   val configuration=SupportSQLiteOpenHelper.Configuration.builder(ctx).name(name)
    .callback(object:SupportSQLiteOpenHelper.Callback(2) {
     override fun onCreate(db:SupportSQLiteDatabase) {
      db.execSQL("CREATE TABLE profiles (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, document TEXT NOT NULL)")
      MIGRATION_1_2.migrate(db)
      db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
      db.execSQL("INSERT INTO room_master_table VALUES(42,'14f917dfbacbe370fb873e4cdc8b7aa1')")
      db.execSQL("INSERT INTO profiles VALUES(?,?,?)",arrayOf(profile.id,profile.name,document))
      db.execSQL("INSERT INTO sync_bindings VALUES(?,?,?)",arrayOf(profile.id,uid,1L))
      for (row in listOf(receipt,pending)) db.execSQL(
       "INSERT INTO sync_outbox(opId,accountUid,profileId,kind,recordId,revision,snapshot,state,tombstone,createdAtMs) VALUES(?,?,?,?,?,?,?,?,?,?)",
       arrayOf(row.opId,row.accountUid,row.profileId,row.kind,row.recordId,row.revision,row.snapshot,row.state,0,row.createdAtMs))
     }
     override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=error("Unexpected fixture upgrade")
    }).build()
   FrameworkSQLiteOpenHelperFactory().create(configuration).use { assertEquals(2,it.writableDatabase.version) }
   fun open()=Room.databaseBuilder(ctx,FormaDatabase::class.java,name).addMigrations(MIGRATION_1_2,MIGRATION_2_3).build()
   val cursor=SyncPullRow(uid,profile.id,"measurement",5L,1,"v2-measure")
   var db=open()
   try {
    assertEquals(document,db.profiles().get(profile.id)?.document)
    assertEquals(completed,ProfileRepository(db).load(profile.id))
    assertEquals(SyncBindingRow(profile.id,uid,1L),db.sync().binding(profile.id))
    assertEquals(receipt,db.sync().latestReceipt(uid,profile.id,"profile",profile.id))
    assertEquals(pending,db.sync().nextPendingForAccount(uid))
    assertTrue(db.syncReview().reviews(uid,1).isEmpty())
    assertNull(db.syncReview().cursor(uid,profile.id,"measurement"))
    db.syncReview().saveCursor(cursor)
   } finally {db.close()}
   db=open()
   try {
    assertEquals(cursor,db.syncReview().cursor(uid,profile.id,"measurement"))
    assertEquals(pending,db.sync().nextPendingForAccount(uid))
   } finally {db.close()}
  } finally {ctx.deleteDatabase(name)}
 }

}
