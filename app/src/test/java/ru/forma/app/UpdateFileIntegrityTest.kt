package ru.forma.app

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Synthetic untrusted file payloads. No real APK, signing key or release. */
class UpdateFileIntegrityTest {
 private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256")
  .digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}

 @Test fun validContentAddressedFilePassesExactSizeAndHash() {
  val bytes=ByteArray(8197){(it*17).toByte()}
  val digest=sha(bytes)
  val dir=java.nio.file.Files.createTempDirectory("forma-update-test").toFile()
  val file=File(dir,expectedUpdateApkName(3L,digest))
  try {
   file.writeBytes(bytes)
   verifyUpdateCachePath(dir,file,3L,digest)
   verifyUpdateApkBytes(file,bytes.size.toLong(),digest)
  }finally{file.delete();dir.delete()}
 }

 @Test fun changedTruncatedOversizedOrWrongPathNeverPasses() {
  val bytes=ByteArray(501){(it*23).toByte()}
  val digest=sha(bytes)
  val dir=java.nio.file.Files.createTempDirectory("forma-update-failure").toFile()
  val file=File(dir,expectedUpdateApkName(9L,digest))
  try {
   for (corrupted in listOf(
    bytes.copyOf(bytes.size-1),
    bytes.clone().also{it[30]=(it[30].toInt() xor 1).toByte()},
    bytes+byteArrayOf(13)
   )) {
    file.writeBytes(corrupted)
    assertThrows(IllegalArgumentException::class.java) {
     verifyUpdateApkBytes(file,bytes.size.toLong(),digest)
    }
   }
   assertThrows(IllegalArgumentException::class.java) {
    verifyUpdateCachePath(dir,File(dir,"forma-update.apk"),9L,digest)
   }
   assertThrows(IllegalArgumentException::class.java) {
    verifyUpdateCachePath(dir,file,10L,digest)
   }
  }finally{file.delete();dir.delete()}
 }

 @Test fun zeroSizeOrMalformedHashCannotBecomeInstallerFilename() {
  val file=File.createTempFile("forma-invalid-apk-", ".tmp")
  try {
   assertThrows(IllegalArgumentException::class.java) {
    verifyUpdateApkBytes(file,0L,"a".repeat(64))
   }
   assertThrows(IllegalArgumentException::class.java) {
    expectedUpdateApkName(3L,"../other")
   }
   assertThrows(IllegalArgumentException::class.java) {
    expectedUpdateApkName(-1L,"a".repeat(64))
   }
  }finally{file.delete()}
 }
}
