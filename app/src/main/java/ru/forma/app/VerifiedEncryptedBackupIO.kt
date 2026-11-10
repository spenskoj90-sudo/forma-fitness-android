package ru.forma.app

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * The SAF provider is external to FORMA: a successful write/close is not proof
 * that the selected document contains the complete encrypted backup.
 * Reopen it only after closing the writer and compare exact ciphertext bytes,
 * without decoding personal records or copying their contents to logs.
 * This is a local document readback, NOT independent backup/restore proof.
 */
internal fun writeVerifiedEncryptedBackup(
 encryptedBytes: ByteArray,
 openForWrite: () -> OutputStream?,
 openForRead: () -> InputStream?
) {
 require(encryptedBytes.isNotEmpty()) { "Нет зашифрованных данных для сохранения" }
 val expectedHash = MessageDigest.getInstance("SHA-256").digest(encryptedBytes)
 val output = openForWrite() ?: throw IOException("Хранилище не разрешило запись файла")
 output.use { it.write(encryptedBytes); it.flush() }

 val input = openForRead() ?: throw IOException("Хранилище не разрешило проверить записанный файл")
 val actualHash = MessageDigest.getInstance("SHA-256")
 var actualSize = 0L
 input.use { source ->
  val buffer = ByteArray(8192)
  while (true) {
   val n = source.read(buffer)
   if (n == -1) break
   if (n == 0) throw IOException("Хранилище не возвращает данные для проверки")
   actualSize += n
   if (actualSize > encryptedBytes.size.toLong()) {
    throw IOException("Записанная копия содержит лишние байты")
   }
   actualHash.update(buffer, 0, n)
  }
 }
 if (actualSize != encryptedBytes.size.toLong() ||
  !MessageDigest.isEqual(expectedHash, actualHash.digest())) {
  throw IOException("Записанная копия не совпадает с исходным зашифрованным файлом")
 }
}
