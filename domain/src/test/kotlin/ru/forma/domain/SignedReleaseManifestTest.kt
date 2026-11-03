package ru.forma.domain

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SignedReleaseManifestTest {
    private val b64 = Base64.getEncoder()
    private fun pair() = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
    private fun sign(payload: ByteArray, key: java.security.KeyPair): String {
        val s = Signature.getInstance("SHA256withRSA")
        s.initSign(key.private)
        s.update(payload)
        return b64.encodeToString(s.sign())
    }

    @Test fun acceptsAuthenticPayloadWithoutRewritingItsBytes() {
        val key = pair()
        val payload = """{"applicationId":"ru.forma.fitness","versionCode":3}""".toByteArray()
        assertArrayEquals(payload, verifySignedReleasePayload(
            b64.encodeToString(payload), sign(payload,key), b64.encodeToString(key.public.encoded)
        ))
    }

    @Test fun rejectsTamperingDifferentKeyAbsentPinAndMalformedBase64() {
        val key = pair()
        val other = pair()
        val payload = """{"applicationId":"ru.forma.fitness","versionCode":3}""".toByteArray()
        val enc = b64.encodeToString(payload)
        val sig = sign(payload,key)
        val pub = b64.encodeToString(key.public.encoded)
        assertThrows(IllegalArgumentException::class.java) {
            verifySignedReleasePayload(b64.encodeToString(payload + byteArrayOf(32)),sig,pub)
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifySignedReleasePayload(enc,sig,b64.encodeToString(other.public.encoded))
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifySignedReleasePayload(enc,sig,"")
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifySignedReleasePayload("invalid!",sig,pub)
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifySignedReleasePayload(enc,b64.encodeToString(ByteArray(384)),pub)
        }
    }
}
