package ru.forma.app

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
   file.writeBytes(encryptPortableBackup(original,"strong passphrase 123".toCharArray()))
   val reopened=file.readBytes()
   assertFalse(reopened.toString(Charsets.ISO_8859_1).contains("synthetic-profile"))
   val decoded=decryptPortableBackup(reopened,"strong passphrase 123".toCharArray())
   assertEquals(original,decoded)
   assertEquals("synthetic-profile",decodeBackup(decoded).profiles.single().id)
   assertTrue(runCatching{decryptPortableBackup(reopened,"incorrect passphrase".toCharArray())}.isFailure)
  }finally{file.delete()}
 }
}
