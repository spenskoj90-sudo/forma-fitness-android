package ru.forma.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Non-Play/sideload release channel; the system installer retains final control. */
private const val MAX_UPDATE_APK_BYTES = 200L * 1024 * 1024
private const val MAX_UPDATE_MANIFEST_BYTES = 16 * 1024

data class FormaRelease(
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val certificateSha256: String
)

private fun validHttps(url: String): Uri {
    val uri = Uri.parse(url)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.fragment == null) { "Разрешён только прямой HTTPS-адрес обновлений" }
    return uri
}

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun readSmallUtf8(connection: HttpsURLConnection): String {
    connection.inputStream.use { input ->
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(bytes.size() + n <= MAX_UPDATE_MANIFEST_BYTES) { "Слишком большой файл описания обновления" }
            bytes.write(buffer, 0, n)
        }
        return bytes.toString("UTF-8")
    }
}

private fun androidPackageInfo(context: Context, file: String? = null): PackageInfo {
    val pm = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28)
        PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    return (if (file == null) pm.getPackageInfo(context.packageName, flags)
        else pm.getPackageArchiveInfo(file, flags))
        ?: error("Android не смог проверить информацию об APK")
}

private fun certificateFingerprint(info: PackageInfo): String {
    @Suppress("DEPRECATION")
    val signers = if (Build.VERSION.SDK_INT >= 28)
        info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
    else info.signatures?.map { it.toByteArray() }.orEmpty()
    require(signers.size == 1) { "Не удалось однозначно определить подпись APK" }
    return digest(signers.single())
}

@Suppress("DEPRECATION")
private fun versionCode(info: PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

class FormaUpdateClient(private val context: Context) {
    fun installedVersion(): String = runCatching {
        androidPackageInfo(context).versionName.orEmpty() +
            " (код " + versionCode(androidPackageInfo(context)) + ")"
    }.getOrDefault("не определена")

    suspend fun check(): FormaRelease? = withContext(Dispatchers.IO) {
        val feedUrl = context.getString(R.string.forma_update_feed_url).trim()
        require(feedUrl.isNotEmpty()) {
            "Канал обновлений ещё не настроен. Установленная FORMA и её данные остаются без изменений."
        }
        val feed = validHttps(feedUrl)
        val connection = (URL(feedUrl).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            require(connection.responseCode == 200) { "Сервер обновлений временно недоступен" }
            val obj = JSONObject(readSmallUtf8(connection))
            val next = FormaRelease(
                applicationId = obj.getString("applicationId"),
                versionCode = obj.getLong("versionCode"),
                versionName = obj.getString("versionName").take(80),
                apkUrl = obj.getString("apkUrl"),
                sha256 = obj.getString("sha256").lowercase(),
                certificateSha256 = obj.getString("certificateSha256").lowercase()
            )
            val apk = validHttps(next.apkUrl)
            require(apk.host.equals(feed.host, ignoreCase = true)) {
                "APK должен размещаться на том же защищённом сервере"
            }
            require(next.applicationId == context.packageName) { "Пакет обновления не совпадает с установленным" }
            require(next.sha256.matches(Regex("[0-9a-f]{64}")) &&
                next.certificateSha256.matches(Regex("[0-9a-f]{64}"))) {
                "Некорректные контрольные суммы обновления"
            }
            require(next.certificateSha256 == certificateFingerprint(androidPackageInfo(context))) {
                "Подпись нового выпуска не совпадает с подписью установленной FORMA"
            }
            next.takeIf { it.versionCode > versionCode(androidPackageInfo(context)) }
        } finally { connection.disconnect() }
    }

    suspend fun downloadAndVerify(release: FormaRelease): File = withContext(Dispatchers.IO) {
        val base = validHttps(context.getString(R.string.forma_update_feed_url).trim())
        val source = validHttps(release.apkUrl)
        require(source.host.equals(base.host, ignoreCase = true))
        require(release.applicationId == context.packageName)
        require(release.versionCode > versionCode(androidPackageInfo(context)))
        require(release.certificateSha256 == certificateFingerprint(androidPackageInfo(context)))
        val dir = File(context.cacheDir, "verified-updates").apply { mkdirs() }
        val temp = File(dir, "forma-update.apk")
        temp.delete()
        val connection = (URL(release.apkUrl).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = false
        }
        try {
            require(connection.responseCode == 200) { "Не удалось скачать обновление" }
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_UPDATE_APK_BYTES) { "APK превышает допустимый размер" }
                        md.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            }
            require(total > 0 && hex(md.digest()) == release.sha256) { "Контрольная сумма APK не совпадает" }
            val archive = androidPackageInfo(context, temp.absolutePath)
            require(archive.packageName == context.packageName &&
                versionCode(archive) == release.versionCode) { "Неверная версия или имя пакета APK" }
            require(certificateFingerprint(archive) == release.certificateSha256) { "APK подписан другим ключом" }
            temp
        } catch (e: Exception) {
            temp.delete()
            throw e
        } finally { connection.disconnect() }
    }

    fun requestSystemInstallation(apk: File) {
        // Never install silently. Android must display its own install prompt.
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            throw IllegalStateException("Разрешите установку из FORMA и повторите действие.")
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
fun UpdateCenter() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val client = remember(context) { FormaUpdateClient(context) }
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Проверка выполняется вручную. Тренировки и замеры не отправляются серверу обновлений.") }
    var release by remember { mutableStateOf<FormaRelease?>(null) }
    Panel("Обновление FORMA") {
        Text("Установлена: " + client.installedVersion())
        Text(message, style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            working = true
            scope.launch {
                try {
                    release = client.check()
                    message = if (release == null) "У вас актуальная версия." else
                        "Доступна версия " + release!!.versionName + ". Установка сохраняет данные только при совместимой подписи."
                } catch (e: Exception) { message = e.message ?: "Не удалось проверить обновления";release = null }
                finally { working = false }
            }
        }, enabled = !working, modifier = Modifier.fillMaxWidth()) {
            Text(if (working) "Проверяем…" else "Проверить обновления")
        }
        val available = release
        if (available != null) Button(
            onClick = {
                working = true
                scope.launch {
                    try {
                        val apk = client.downloadAndVerify(available)
                        message = "APK проверен. Android запросит подтверждение установки."
                        client.requestSystemInstallation(apk)
                    } catch (e: Exception) { message = e.message ?: "Не удалось подготовить установку" }
                    finally { working = false }
                }
            },
            enabled = !working,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Скачать и установить через Android") }
        Text("Для непрерывных обновлений нужен постоянный ключ подписи и официальный HTTPS-канал выпусков. Нынешние разовые тестовые APK таким каналом не являются.", style = MaterialTheme.typography.bodySmall)
    }
}
