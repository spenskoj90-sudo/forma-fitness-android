package ru.forma.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.exerciseCatalog

/** Synthetic offline 3-D surface smoke test; no qualification or video claim. */
@RunWith(AndroidJUnit4::class)
class OriginalVolumetricPreviewTest {
 @get:Rule val rule=createComposeRule()
 @Test fun threeDimensionalSurfaceOnlyOpensOnRequestAndManualControlsRemainAccessible() {
  rule.setContent { MaterialTheme {
   Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){
    OriginalVolumetricPreview(exerciseCatalog.first())
   }
  }}
  rule.onNodeWithTag("volume-preview-surface").assertDoesNotExist()
  rule.onNodeWithTag("volume-preview-toggle").assertExists().performScrollTo().performClick()
  rule.onNodeWithTag("volume-preview-surface").assertExists()
  rule.onNodeWithText("Независимая проверка техники ещё не завершена.",substring=true).assertExists()
  rule.onNodeWithTag("volume-preview-orbit").assertExists()
  rule.onNodeWithTag("volume-preview-scrub").assertExists()
  rule.onNodeWithTag("volume-preview-play").performScrollTo().performClick()
  rule.onNodeWithText("Пауза 3D").assertExists()
  rule.onNodeWithTag("volume-preview-play").performScrollTo().performClick()
  rule.onNodeWithText("Воспроизвести 3D").assertExists()
  rule.onNodeWithText("Без движения").performScrollTo().performClick()
  rule.onNodeWithTag("volume-preview-play").assertIsNotEnabled()
  rule.onNodeWithTag("volume-preview-toggle").performScrollTo().performClick()
  rule.onNodeWithTag("volume-preview-surface").assertDoesNotExist()
 }
}
