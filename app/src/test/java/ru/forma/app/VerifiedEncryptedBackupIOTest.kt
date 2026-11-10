package ru.forma.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Synthetic ciphertext only; never read real user files or passwords. */
class VerifiedEncryptedBackupIOTest {
 private val sample=ByteArray(8197){(it*17).toByte()}

 @Test fun readbackStartsOnlyAfterWriteClosesAndPreservesExactBytes() {
  val stored=ByteArrayOutputStream()
  var closed=false
  writeVerifiedEncryptedBackup(sample,{
   object:FilterOutputStream(stored){
    override fun close(){super.close();closed=true}
   }
  },{
   assertTrue("Provider must be reopened after write/close",closed)
   ByteArrayInputStream(stored.toByteArray())
  })
  assertTrue(closed)
  assertArrayEquals(sample,stored.toByteArray())
 }

 @Test fun truncatedTamperedAndExtraCiphertextNeverReportSuccess() {
  val trimmed=sample.copyOf(sample.size-1)
  val tampered=sample.copyOf().also{it[60]=(it[60].toInt() xor 7).toByte()}
  val trailing=sample+byteArrayOf(0x42)
  for (corrupted in listOf(trimmed,tampered,trailing)) {
   val failure=runCatching {
    writeVerifiedEncryptedBackup(sample,{ByteArrayOutputStream()},
     {ByteArrayInputStream(corrupted)})
   }.exceptionOrNull()
   assertTrue(failure is IOException)
  }
 }

 @Test fun missingWriteOrReadPermissionsFailClosed() {
  assertTrue(runCatching {
   writeVerifiedEncryptedBackup(sample,{null},{ByteArrayInputStream(sample)})
  }.exceptionOrNull() is IOException)
  assertTrue(runCatching {
   writeVerifiedEncryptedBackup(sample,{ByteArrayOutputStream()},{null})
  }.exceptionOrNull() is IOException)
 }
}
