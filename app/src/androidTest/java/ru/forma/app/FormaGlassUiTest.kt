package ru.forma.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FormaGlassUiTest {
 @get:Rule val rule=createComposeRule()

 @Test fun changingEffectAndContrastModesKeepsHeadingAndBodyAccessible(){
  val visual=mutableStateOf(FormaVisualOptions())
  rule.setContent {
   CompositionLocalProvider(LocalFormaVisualOptions provides visual.value) {
    MaterialTheme {
     Column(Modifier.width(260.dp).verticalScroll(rememberScrollState())) {
      FormaGlassPanel("Техника упражнений") {
       Text("Текстовые шаги доступны без интернета")
      }
     }
    }
   }
  }
  for(options in listOf(
   FormaVisualOptions(),
   FormaVisualOptions(highContrast=true),
   FormaVisualOptions(simplifiedEffects=true,reducedMotion=true)
  )) {
   rule.runOnIdle { visual.value=options }
   rule.onNodeWithText("Техника упражнений")
    .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    .performScrollTo().assertIsDisplayed()
   rule.onNodeWithText("Текстовые шаги доступны без интернета")
    .performScrollTo().assertIsDisplayed()
  }
 }

 @Test fun reducedMotionStops2DSchematicPlaybackButKeepsManualScrubbing(){
  rule.setContent{
   MaterialTheme {
    CompositionLocalProvider(LocalFormaVisualOptions provides FormaVisualOptions(reducedMotion=true)) {
     OriginalMotionDemo(ru.forma.domain.exerciseCatalog.first())
    }
   }
  }
  rule.onNodeWithTag("original-motion-play").assertIsNotEnabled()
  rule.onNodeWithTag("original-motion-scrubber").assertExists()
  rule.onNodeWithText("Уменьшение движения включено.",substring=true).assertExists()
 }
 @Test fun narrowDoubleFontSwitchLabelsStayOperable(){
  var high by mutableStateOf(false)
  rule.setContent {
   val density=LocalDensity.current
   CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
    MaterialTheme {
     Column(Modifier.width(260.dp).verticalScroll(rememberScrollState())) {
      FormaGlassPanel("Оформление"){
       SwitchRow("Повышенная контрастность",high){ high=it }
      }
     }
    }
   }
  }
  rule.onNodeWithText("Повышенная контрастность")
   .performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
  rule.runOnIdle { org.junit.Assert.assertTrue(high) }
  rule.onNodeWithText("Повышенная контрастность")
   .performScrollTo().performClick()
  rule.runOnIdle { org.junit.Assert.assertFalse(high) }
 }
}
