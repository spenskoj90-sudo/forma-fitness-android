package ru.forma.app

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.*

private val testApp get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FormaApp
private fun snapshotFile(app: FormaApp) = File(app.filesDir, "test-installer-snapshot.json")
private fun confirmationFile(app: FormaApp) = File(app.filesDir, "test-installer-confirmation.txt")
private val installerPackages = setOf("com.android.packageinstaller", "com.google.android.packageinstaller")

@Serializable
private data class UpdateSnapshot(val selected: String, val profiles: List<Profile>)

private suspend fun snapshot(app: FormaApp) = UpdateSnapshot(
    app.settings.settings.first().selected,
    app.repository.profiles.first().sortedBy { it.id },
)

@Suppress("DEPRECATION")
private fun installedCode(app: FormaApp) = app.packageManager.getPackageInfo(app.packageName, 0).versionCode

private fun confirmationButton(root: AccessibilityNodeInfo?): Rect? {
    if (root == null || root.packageName?.toString() !in installerPackages) return null
    fun visibleText(text: String) = root.findAccessibilityNodeInfosByText(text).filter {
        it.isVisibleToUser && it.packageName?.toString() == root.packageName?.toString() && it.text?.toString() == text
    }
    if (visibleText("FORMA").isEmpty() || visibleText("Do you want to update this app?").isEmpty()
        || visibleText("Cancel").none { it.isEnabled && it.isClickable }) return null
    val update = visibleText("Update").singleOrNull { it.isEnabled && it.isClickable } ?: return null
    val bounds = Rect()
    update.getBoundsInScreen(bounds)
    return bounds.takeUnless { it.isEmpty }
}

/** Phase one leaves Android's confirmation on screen for the host test to accept. */
@RunWith(AndroidJUnit4::class)
class UpdateInstallerHandoffTest {
    @get:Rule val rule = createComposeRule()

    @Test fun realUpdateCenterOpensSystemConfirmation() {
        val app = testApp
        assertEquals(2, installedCode(app))
        assertTrue("Fixture must be allowed to request system installation", app.packageManager.canRequestPackageInstalls())
        val file = snapshotFile(app)
        assertFalse("Stale install snapshot", file.exists())
        assertFalse("Stale system confirmation", confirmationFile(app).exists())
        val sample = Profile(
            id = "system-update-fixture", name = "Тест установки", weightKg = 77.0,
            measurements = listOf(Measurement("system-update-weight", 1000, mapOf("weight" to 77.0))),
        )
        runBlocking {
            val session = stopSession(startSession(sample.id, generateProgram(sample).first(), 1000))
            app.repository.saveProfile(finishSession(sample, session, 1000))
            app.settings.select(sample.id)
            file.writeText(Json.encodeToString(UpdateSnapshot.serializer(), snapshot(app)))
        }
        val client = UpdateHttpsFixture(app).client("stable.json")
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { UpdateCenter(client) }
            }
        }
        rule.onNodeWithText("Проверить обновления").performScrollTo().performClick()
        val installLabel = "Скачать и установить через Android"
        rule.waitUntil(20_000) { rule.onAllNodesWithText(installLabel).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Что нового: Synthetic HTTPS update fixture").assertExists()
        rule.onNodeWithText(installLabel).performScrollTo().assertIsDisplayed().performTouchInput { click() }
        val device = InstrumentationRegistry.getInstrumentation().uiAutomation
        var bounds: Rect? = null
        rule.waitUntil(20_000) {
            bounds = confirmationButton(device.rootInActiveWindow)
            bounds != null
        }
        assertEquals("The system must wait for confirmation", 2, installedCode(app))
        val button = requireNotNull(bounds)
        confirmationFile(app).writeText("${button.centerX()} ${button.centerY()}")
    }
}

/** Phase two runs from the newly installed app process and reopens its real database. */
@RunWith(AndroidJUnit4::class)
class AfterSystemUpdateTest {
    @Test fun systemUpdateKeepsAllProfilesAndSelection() = runBlocking {
        val app = testApp
        assertEquals(3, installedCode(app))
        val file = snapshotFile(app)
        val before = Json.decodeFromString(UpdateSnapshot.serializer(), file.readText())
        val fixture = before.profiles.single { it.id == "system-update-fixture" }
        assertEquals(fixture.id, before.selected)
        assertEquals(1, fixture.history.size)
        assertEquals(1, fixture.measurements.size)
        assertEquals(before, snapshot(app))
        assertTrue(file.delete())
        assertTrue(confirmationFile(app).delete())
    }
}
