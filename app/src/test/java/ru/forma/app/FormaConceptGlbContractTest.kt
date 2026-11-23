package ru.forma.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** GLB is original concept-only artwork, never a validated exercise demonstration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FormaConceptGlbContractTest {
    private fun asset(): ByteArray {
        val rel = "build/generated/forma-concept/assets/" + FormaConceptGlbContract.ASSET_PATH
        val f = listOf(File(rel), File("app/" + rel)).firstOrNull { it.isFile }
        assertNotNull("Build must reproduce the exact GLB before unit tests", f)
        return f!!.readBytes()
    }

    @Test fun originalAssetIsIntactAndUnreviewed() {
        val bytes = asset()
        assertEquals(FormaConceptGlbContract.EXPECTED_SIZE, bytes.size)
        assertEquals("CONCEPT_ONLY", FormaConceptGlbContract.REVIEW_STATUS)
        assertTrue(FormaConceptGlbContract.valid(bytes))
    }

    @Test fun gltfDecoderAcceptsOnlyOriginalVerifiedGeometry() {
        val scene = FormaConceptDecoder.decode(asset())
        assertEquals(20, scene.meshes.size)
        assertEquals(3, scene.meshes.map { it.rgba.toList() }.distinct().size)
        assertEquals(5004, scene.meshes.sumOf { it.count / 3 })
        assertTrue(scene.meshes.all { it.count > 0 })
    }

    @Test fun tamperedAndTruncatedAssetsFailClosed() {
        val original = asset()
        val mutated = original.clone()
        mutated[mutated.lastIndex - 3] = (mutated[mutated.lastIndex - 3].toInt() xor 1).toByte()
        assertFalse(FormaConceptGlbContract.valid(mutated))
        assertFalse(FormaConceptGlbContract.valid(original.copyOf(original.size - 1)))
        assertFalse(FormaConceptGlbContract.validWithSha(original, "0".repeat(64)))
        assertFalse(FormaConceptGlbContract.valid(byteArrayOf(1, 2, 3)))
        try {
            FormaConceptDecoder.decode(mutated)
            fail("Tampered GLB must never reach the renderer")
        } catch (expected: IllegalArgumentException) {
            // expected fail-closed behavior
        }
    }
}
