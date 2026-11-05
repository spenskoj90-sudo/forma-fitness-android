package ru.forma.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.forma.domain.*

/** Restore unsaved inputs, without treating a form as durable workout history. */
class FormDraftRestorationTest {
 @get:Rule val rule=createComposeRule()

 @Test fun profileEditorRestoresInputWithoutDroppingHistoricalRecords() {
  val history=Workout(id="historical",day=TrainingDay(day=1,name="Saved"),startedMs=1L,
   finishedMs=2L,sets=emptyList(),status=Phase.STOPPED)
  val original=Profile(id="owner-one",name="Original",history=listOf(history))
  var accepted:Profile?=null
  val restore=StateRestorationTester(rule)
  restore.setContent {
   MaterialTheme { ProfileEditor(original,{accepted=it},{},true) }
  }
  rule.onNodeWithText("Имя").performTextReplacement("Сохранённый черновик")
  rule.onNodeWithText("Возраст").performTextReplacement("42")
  restore.emulateSavedInstanceStateRestore()
  rule.onNodeWithText("Сохранённый черновик").assertExists()
  rule.onNodeWithText("Сохранить профиль").assertIsEnabled().performClick()
  rule.runOnIdle {
   assertEquals("owner-one",accepted?.id)
   assertEquals("Сохранённый черновик",accepted?.name)
   assertEquals(42,accepted?.age)
   assertEquals(listOf(history),accepted?.history)
  }
 }

 @Test fun cardioControlsRestoreIntervalsAndDurationBeforeStart() {
  val day=TrainingDay(day=2,name="Cardio",cardio=CardioKind.WALK,durationSec=1200)
  var started:TrainingDay?=null
  val restore=StateRestorationTester(rule)
  restore.setContent {
   MaterialTheme {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
     CardioControls(day){started=it}
    }
   }
  }
  rule.onNodeWithText("Ходьба / бег").performClick()
  rule.onNodeWithText("Длительность, минут (1–120)").performTextReplacement("35")
  rule.onNodeWithText("Ходьба, секунд (10–1800)").performTextReplacement("150")
  rule.onNodeWithText("Бег, секунд (10–1800)").performTextReplacement("75")
  restore.emulateSavedInstanceStateRestore()
  rule.onNodeWithText("Начать кардио").performScrollTo().assertIsEnabled().performClick()
  rule.runOnIdle {
   assertEquals(CardioKind.INTERVAL,started?.cardio)
   assertEquals(2100,started?.durationSec)
   assertEquals(listOf(150,75),started?.intervals)
  }
 }

 @Test fun measurementEditRestoresOriginalIdAndUserInput() {
  val measure=Measurement(id="measure-one",dateMs=123_456L,
   values=mapOf("weight" to 75.0))
  val owner=Profile(id="owner-two",name="Owner",measurements=listOf(measure))
  var savedId:String?=null
  var savedDate:Long?=null
  var savedValues:Map<String,Double>?=null
  val restore=StateRestorationTester(rule)
  restore.setContent {
   MaterialTheme {
    BodyScreen(owner){values,id,dateMs,done->
     savedId=id;savedDate=dateMs;savedValues=values;done()
    }
   }
  }
  rule.onNodeWithText("Исправить запись").performScrollTo().performClick()
  rule.onNodeWithText("Вес, кг").performTextReplacement("77")
  restore.emulateSavedInstanceStateRestore()
  rule.onNodeWithText("Сохранить замеры").performScrollTo().assertIsEnabled().performClick()
  rule.runOnIdle {
   assertEquals("measure-one",savedId)
   assertEquals(123_456L,savedDate)
   assertEquals(77.0,savedValues?.get("weight")?:0.0,0.0)
  }
 }
}
