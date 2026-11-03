package ru.forma.domain

/**
 * Security gate shared by manual update checking and APK verification.
 * SHA-256 values here are fingerprints, never signing keys.
 * Compatible signing lineage/rotation requires an explicit, separately tested
 * implementation; until then, fail closed on any certificate difference.
 */
fun validateUpdateCandidate(
    installedApplicationId: String,
    installedVersionCode: Long,
    installedCertificateSha256: String,
    offeredApplicationId: String,
    offeredVersionCode: Long,
    offeredApkSha256: String,
    offeredCertificateSha256: String
): Boolean {
    val hex64 = Regex("[0-9a-f]{64}")
    require(offeredApplicationId == installedApplicationId) {
        "Пакет обновления не совпадает с установленной FORMA"
    }
    require(offeredVersionCode >= 1L) { "Некорректный код версии приложения" }
    require(offeredApkSha256.matches(hex64) && offeredCertificateSha256.matches(hex64)) {
        "Некорректный SHA-256 файла или подписи"
    }
    require(installedCertificateSha256.matches(hex64)) {
        "Невозможно подтвердить подпись установленной FORMA"
    }
    require(offeredCertificateSha256 == installedCertificateSha256) {
        "Подпись новой версии отличается от установленной FORMA"
    }
    return offeredVersionCode > installedVersionCode
}
