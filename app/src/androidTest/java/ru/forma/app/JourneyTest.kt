package ru.forma.app
import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.rule.GrantPermissionRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class JourneyTest{
 private val composeRule=createAndroidComposeRule<MainActivity>()
 @get:Rule val rules:TestRule=RuleChain.outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)).around(composeRule)
 private fun click(text:String){val n=composeRule.onNodeWithText(text,useUnmergedTree=true);if(text !in listOf("Прогресс","Профиль","Тренировки"))n.performScrollTo();n.performClick();composeRule.waitForIdle()}
 @Test fun offlineProfileWorkoutAndIsolation(){
  composeRule.waitUntil(20000){composeRule.onAllNodesWithText("Имя").fetchSemanticsNodes().isNotEmpty()}
  composeRule.onNodeWithText("Имя").performTextInput("Тест А");click("Сохранить профиль")
  composeRule.waitUntil(20000){composeRule.onAllNodesWithText("Твоя форма.").fetchSemanticsNodes().isNotEmpty()}
  click("К тренировкам");composeRule.onAllNodesWithText("Начать тренировку").onFirst().performScrollTo().performClick()
  composeRule.waitUntil(20000){composeRule.onAllNodesWithText("Пропустить этап").fetchSemanticsNodes().isNotEmpty()}
  click("Пропустить этап");click("Подход выполнен")
  composeRule.waitUntil(20000){composeRule.onAllNodesWithText("Отдых").fetchSemanticsNodes().isNotEmpty()}
  click("Пауза");composeRule.onNodeWithText("Продолжить сопровождение").assertExists();click("Продолжить сопровождение")
  click("Остановить тренировку");click("Сохранить и завершить");click("Прогресс")
  composeRule.onAllNodesWithText("Подходы: 1 · повторения: 12").onFirst().assertExists()
  click("Профиль");click("Тёмное");click("Добавить пользователя")
  composeRule.onNodeWithText("Имя").performTextInput("Тест Б");click("Сохранить профиль")
  composeRule.waitUntil(20000){composeRule.onAllNodesWithText("Тест Б").fetchSemanticsNodes().isNotEmpty()}
  click("Прогресс");composeRule.onNodeWithText("История появится после первой сохранённой тренировки.").assertExists()
  click("Профиль");click("Тест А");click("Прогресс")
  composeRule.onAllNodesWithText("Подходы: 1 · повторения: 12").onFirst().assertExists()
 }
}
