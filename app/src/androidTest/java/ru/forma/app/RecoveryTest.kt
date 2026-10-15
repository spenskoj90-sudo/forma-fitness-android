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
  // Verify durable resume, a live service tick, then stop deliberately.
  try{rule.waitUntil(20000){runBlocking{app.repository.load(p.id)}?.active?.paused==false}}
  catch(e:androidx.compose.ui.test.ComposeTimeoutException){
   val active=runBlocking{app.repository.load(p.id)}?.active
   throw AssertionError("Owner Resume did not persist: session=$active, serviceRunning=${app.serviceRunning}",e)
  }
  val resumeTick=runBlocking{app.repository.load(p.id)}?.active?.lastTickMs?:error("Missing resumed workout")
  rule.waitUntil(20000){runBlocking{app.repository.load(p.id)}?.active?.let{!it.paused&&it.lastTickMs>resumeTick+1000}==true}
  rule.onNodeWithText("Боль / дискомфорт — стоп").performScrollTo().performClick()
  rule.waitUntil(15000){runBlocking{app.repository.load(p.id)}?.active?.terminal==true}
  // Room may persist STOPPED before StateFlow recomposes Runner. On older
  // Compose/Android combinations the button text is only in the unmerged tree.
  // Assert both the durable terminal phase above and an actual rendered action.
  try {
   rule.waitUntil(20000){
    rule.onAllNodesWithText("Сохранить и завершить",useUnmergedTree=true)
     .fetchSemanticsNodes().isNotEmpty()
   }
  }catch(e:androidx.compose.ui.test.ComposeTimeoutException){
   runCatching{rule.onRoot(useUnmergedTree=true).printToLog("FORMA-RECOVERY-TIMEOUT")}
   throw AssertionError("Terminal session persisted but completion action was not rendered",e)
  }
  rule.onNodeWithText("Сохранить и завершить",useUnmergedTree=true).performScrollTo().performClick()
  rule.waitUntil(15000){runBlocking{app.repository.load(p.id)}?.active==null}
  val saved=runBlocking{app.repository.load(p.id)}!!
  assertEquals(ru.forma.domain.Phase.STOPPED,saved.history.last().status)
  assertTrue(saved.history.last().activeSeconds<=before.activeSeconds+15)
  assertEquals(4,runBlocking{app.repository.profiles.first()}.size)
 }
}
