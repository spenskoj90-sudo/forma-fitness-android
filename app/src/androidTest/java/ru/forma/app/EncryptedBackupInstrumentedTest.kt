package ru.forma.app

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class EncryptedBackupInstrumentedTest {
 @Test fun encryptedOfflineCopyOpensAfterFileReopenOnAndroid() {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val file=File(ctx.cacheDir,"synthetic-encrypted-backup.forma")
  val original=encodeBackup(Backup(profiles=listOf(Profile(
   id="synthetic-profile",name="Тест",measurements=listOf(
    Measurement(id="synthetic-m",dateMs=1000L,values=mapOf("weight" to 73.0))
   )
  ))))
  try {
   val ciphertext=encryptPortableBackup(original,"strong passphrase 123".toCharArray())
   try{
    writeVerifiedEncryptedBackup(ciphertext,{file.outputStream()},{file.inputStream()})
   }finally{ciphertext.fill(0)}
   val reopened=file.readBytes()
   assertFalse(reopened.toString(Charsets.ISO_8859_1).contains("synthetic-profile"))
   val decoded=decryptPortableBackup(reopened,"strong passphrase 123".toCharArray())
   assertEquals(original,decoded)
   assertEquals("synthetic-profile",decodeBackup(decoded).profiles.single().id)
   assertTrue(runCatching{decryptPortableBackup(reopened,"incorrect passphrase".toCharArray())}.isFailure)
   // Ciphertext created by an independent Python provider with a UTF-8
   // Cyrillic password: catches Android PBKDF2 character encoding mismatch.
   val independent=java.util.Base64.getDecoder().decode("Rk9STUFCSzIBAAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGw2setFPWh7J/KG1PrGo9cBLxVx/EzNVoiSROUiyh1inYkQYOEprgj4Dke/mhv648FEi5t92YFWh3FfKHUP5flkzAARJBqIfVaq2TM1IlAKmlQ==")
   assertEquals("{\"version\":1,\"profiles\":[{\"id\":\"fixture\",\"name\":\"Cross-device\"}]}",
    decryptPortableBackup(independent,"между устройствами пароль".toCharArray()))
  }finally{file.delete()}
 }
 @Test fun encryptedRestoreAddsHistoryWithoutOverwritingExistingProfileAcrossDatabaseReopen():Unit=runBlocking {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val dbName="forma-synthetic-encrypted-restore.db"
  val file=File(ctx.cacheDir,"synthetic-restore-roundtrip.forma")
  ctx.deleteDatabase(dbName)
  var db=Room.databaseBuilder(ctx,FormaDatabase::class.java,dbName)
   .addMigrations(MIGRATION_1_2).build()
  try {
   var repo=ProfileRepository(db)
   val existing=Profile(id="already-stored",name="Unchanged")
   repo.saveProfile(existing)
   val withMeasurement=Profile(id="export-source",name="Offline athlete",
    measurements=listOf(Measurement(id="saved-waist",dateMs=1000L,
     values=mapOf("waist" to 81.0))))
   val started=startSession(withMeasurement.id,generateProgram(withMeasurement).first(),1000L)
   val finished=stopSession(started,false,1500L)
   val source=finishSession(withMeasurement,finished,1600L)
   assertEquals(1,source.history.size)
   assertNull(source.active)
   val original=encodeBackup(Backup(profiles=listOf(source)))
   val exportPassword="synthetic private passphrase".toCharArray()
   val ciphertext=try{encryptPortableBackup(original,exportPassword)}
    finally{exportPassword.fill('\u0000')}
   try{
    writeVerifiedEncryptedBackup(ciphertext,{file.outputStream()},{file.inputStream()})
   }finally{ciphertext.fill(0)}
   val fromDisk=file.readBytes()
   assertFalse(fromDisk.toString(Charsets.ISO_8859_1).contains("Offline athlete"))
   // Rejected authentication cannot insert any partial profile into Room.
   val corrupted=fromDisk.copyOf().also{
    it[it.lastIndex]=(it.last().toInt() xor 1).toByte()
   }
   fun openCopy(bytes:ByteArray):String {
    val password="synthetic private passphrase".toCharArray()
    return try{decryptPortableBackup(bytes,password)}
     finally{password.fill('\u0000')}
   }
   assertTrue(runCatching{repo.importBackup(openCopy(corrupted))}.isFailure)
   assertEquals(listOf(existing),db.profiles().all().map{repo.load(it.id)!!})
   val importedIds=repo.importBackup(openCopy(fromDisk))
   assertEquals(1,importedIds.size)
   val importedId=importedIds.single()
   assertNotEquals(source.id,importedId)
   val restored=repo.load(importedId)!!
   assertEquals(source,restored.copy(id=source.id))
   assertEquals(existing,repo.load(existing.id))
   assertEquals(0,db.sync().totalRows())
   assertNull(db.sync().binding(importedId))
   db.close()
   db=Room.databaseBuilder(ctx,FormaDatabase::class.java,dbName)
    .addMigrations(MIGRATION_1_2).build()
   repo=ProfileRepository(db)
   assertEquals(source,repo.load(importedId)!!.copy(id=source.id))
   assertEquals(existing,repo.load(existing.id))
   assertEquals(0,db.sync().totalRows())
   assertNull(db.sync().binding(importedId))
  }finally{
   db.close()
   file.delete()
   ctx.deleteDatabase(dbName)
  }
 }

}
