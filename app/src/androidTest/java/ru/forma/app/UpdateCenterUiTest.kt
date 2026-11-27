package ru.forma.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic gateway: never contacts a feed, opens Android installer or signs an APK. */
@RunWith(AndroidJUnit4::class)
class UpdateCenterUiTest {
 @get:Rule val rule=createComposeRule()

 private class FakeGateway:FormaUpdateGateway {
  var checkResult=CompletableDeferred<FormaRelease?>()
  var downloadResult=CompletableDeferred<File>()
  var installationRequests=0
  var allowSyntheticHandoff=false
  override fun installedVersion()="Test only (code 1)"
  override suspend fun check():FormaRelease?=checkResult.await()
  override suspend fun downloadAndVerify(release:FormaRelease):File=downloadResult.await()
  override suspend fun requestSystemInstallation(apk:File,release:FormaRelease) {
   installationRequests++
   require(allowSyntheticHandoff) { "Synthetic fixture must not launch an installer" }
  }
 }

 @Test fun signedOfferLoadingFailureRetrySyntheticHandoffAndRecheckAreAccessible() {
  val gateway=FakeGateway()
  val next=FormaRelease(
   applicationId="ru.forma.fitness",versionCode=3L,versionName="Test 3",
   apkUrl="https://updates.example.test/forma-v3.apk",
   sha256="a".repeat(64),certificateSha256="b".repeat(64),
   releaseNotes="Synthetic release notes",sizeBytes=1048576L
  )
  rule.setContent {
   MaterialTheme {
    Column(Modifier.verticalScroll(rememberScrollState())) { UpdateCenter(gateway) }
   }
  }
  rule.onNodeWithText("Проверить обновления").performScrollTo().assertIsDisplayed().performClick()
  rule.onNodeWithText("Проверяем…").assertExists()
  rule.onNodeWithText("Проверяем официальный HTTPS-канал",substring=true).assertExists()
  rule.onNodeWithText("Скачать и установить через Android").assertDoesNotExist()

  rule.runOnIdle {gateway.checkResult.complete(next)}
  rule.waitUntil(timeoutMillis=10000) {
   rule.onAllNodesWithText("Скачать и установить через Android").fetchSemanticsNodes().isNotEmpty()
  }
  rule.onNodeWithText("Что нового: Synthetic release notes").assertExists()
  rule.onNodeWithText("Скачать и установить через Android")
   .performScrollTo().assertIsDisplayed().performClick()
  rule.onNodeWithText("Загружаем APK…").performScrollTo().assertExists()
  rule.onNodeWithText("Загружаем и проверяем APK.",substring=true).assertExists()
  rule.onNodeWithText("Проверить обновления").assertIsNotEnabled()

  rule.runOnIdle {gateway.downloadResult.completeExceptionally(IOException("Synthetic network outage"))}
  rule.waitUntil(timeoutMillis=10000) {
   rule.onAllNodesWithText("Не удалось загрузить APK.",substring=true)
    .fetchSemanticsNodes().isNotEmpty()
  }
  rule.onNodeWithText("Скачать и установить через Android")
   .performScrollTo().assertIsEnabled()
  rule.onNodeWithText("Проверить обновления").assertIsEnabled()
  assertEquals(0,gateway.installationRequests)

  // The retry succeeds, but the fixture never reads an APK or starts Android.
  rule.runOnIdle {
   gateway.downloadResult=CompletableDeferred()
   gateway.allowSyntheticHandoff=true
  }
  rule.onNodeWithText("Скачать и установить через Android")
   .performScrollTo().assertIsDisplayed().performClick()
  rule.onNodeWithText("Загружаем APK…").assertExists()
  rule.runOnIdle {gateway.downloadResult.complete(File("synthetic-never-opened.apk"))}
  rule.waitUntil(timeoutMillis=10000) {
   rule.onAllNodesWithText("Подтвердите установку в Android.",substring=true)
    .fetchSemanticsNodes().isNotEmpty()
  }
  assertEquals("Only one synthetic system handoff is permitted",1,gateway.installationRequests)
  rule.onNodeWithText("Проверить обновления").assertIsEnabled()

  // A fresh check must immediately hide the old release, then report no update.
  rule.runOnIdle {gateway.checkResult=CompletableDeferred()}
  rule.onNodeWithText("Проверить обновления").performScrollTo().assertIsDisplayed().performClick()
  rule.onNodeWithText("Скачать и установить через Android").assertDoesNotExist()
  rule.runOnIdle {gateway.checkResult.complete(null)}
  rule.waitUntil(timeoutMillis=10000) {
   rule.onAllNodesWithText("У вас актуальная версия.").fetchSemanticsNodes().isNotEmpty()
  }
  rule.onNodeWithText("Скачать и установить через Android").assertDoesNotExist()
  assertEquals(1,gateway.installationRequests)
 }
}
