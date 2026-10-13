package ru.forma.app
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class JourneyTest{
 @get:Rule val rule=createAndroidComposeRule<MainActivity>()
 private fun click(text:String){val n=rule.onNodeWithText(text,useUnmergedTree=true);if(text !in listOf("Прогресс","Профиль","Тренировки"))n.performScrollTo();n.performClick();rule.waitForIdle()}
 @Test fun offlineProfileWorkoutAndIsolation(){
  rule.waitUntil(20000){rule.onAllNodesWithText("Имя").fetchSemanticsNodes().isNotEmpty()}
  rule.onNodeWithText("Имя").performTextInput("Тест А");click("Сохранить профиль")
  rule.waitUntil(20000){rule.onAllNodesWithText("Твоя форма.").fetchSemanticsNodes().isNotEmpty()}
  click("К тренировкам");rule.onAllNodesWithText("Начать тренировку").onFirst().performScrollTo().performClick()
  rule.waitUntil(20000){rule.onAllNodesWithText("Пропустить этап").fetchSemanticsNodes().isNotEmpty()}
  click("Пропустить этап");click("Подход выполнен")
  rule.waitUntil(20000){rule.onAllNodesWithText("Отдых").fetchSemanticsNodes().isNotEmpty()}
  click("Пауза");rule.onNodeWithText("Продолжить сопровождение").assertExists();click("Продолжить сопровождение")
  click("Остановить тренировку");click("Сохранить и завершить");click("Прогресс")
  rule.onAllNodesWithText("Подходы: 1 · повторения: 12").onFirst().assertExists()
  click("Профиль");click("Тёмное");click("Добавить пользователя")
  rule.onNodeWithText("Имя").performTextInput("Тест Б");click("Сохранить профиль")
  rule.waitUntil(20000){rule.onAllNodesWithText("Тест Б").fetchSemanticsNodes().isNotEmpty()}
  click("Прогресс");rule.onNodeWithText("История появится после первой сохранённой тренировки.").assertExists()
  click("Профиль");click("Тест А");click("Прогресс")
  rule.onAllNodesWithText("Подходы: 1 · повторения: 12").onFirst().assertExists()
 }
}
