package ru.forma.domain

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val oldCode = 2L
    private fun check(
        packageName: String = "ru.forma.fitness",
        version: Long = 3L,
        sha: String = b,
        signer: String = a
    ) = validateUpdateCandidate(
        installedApplicationId = "ru.forma.fitness",
        installedVersionCode = oldCode,
        installedCertificateSha256 = a,
        offeredApplicationId = packageName,
        offeredVersionCode = version,
        offeredApkSha256 = sha,
        offeredCertificateSha256 = signer
    )

    @Test fun validNewVersionWithSamePackageAndSignerIsOffered() {
        assertTrue(check())
    }

    @Test fun sameVersionAndDowngradeNeverOffered() {
        assertFalse(check(version = oldCode))
        assertFalse(check(version = 1L))
    }

    @Test fun differentAppIdAndSignerRejectedEvenIfVersionHigher() {
        assertThrows(IllegalArgumentException::class.java) { check(packageName = "ru.forma.fitness.acceptance") }
        assertThrows(IllegalArgumentException::class.java) { check(signer = b) }
    }

    @Test fun malformedHashesAndVersionRejected() {
        assertThrows(IllegalArgumentException::class.java) { check(sha = "bad") }
        assertThrows(IllegalArgumentException::class.java) { check(sha = "G".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { check(version = -1L) }
    }
}
