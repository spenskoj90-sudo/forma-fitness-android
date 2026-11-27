package ru.forma.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.Profile

/** Synthetic editable drafts; no Room, personal data or provider credentials. */
@RunWith(AndroidJUnit4::class)
class ProfileEditExitTest {
 @get:Rule val rule=createComposeRule()

 @Test fun dirtyDraftRequiresExplicitDiscardAndNeverSavesImplicitly(){
  val profile=Profile(id="sample-a",name="Original")
  var saved=0
  var cancelled=0
  rule.setContent{MaterialTheme{
   ProfileEditor(profile,save={saved++},cancel={cancelled++},canCancel=true)
  }}
  rule.onNodeWithText("Имя").performTextReplacement("Changed")
  rule.onNodeWithText("Имя").performImeAction()
  rule.onNodeWithTag("profile-cancel",useUnmergedTree=true).assertIsDisplayed()
   .performTouchInput{click()}
  rule.onNodeWithText("Несохранённые изменения").assertExists()
  assertEquals(0,cancelled)
  assertEquals(0,saved)
  rule.onNodeWithText("Продолжить редактирование").performClick()
  rule.onNodeWithText("Несохранённые изменения").assertDoesNotExist()
  rule.onNodeWithTag("profile-cancel",useUnmergedTree=true).performTouchInput{click()}
  rule.onNodeWithText("Выйти без сохранения").performClick()
  assertEquals(1,cancelled)
  assertEquals(0,saved)
 }

 @Test fun unchangedDraftCanBeCancelledImmediately(){
  val profile=Profile(id="sample-b",name="Unchanged")
  var cancelled=0
  rule.setContent{MaterialTheme{
   ProfileEditor(profile,save={},cancel={cancelled++},canCancel=true)
  }}
  rule.onNodeWithTag("profile-cancel",useUnmergedTree=true).assertIsDisplayed()
   .performTouchInput{click()}
  assertEquals(1,cancelled)
  rule.onNodeWithText("Несохранённые изменения").assertDoesNotExist()
 }

 @Test fun twoTimesFontInNarrowViewportKeepsRealSaveTargetUsable(){
  val profile=Profile(id="sample-c",name="Narrow")
  var saved=0
  rule.setContent{
   val density=LocalDensity.current.density
   CompositionLocalProvider(LocalDensity provides Density(density,2f)){
    MaterialTheme{Box(Modifier.width(320.dp).height(600.dp)){
     ProfileEditor(profile,save={saved++},cancel={},canCancel=true)
    }}
   }
  }
  val root=rule.onRoot(useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
  val button=rule.onNodeWithTag("profile-save",useUnmergedTree=true)
   .assertIsEnabled().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
  assertTrue("2x Save button is clipped: button=$button root=$root",
   button.top>=root.top && button.bottom<=root.bottom)
  rule.onNodeWithTag("profile-save",useUnmergedTree=true).performTouchInput{click()}
  assertEquals(1,saved)
 }
}
