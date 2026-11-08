package ru.forma.domain

import org.junit.Assert.*
import org.junit.Test

class EncryptedBackupTest {
 private fun sample():String {
  val p=Profile(id="offline-a",name="Тестовый пользователь",
   measurements=listOf(Measurement(id="metric-a",dateMs=1000L,values=mapOf("weight" to 75.0))))
  val finished=stopSession(startSession(p.id,generateProgram(p).first(),1000L),false,1500L)
  return encodeBackup(Backup(profiles=listOf(finishSession(p,finished,1600L))))
 }

 @Test fun encryptedRoundTripPreservesFullHistoryAndMeasurements() {
  val original=sample()
  val bytes=encryptPortableBackup(original,"длинный пароль 12345".toCharArray())
  assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("offline-a"))
  val restored=decryptPortableBackup(bytes,"длинный пароль 12345".toCharArray())
  assertEquals(original,restored)
  val profile=decodeBackup(restored).profiles.single()
  assertEquals(1,profile.history.size)
  assertEquals(1,profile.measurements.size)
  assertNull(profile.active)
 }

 @Test fun distinctSaltAndNonceAndModifiedDataCannotBeOpened() {
  val json=sample()
  val password="long private passphrase"
  val first=encryptPortableBackup(json,password.toCharArray())
  val second=encryptPortableBackup(json,password.toCharArray())
  assertFalse(first.contentEquals(second))
  assertEquals(json,decryptPortableBackup(first,password.toCharArray()))
  assertTrue(runCatching{decryptPortableBackup(first,"a different password".toCharArray())}.isFailure)
  val tampered=first.copyOf()
  tampered[tampered.lastIndex]=(tampered.last().toInt() xor 1).toByte()
  assertTrue(runCatching{decryptPortableBackup(tampered,password.toCharArray())}.isFailure)
  val modifiedSalt=first.copyOf()
  modifiedSalt[10]=(modifiedSalt[10].toInt() xor 1).toByte()
  assertTrue(runCatching{decryptPortableBackup(modifiedSalt,password.toCharArray())}.isFailure)
 }

 @Test fun legacyJsonAndUnsupportedVersionAreNeverMisreadAsEncrypted() {
  val json=sample()
  val password="long private passphrase"
  assertTrue(runCatching{decryptPortableBackup(json.toByteArray(),password.toCharArray())}.isFailure)
  val encrypted=encryptPortableBackup(json,password.toCharArray())
  val invalidVersion=encrypted.copyOf()
  invalidVersion[8]=99
  assertTrue(runCatching{decryptPortableBackup(invalidVersion,password.toCharArray())}.isFailure)
  assertTrue(runCatching{decryptPortableBackup(encrypted.copyOf(20),password.toCharArray())}.isFailure)
  assertTrue(runCatching{encryptPortableBackup(json,"short".toCharArray())}.isFailure)
 }
}
