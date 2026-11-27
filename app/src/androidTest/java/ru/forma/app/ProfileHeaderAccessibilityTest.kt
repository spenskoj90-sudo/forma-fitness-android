package ru.forma.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileHeaderAccessibilityTest {
 @get:Rule val rule=createComposeRule()

 @Test fun longProfileIdentityIsFullySpokenAndFitsAtDoubleFont() {
  val fullName="Синтетический профиль с длинным отличительным именем"
  rule.setContent {
   val density=LocalDensity.current.density
   CompositionLocalProvider(LocalDensity provides Density(density,2f)) {
    MaterialTheme {
     Box(Modifier.width(320.dp).testTag("profile-header-container")) {
      FormaBrandAndProfileHeader(fullName)
     }
    }
   }
  }
  val name=rule.onNodeWithTag("active-profile-header",useUnmergedTree=true)
   .assertExists()
   .assertContentDescriptionEquals("Активный профиль: $fullName")
  val bounds=name.fetchSemanticsNode().boundsInRoot
  val host=rule.onNodeWithTag("profile-header-container",useUnmergedTree=true)
   .fetchSemanticsNode().boundsInRoot
  assertTrue("Profile label has no available width",bounds.width>0f)
  assertTrue("Profile label leaves the narrow viewport",bounds.right<=host.right+1f)
  assertTrue("Profile label starts outside the viewport",bounds.left>=host.left-1f)
 }

 @Test fun preOnboardingHeaderNeverInventsSelectedProfile() {
  rule.setContent {
   MaterialTheme { FormaBrandAndProfileHeader(null) }
  }
  rule.onNodeWithTag("active-profile-header",useUnmergedTree=true).assertDoesNotExist()
  rule.onNodeWithText("FORM",useUnmergedTree=true).assertExists()
  rule.onNodeWithText("A",useUnmergedTree=true).assertExists()
 }
}
