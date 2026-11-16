package ru.forma.domain

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable, password-based authenticated local backup. No server, keystore,
 * device identifier or permanent signer is involved. This is NOT cloud sync.
 *
 * Format v1: 8-byte "FORMABK2" magic, 1-byte version, 16-byte random salt,
 * 12-byte random GCM nonce, AES-256-GCM ciphertext and 16-byte tag.
 * The entire header is GCM AAD; KDF changes require a new format version.
 * PBKDF2-HMAC-SHA256 with 310,000 iterations.
 *
 * The owner must keep the password independently. Callers own their CharArray
 * and must clear it once the operation completes.
 */
private val encryptedBackupMagic = "FORMABK2".toByteArray(Charsets.US_ASCII)
private const val ENCRYPTED_BACKUP_VERSION:Byte = 1
private const val BACKUP_SALT_BYTES = 16
private const val BACKUP_NONCE_BYTES = 12
private const val BACKUP_TAG_BITS = 128
private const val BACKUP_KDF_ITERATIONS = 310_000
private const val BACKUP_HEADER_BYTES = 8 + 1 + BACKUP_SALT_BYTES + BACKUP_NONCE_BYTES
private const val BACKUP_TAG_BYTES = BACKUP_TAG_BITS / 8

private fun backupKey(password:CharArray,salt:ByteArray):ByteArray {
 val spec=PBEKeySpec(password,salt,BACKUP_KDF_ITERATIONS,256)
 return try{SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded}
 finally{spec.clearPassword()}
}

/** Input JSON must already be a valid FORMA backup; fails before encryption. */
fun encryptPortableBackup(json:String,password:CharArray):ByteArray {
 require(password.size in 12..1024){"Пароль копии должен содержать от 12 до 1024 символов"}
 decodeBackup(json)
 val plaintext=json.toByteArray(Charsets.UTF_8)
 val salt=ByteArray(BACKUP_SALT_BYTES).also{SecureRandom().nextBytes(it)}
 val nonce=ByteArray(BACKUP_NONCE_BYTES).also{SecureRandom().nextBytes(it)}
 val header=ByteArray(BACKUP_HEADER_BYTES)
 encryptedBackupMagic.copyInto(header,0)
 header[encryptedBackupMagic.size]=ENCRYPTED_BACKUP_VERSION
 salt.copyInto(header,9)
 nonce.copyInto(header,9+BACKUP_SALT_BYTES)
 val key=backupKey(password,salt)
 return try {
  val cipher=Cipher.getInstance("AES/GCM/NoPadding")
  cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(BACKUP_TAG_BITS,nonce))
  cipher.updateAAD(header)
  val ciphertext=cipher.doFinal(plaintext)
  header+ciphertext
 }finally{
  key.fill(0)
  plaintext.fill(0)
 }
}

/**
 * Authenticates before decoding any profile. Wrong password or modified
 * ciphertext/header cannot be imported; legacy plaintext JSON is rejected.
 * The repository import remains additive and transactionally validated.
 */
fun decryptPortableBackup(file:ByteArray,password:CharArray):String {
 require(file.size in (BACKUP_HEADER_BYTES+BACKUP_TAG_BYTES)..(MAX_BACKUP_BYTES+BACKUP_HEADER_BYTES+BACKUP_TAG_BYTES)){
  "Некорректный размер защищённой копии"
 }
 require(file.copyOfRange(0,encryptedBackupMagic.size).contentEquals(encryptedBackupMagic) &&
  file[encryptedBackupMagic.size]==ENCRYPTED_BACKUP_VERSION){
  "Неподдерживаемый формат защищённой копии"
 }
 require(password.isNotEmpty()){"Введите пароль защищённой копии"}
 val header=file.copyOfRange(0,BACKUP_HEADER_BYTES)
 val salt=file.copyOfRange(9,9+BACKUP_SALT_BYTES)
 val nonce=file.copyOfRange(9+BACKUP_SALT_BYTES,BACKUP_HEADER_BYTES)
 val key=backupKey(password,salt)
 return try {
  val cipher=Cipher.getInstance("AES/GCM/NoPadding")
  cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(BACKUP_TAG_BITS,nonce))
  cipher.updateAAD(header)
  val decoded=cipher.doFinal(file,BACKUP_HEADER_BYTES,file.size-BACKUP_HEADER_BYTES)
  try{
   val text=decoded.toString(Charsets.UTF_8)
   decodeBackup(text)
   text
  }finally{decoded.fill(0)}
 }catch(e:GeneralSecurityException){
  throw IllegalArgumentException("Неверный пароль или повреждённая защищённая копия",e)
 }finally{key.fill(0)}
}
