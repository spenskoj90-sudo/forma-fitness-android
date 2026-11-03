package ru.forma.domain

import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Authenticates exact signed JSON payload bytes before fields are parsed or trusted. */
fun verifySignedReleasePayload(payloadB64: String, signatureB64: String, publicKeySpkiB64: String): ByteArray {
    require(publicKeySpkiB64.isNotBlank()) { "Доверенный ключ публикации не настроен" }
    val decoder = Base64.getDecoder()
    val payload = decoder.decode(payloadB64)
    val signature = decoder.decode(signatureB64)
    val encodedKey = decoder.decode(publicKeySpkiB64)
    require(payload.isNotEmpty() && payload.size <= 16_384) { "Недопустимый размер описания обновления" }
    require(signature.size in 256..512 && encodedKey.size in 256..1024) { "Некорректный формат подписи" }
    val publicKey = KeyFactory.getInstance("RSA")
        .generatePublic(X509EncodedKeySpec(encodedKey)) as RSAPublicKey
    require(publicKey.modulus.bitLength() >= 3072) { "Слишком короткий ключ публикации" }
    val check = Signature.getInstance("SHA256withRSA")
    check.initVerify(publicKey)
    check.update(payload)
    require(check.verify(signature)) { "Подпись описания обновления недействительна" }
    return payload
}
