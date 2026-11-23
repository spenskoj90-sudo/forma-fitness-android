package ru.forma.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** The Infinix-generated GLB is original procedural art, NOT exercise instruction. */
internal object FormaConceptGlbContract {
    const val ASSET_PATH = "forma/concepts/20261123T103931_b06a115a.glb"
    const val EXPECTED_SHA256 = "e7d622a770bd77026f30dfd8baa0cf0d6880de3204baf6c158be61823c27b6f5"
    const val REVIEW_STATUS = "CONCEPT_ONLY"
    const val EXPECTED_SIZE = 125288

    fun valid(bytes: ByteArray): Boolean = validWithSha(bytes, EXPECTED_SHA256)

    internal fun validWithSha(bytes: ByteArray, sha256: String): Boolean {
        if (bytes.size != EXPECTED_SIZE || sha256.length != 64) return false
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.int != 0x46546c67 || buf.int != 2 || buf.int != bytes.size) return false
        val jsonLength = buf.int
        if (jsonLength <= 0 || jsonLength % 4 != 0 || jsonLength > bytes.size - 28) return false
        if (buf.int != 0x4e4f534a) return false
        val binaryHeader = 20L + jsonLength
        if (binaryHeader + 8 > bytes.size) return false
        buf.position(binaryHeader.toInt())
        val binaryLength = buf.int
        if (binaryLength < 0 || binaryLength % 4 != 0 || buf.int != 0x004e4942) return false
        if (binaryHeader + 8 + binaryLength != bytes.size.toLong()) return false
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return actual == sha256
    }
}
