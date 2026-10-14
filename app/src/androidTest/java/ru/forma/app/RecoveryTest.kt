package ru.forma.app
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class RecoveryTest{
 @get:Rule val rule=createAndroidComposeRule<MainActivity>()
 @Test fun processDeathDoesNotCreditAbsentTime(){
  rule.waitUntil(20000){rule.onAllNodesWithText("Продолжить сопровождение").fetchSemanticsNodes().isNotEmpty()}
  val app=rule.activity.application as FormaApp
  val p=runBlocking{app.repository.profiles.first()}.first{it.active!=null}
  val before=p.active!!;assertTrue(before.paused);assertFalse(app.serviceRunning)
  rule.onNodeWithText("Продолжить сопровождение").performScrollTo().performClick()
  rule.waitUntil(15000){app.serviceRunning}
  rule.onNodeWithText("Боль / дискомфорт — стоп").performScrollTo().performClick()
  rule.waitUntil(15000){runBlocking{app.repository.load(p.id)}?.active?.terminal==true}
  rule.onNodeWithText("Сохранить и завершить").performScrollTo().performClick()
  rule.waitUntil(15000){runBlocking{app.repository.load(p.id)}?.active==null}
  val saved=runBlocking{app.repository.load(p.id)}!!
  assertEquals(ru.forma.domain.Phase.STOPPED,saved.history.last().status)
  assertTrue(saved.history.last().activeSeconds<=before.activeSeconds+15)
  assertEquals(4,runBlocking{app.repository.profiles.first()}.size)
 }
}
