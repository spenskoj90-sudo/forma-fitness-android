package ru.forma.app

import android.Manifest
import android.os.Build
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.rule.GrantPermissionRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.forma.domain.*

@RunWith(AndroidJUnit4::class)
class JourneyTest{
 private val composeRule=createAndroidComposeRule<MainActivity>()
 @get:Rule val rules:TestRule=if(Build.VERSION.SDK_INT>=33)RuleChain.outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)).around(composeRule)else composeRule
 private val app get()=composeRule.activity.application as FormaApp
 private fun waitText(text:String){
  try{composeRule.waitUntil(20000){composeRule.onAllNodesWithText(text,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}}
  catch(e:androidx.compose.ui.test.ComposeTimeoutException){
   runCatching{screenshot("text-timeout-${text.hashCode()}")}
   runCatching{composeRule.onRoot(useUnmergedTree=true).printToLog("FORMA-TEXT-TIMEOUT")}
   throw e
  }
 }
 // The bottom navigation's labels may not appear as text (alwaysShowLabel=false).
 // Require an actionable navigation target after the active workout disappears.
 private fun waitNavigation(){
  try{composeRule.waitUntil(20000){composeRule.onAllNodesWithContentDescription("Прогресс",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}}
  catch(e:androidx.compose.ui.test.ComposeTimeoutException){
   // Capture while Activity is still alive; CI failure screenshots after
   // instrumentation teardown only show the Android launcher.
   runCatching{screenshot("navigation-timeout")}
   runCatching{composeRule.onRoot(useUnmergedTree=true).printToLog("FORMA-NAV-TIMEOUT")}
   throw e
  }
 }
 private fun click(text:String){
  if(text in listOf("Главная","Моё тело","Прогресс","Профиль","Тренировки"))composeRule.onNodeWithContentDescription(text,useUnmergedTree=true).performClick()
  else composeRule.onNodeWithText(text,useUnmergedTree=true).performScrollTo().performClick()
  composeRule.waitForIdle()
 }
 private fun screenshot(name:String){composeRule.waitForIdle();val inst=InstrumentationRegistry.getInstrumentation();val file=java.io.File(app.filesDir,"evidence/$name.png");file.parentFile!!.mkdirs();inst.uiAutomation.takeScreenshot().let{bitmap->file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
 private fun current()=runBlocking{app.repository.load(app.settings.settings.first().selected)!!}
 @Test fun offlineProfileWorkoutAndIsolation(){
  waitText("Имя");composeRule.onNodeWithText("Имя").performTextInput("Тест А");click("Сохранить профиль");waitText("Твой путь к сильной и здоровой версии себя")
  // The Home UI can render from the Room profile before DataStore finishes\n  // persisting the selected ID; assert the durable new-profile defaults directly.\n  assertEquals(setOf(1,3,5),runBlocking{app.repository.profiles.first().single().days})
  screenshot("home-light");click("Тренировки");screenshot("training-light")
  composeRule.onAllNodesWithText("Начать тренировку").onFirst().performScrollTo().performClick();waitText("Пропустить этап");click("Пропустить этап");waitText("Подход выполнен");screenshot("workout-light")
  click("Подход выполнен");waitText("Отдых");click("Пауза");waitText("Продолжить сопровождение");click("Продолжить сопровождение")
  click("Остановить тренировку");waitText("Сохранить и завершить");click("Сохранить и завершить");composeRule.waitUntil(20000){current().active==null};waitNavigation();click("Прогресс")
  composeRule.onAllNodesWithText("Подходы: 1 · повторения: 12").onFirst().assertExists()
  click("Профиль");click("Тёмное");click("Добавить пользователя");waitText("Имя");composeRule.onNodeWithText("Имя").performTextInput("Тест Б");click("Сохранить профиль");waitNavigation();waitText("Тест Б")
  click("Прогресс");composeRule.onNodeWithText("История появится после первой сохранённой тренировки.").assertExists()
  click("Профиль");click("Тест А");composeRule.waitUntil(20000){current().name=="Тест А"};click("Прогресс")
  composeRule.onAllNodesWithText("Подходы: 1 · повторения: 12").onFirst().assertExists()
  screenshot("progress-dark");click("Главная");screenshot("home-dark");click("Моё тело");screenshot("body-dark");click("Профиль");screenshot("profile-dark")
  click("Изменить профиль и цели");composeRule.onAllNodesWithText("Общая форма").onFirst().performScrollTo().performClick();click("Сохранить профиль");waitText("Пользователи")
  assertEquals(1,current().history.size)
  click("Тренировки");composeRule.onAllNodesWithText("Начать тренировку").onFirst().performScrollTo().performClick();waitText("Пропустить этап")
  // Regression: a previous foreground loop must not swallow or stop this
  // immediate next start. Confirm the NEW session is receiving live ticks.
  val secondSession=current().active!!
  composeRule.waitUntil(15000){
   val active=current().active
   active?.id==secondSession.id && active.lastTickMs>secondSession.lastTickMs
  }
  click("Пропустить этап")
  val count=current().active!!.day.exercises.sumOf{it.sets}
  for(i in 0 until count){waitText("Подход выполнен");click("Подход выполнен");if(i<count-1){waitText("Отдых");click("Пропустить этап")}}
  waitText("Сохранить и завершить");assertEquals(Phase.DONE,current().active!!.phase);click("Сохранить и завершить");waitNavigation()
  composeRule.waitUntil(20000){current().history.size==2};assertEquals(count,current().history.last().sets.size)
  // Exercise real app/repository backup without sending personal data anywhere.
  runBlocking{val before=app.repository.exportBackup();val ids=app.repository.importBackup(before);assertEquals(2,ids.size);assertEquals(4,app.repository.profiles.firstForTest().size)}
  waitText("Кардио по желанию")
  composeRule.onAllNodesWithText("Длительность, минут (1–120)").onFirst().performScrollTo().performTextReplacement("1")
  composeRule.onAllNodesWithText("Начать кардио").onFirst().performScrollTo().performClick();waitText("Пропустить этап");click("Пропустить этап")
  composeRule.waitUntil(15000){current().active?.phase==Phase.CARDIO}
  val elapsed=current().active!!.activeSeconds
  composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
  composeRule.waitUntil(20000){current().active!!.activeSeconds>=elapsed+3}
  composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
  // Wait for actual cardio terminal state, rather than an arbitrary sleep.
  composeRule.waitUntil(80000){current().active?.phase==Phase.DONE}
  waitText("Сохранить и завершить");click("Сохранить и завершить");waitNavigation()
  composeRule.waitUntil(20000){current().history.size==3};assertEquals(60,current().history.last().day.durationSec)
  composeRule.onAllNodesWithText("Начать тренировку").onFirst().performScrollTo().performClick();waitText("Пропустить этап");click("Боль / дискомфорт — стоп");waitText("Сохранить и завершить");click("Сохранить и завершить")
  composeRule.waitUntil(20000){current().recovery==1};assertEquals(0,current().history.last().sets.size);assertEquals(Phase.STOPPED,current().history.last().status)
  click("Профиль");click("Изменить профиль и цели")
  // Slider semantics make the recovery update independent of pixel coordinates.
  composeRule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress)).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(3f)}
  click("Сохранить профиль");waitText("Пользователи");click("Тренировки")
  composeRule.onAllNodesWithText("Начать кардио").onFirst().performScrollTo().performClick()
  waitText("Пропустить этап")
  val recoveryCardioId=current().active!!.id
  click("Пропустить этап")
  // Stage transition replaces the Runner subtree using Compose key(phase).
  // Clicking Pause before the new CARDIO node is composed can hit a stale
  // WARMUP button and silently miss the user's tap on slower emulators.
  composeRule.waitUntil(20000){current().active?.let{it.id==recoveryCardioId&&it.phase==Phase.CARDIO}==true}
  waitText("Ходьба")
  click("Пауза")
  // Check durable Room state as well as the UI: identify an actual command
  // failure separately from a delayed Compose transition.
  try{composeRule.waitUntil(20000){current().active?.let{it.id==recoveryCardioId&&it.paused}==true}}
  catch(e:androidx.compose.ui.test.ComposeTimeoutException){
   runCatching{screenshot("pause-not-persisted")}
   runCatching{composeRule.onRoot(useUnmergedTree=true).printToLog("FORMA-PAUSE-TIMEOUT")}
   throw AssertionError("Pause did not persist for session $recoveryCardioId; state=${current().active}",e)
  }
  waitText("Продолжить сопровождение")
  composeRule.activityRule.scenario.recreate();waitText("Продолжить сопровождение");assertTrue(current().active!!.paused)
  // Leave a real persisted paused workout for a separate force-stop/relaunch test.
  screenshot("paused-dark")
 }
}
private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstForTest():T=this.first()
