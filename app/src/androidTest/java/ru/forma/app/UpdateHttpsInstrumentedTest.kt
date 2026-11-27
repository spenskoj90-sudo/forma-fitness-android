package ru.forma.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.Proxy
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A loopback TLS feed with a disposable pin and an actual same-signer debug APK. */
internal class UpdateHttpsFixture(private val context: Context) {
    private val arguments = InstrumentationRegistry.getArguments()
    private val fixturePort by lazy {
        requireNotNull(arguments.getString("formaUpdateFixturePort")).toInt().also { require(it in 1024..65535) }
    }
    private val sockets by lazy {
        val der = Base64.getDecoder().decode(requireNotNull(arguments.getString("formaUpdateFixtureTls")))
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream())
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("forma-update-fixture", certificate)
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }.socketFactory
    }

    fun client(path: String, trustFixture: Boolean = true) = FormaUpdateClient(
        context, "https://localhost/$path", requireNotNull(arguments.getString("formaUpdateFixturePin"))
    ) { url ->
        // Only the isolated test transport maps the standard HTTPS port to adb reverse.
        check(url.protocol == "https" && url.host == "localhost" && url.port == -1)
        (URL("https", url.host, fixturePort, url.file).openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
            if (trustFixture) sslSocketFactory = sockets
        }
    }
}

@RunWith(AndroidJUnit4::class)
class UpdateHttpsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = UpdateHttpsFixture(context)

    private suspend fun rejects(message: String, call: suspend () -> Any?) {
        val failure = runCatching { call() }.exceptionOrNull()
        assertTrue("Expected update rejection '$message', got $failure", failure is IllegalArgumentException)
        assertEquals(message, failure?.message)
    }

    @Test fun checksSignedHttpsSourceAndRejectsInvalidOffers() {
        runBlocking {
            val untrusted = runCatching { fixture.client("stable.json", trustFixture = false).check() }.exceptionOrNull()
            assertTrue("Untrusted TLS certificate should fail: $untrusted", untrusted is SSLHandshakeException)
            assertNull(fixture.client("downgrade.json").check())
            rejects("Сервер обновлений временно недоступен") { fixture.client("redirect.json").check() }
            rejects("Источник обновлений изменён") { fixture.client("wrong-source.json").check() }
            rejects("Пакет обновления не совпадает с установленной FORMA") { fixture.client("wrong-package.json").check() }
            rejects("Подпись новой версии отличается от установленной FORMA") { fixture.client("wrong-cert.json").check() }
            rejects("Подпись описания обновления недействительна") { fixture.client("invalid-signature.json").check() }
        }
    }

    @Test fun downloadsAuthenticApkAndRejectsRedirectAlteredAndOversizedBytes() {
        runBlocking {
            val good = fixture.client("stable.json")
            val release = requireNotNull(good.check())
            assertEquals("ru.forma.fitness", release.applicationId)
            assertEquals(3L, release.versionCode)
            assertEquals("Synthetic HTTPS update fixture", release.releaseNotes)
            val directory = File(context.cacheDir, "verified-updates")
            val expected = File(directory, expectedUpdateApkName(release.versionCode, release.sha256))
            assertFalse("A cached fixture would hide bad transport responses", expected.exists())
            try {
                for ((path, error) in listOf(
                    "apk-redirect.json" to "Не удалось скачать обновление",
                    "apk-damaged.json" to "Контрольная сумма APK не совпадает",
                    "apk-oversize.json" to "Размер APK превышает указанный в подписанном описании",
                    "apk-wrong-version.json" to "Неверная версия или имя пакета APK"
                )) {
                    val client = fixture.client(path)
                    val offer = requireNotNull(client.check())
                    rejects(error) { client.downloadAndVerify(offer) }
                    assertFalse(File(directory, expectedUpdateApkName(offer.versionCode, offer.sha256)).exists())
                    assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith("forma-downloading-") })
                }
                val apk = good.downloadAndVerify(release)
                assertEquals(expected.canonicalFile, apk.canonicalFile)
                verifyUpdateApkBytes(apk, release.sizeBytes, release.sha256)
                assertEquals(apk, good.downloadAndVerify(release))
            } finally {
                expected.delete()
            }
        }
    }
}
