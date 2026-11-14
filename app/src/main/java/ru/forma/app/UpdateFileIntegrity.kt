package ru.forma.app

import java.io.File
import java.security.MessageDigest

/** Bound exact-on-disk APK verification before exposing the file to Android. */
internal fun verifyUpdateApkBytes(file: File, expectedSize: Long, expectedSha256: String) {
 require(expectedSize in 1L..(200L * 1024 * 1024)) { "Недопустимый размер обновления" }
 require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "Некорректный SHA-256 обновления" }
 require(file.isFile && file.length() == expectedSize) { "Размер подготовленного APK изменился" }
 val actual = MessageDigest.getInstance("SHA-256")
 var size = 0L
 file.inputStream().use { input ->
  val buffer = ByteArray(32768)
  while (true) {
   val n = input.read(buffer)
   if (n < 0) break
   require(n > 0) { "Не удалось прочитать подготовленный APK" }
   size += n
   require(size <= expectedSize) { "Подготовленный APK содержит лишние байты" }
   actual.update(buffer, 0, n)
  }
 }
 val observed = actual.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
 require(size == expectedSize && observed == expectedSha256) {
  "Контрольная сумма подготовленного APK изменилась"
 }
}

/** The signed hash and version are the only variable parts of the path. */
internal fun expectedUpdateApkName(version: Long, sha256: String): String {
 require(version > 0 && sha256.matches(Regex("[0-9a-f]{64}")))
 return "forma-v${version}-${sha256}.apk"
}

internal fun verifyUpdateCachePath(dir: File, file: File, version: Long, sha256: String) {
 val expected = File(dir, expectedUpdateApkName(version, sha256))
 require(file.canonicalFile == expected.canonicalFile &&
  file.canonicalFile.parentFile == dir.canonicalFile) {
  "Неверный путь подготовленного APK"
 }
}
