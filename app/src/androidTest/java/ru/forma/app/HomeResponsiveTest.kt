package ru.forma.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.*

/** Synthetic UI evidence; 2x font is injected without changing device settings. */
@RunWith(AndroidJUnit4::class)
class HomeResponsiveTest {
 @get:Rule val rule=createComposeRule()

 @Test fun standardFontKeepsTwoRowsOfThreeShortcuts(){
  rule.setContent{MaterialTheme{
   HomeShortcutGrid(onTrain={},onProgress={},onBody={},onProfile={})
  }}
  rule.onAllNodesWithTag("home-shortcut-row",useUnmergedTree=true).assertCountEquals(2)
  rule.onNodeWithTag("shortcut-body",useUnmergedTree=true).assertExists()
 }

 @Test fun doubleFontUsesThreeRowsAndDoesNotTruncateAnyShortcutLabel(){
  val overflow=mutableMapOf<String,Boolean>()
  rule.setContent{
   val nativeDensity=LocalDensity.current.density
   CompositionLocalProvider(LocalDensity provides Density(nativeDensity,2f)){
    MaterialTheme{
     Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){
      HomeShortcutGrid(onTrain={},onProgress={},onBody={},onProfile={},
       onLabelLayout={label,result->overflow[label]=result.hasVisualOverflow})
     }
    }
   }
  }
  rule.onAllNodesWithTag("home-shortcut-row",useUnmergedTree=true).assertCountEquals(3)
  rule.onNodeWithText("Мой\nпрофиль",useUnmergedTree=true).performScrollTo().assertIsDisplayed()
  rule.waitForIdle()
  assertEquals(6,overflow.size)
  assertFalse("A large-font shortcut label is clipped or ellipsized: $overflow",
   overflow.values.any{it})
 }

 @Test fun doubleFontHeroExpandsAndKeepsPrimaryWorkoutButtonInsideCard(){
  val currentDay=java.time.LocalDate.now().dayOfWeek.value
  val ex=exerciseCatalog.single{it.id=="squat"}
  val next=TrainingDay(currentDay,"Силовая · всё тело",
   exercises=listOf(Prescription(ex,3,12)))
  var started=false
  rule.setContent{
   val nativeDensity=LocalDensity.current.density
   CompositionLocalProvider(LocalDensity provides Density(nativeDensity,2f)){
    MaterialTheme{
     Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){
      Box(Modifier.width(320.dp)){
       HomeHero(next,TrainingPlace.HOME,onStart={started=true},onProfile={})
      }
     }
    }
   }
  }
  rule.onNodeWithText("Сегодня\nтренировка").assertExists()
  rule.onNodeWithText("▶  Начать тренировку").performScrollTo().assertIsDisplayed()
  val card=rule.onNodeWithTag("home-hero",useUnmergedTree=true)
   .fetchSemanticsNode().boundsInRoot
  val button=rule.onNodeWithText("▶  Начать тренировку",useUnmergedTree=true)
   .fetchSemanticsNode().boundsInRoot
  assertTrue("Workout action is clipped below the hero",button.bottom<=card.bottom)
  rule.onNodeWithText("▶  Начать тренировку").performClick()
  assertTrue(started)
 }
}
