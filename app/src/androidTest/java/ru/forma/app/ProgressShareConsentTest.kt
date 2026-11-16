package ru.forma.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.Profile

/** Only synthetic profile names; the external share chooser is never opened. */
@RunWith(AndroidJUnit4::class)
class ProgressShareConsentTest {
 @get:Rule val rule=createComposeRule()

 @Test fun shareRequiresPreviewAndCancellationDoesNotLaunchAnotherApp(){
  rule.setContent{MaterialTheme{ProgressScreen(Profile(id="synthetic-a",name="Тест А"))}}
  rule.onNodeWithText("Поделиться отчётом").performScrollTo().performClick()
  rule.onNodeWithText("Поделиться недельным отчётом?").assertExists()
  rule.onNodeWithText("Профиль: Тест А",substring=true).assertExists()
  rule.onNodeWithText("Открыть выбор приложения").assertExists()
  rule.onNodeWithText("Отмена").performClick()
  rule.onNodeWithText("Поделиться недельным отчётом?").assertDoesNotExist()
 }

 @Test fun switchingProfileDismissesPreviousPrivateReportPreview(){
  var current by mutableStateOf(Profile(id="synthetic-a",name="Тест А"))
  rule.setContent{MaterialTheme{ProgressScreen(current)}}
  rule.onNodeWithText("Поделиться отчётом").performScrollTo().performClick()
  rule.onNodeWithText("Профиль: Тест А",substring=true).assertExists()
  rule.runOnIdle{current=Profile(id="synthetic-b",name="Тест Б")}
  rule.onNodeWithText("Поделиться недельным отчётом?").assertDoesNotExist()
  rule.onNodeWithText("Профиль: Тест А",substring=true).assertDoesNotExist()
 }
}
