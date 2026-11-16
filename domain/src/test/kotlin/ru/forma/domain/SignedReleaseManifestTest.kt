package ru.forma.domain

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

/** Ephemeral in-memory test key only; never a FORMA permanent release secret. */
class SignedReleaseManifestTest {
 companion object {
  private val keyPair by lazy {
   KeyPairGenerator.getInstance("RSA").apply{initialize(3072)}.generateKeyPair()
  }
 }
 private val encoder=Base64.getEncoder()
 private val pinnedPublic get()=encoder.encodeToString(keyPair.public.encoded)
 private fun signed(bytes:ByteArray):String {
  val s=Signature.getInstance("SHA256withRSA")
  s.initSign(keyPair.private)
  s.update(bytes)
  return encoder.encodeToString(s.sign())
 }

 @Test fun acceptsExactlyTheSignedPayloadBytes() {
  val content="""{"channel":"stable","versionCode":3}""".toByteArray()
  assertArrayEquals(content,verifySignedReleasePayload(
   encoder.encodeToString(content),signed(content),pinnedPublic))
 }

 @Test fun changedPayloadSignatureAndUnknownKeyAreRejected() {
  val content="""{"channel":"stable","versionCode":3}""".toByteArray()
  val validSignature=signed(content)
  val invalidSignature=signed(content).let { Base64.getDecoder().decode(it) }
   .also{it[40]=(it[40].toInt() xor 1).toByte()}
  assertThrows(IllegalArgumentException::class.java) {
   verifySignedReleasePayload(encoder.encodeToString(content+byteArrayOf(0)),
    validSignature,pinnedPublic)
  }
  assertThrows(IllegalArgumentException::class.java) {
   verifySignedReleasePayload(encoder.encodeToString(content),
    encoder.encodeToString(invalidSignature),pinnedPublic)
  }
  assertThrows(IllegalArgumentException::class.java) {
   verifySignedReleasePayload(encoder.encodeToString(content),validSignature,"")
  }
 }

 @Test fun oversizeManifestAndRsa2048PublicKeyAreRejected() {
  val content="""{"channel":"stable"}""".toByteArray()
  val legacy=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
  assertThrows(IllegalArgumentException::class.java) {
   verifySignedReleasePayload(encoder.encodeToString(content),
    signed(content),encoder.encodeToString(legacy.public.encoded))
  }
  assertThrows(IllegalArgumentException::class.java) {
   verifySignedReleasePayload(encoder.encodeToString(ByteArray(16385){65}),
    signed(content),pinnedPublic)
  }
 }
}
