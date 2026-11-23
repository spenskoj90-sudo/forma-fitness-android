package ru.forma.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.Profile

/** Opt-in display and controls on emulator; pixel fidelity/physical devices still unverified. */
@RunWith(AndroidJUnit4::class)
class FormaConceptViewerTest {
    @get:Rule val rule = createComposeRule()

    @Test fun conceptIsOptInAndCanBeClosedWithoutChangingBodyDiagram() {
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BodyMannequin(Profile(name = "Синтетический профиль"))
                }
            }
        }
        rule.onNodeWithTag("forma3d-concept-surface").assertDoesNotExist()
        rule.onNodeWithTag("forma3d-toggle").performClick()
        rule.onNodeWithTag("forma3d-concept-surface").assertExists()
        rule.onNodeWithTag("forma3d-rotate-left").performClick()
        rule.onNodeWithTag("forma3d-rotate-right").performClick()
        rule.onNodeWithTag("forma3d-zoom-in").performClick()
        rule.onNodeWithTag("forma3d-zoom-out").performClick()
        rule.onNodeWithTag("forma3d-toggle").performClick()
        rule.onNodeWithTag("forma3d-concept-surface").assertDoesNotExist()
    }
}
