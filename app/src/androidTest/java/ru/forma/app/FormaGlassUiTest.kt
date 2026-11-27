package ru.forma.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FormaGlassUiTest {
 @get:Rule val rule=createComposeRule()

 @Test fun changingEffectAndContrastModesKeepsHeadingAndBodyAccessible(){
  val visual=mutableStateOf(FormaVisualOptions())
  rule.setContent {
   CompositionLocalProvider(LocalFormaVisualOptions provides visual.value) {
    MaterialTheme {
     Column(Modifier.width(260.dp).verticalScroll(rememberScrollState())) {
      FormaGlassPanel("Техника упражнений") {
       Text("Текстовые шаги доступны без интернета")
      }
     }
    }
   }
  }
  for(options in listOf(
   FormaVisualOptions(),
   FormaVisualOptions(highContrast=true),
   FormaVisualOptions(simplifiedEffects=true,reducedMotion=true)
  )) {
   rule.runOnIdle { visual.value=options }
   rule.onNodeWithText("Техника упражнений")
    .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    .performScrollTo().assertIsDisplayed()
   rule.onNodeWithText("Текстовые шаги доступны без интернета")
    .performScrollTo().assertIsDisplayed()
  }
 }

 @Test fun staticPlankOffersCameraRotationAndTheOfflineTechniqueWithoutPlayback(){
  rule.setContent{
   MaterialTheme {
    CompositionLocalProvider(LocalFormaVisualOptions provides FormaVisualOptions(reducedMotion=true)) {
     Column(Modifier.width(260.dp).verticalScroll(rememberScrollState())) {
      AuthoredTechniqueWalkthrough(ru.forma.domain.exerciseCatalog.first{it.id=="mat_plank"})
     }
    }
   }
  }
  rule.onNodeWithTag("volume-preview-toggle").performScrollTo().performClick()
  rule.onNodeWithText("Статическое удержание").performScrollTo().assertIsDisplayed()
  rule.onNodeWithTag("volume-preview-orbit").assertExists()
  rule.onNodeWithTag("volume-preview-play").assertDoesNotExist()
  rule.onNodeWithTag("volume-preview-scrub").assertDoesNotExist()
  rule.onNodeWithTag("authored-technique-frame").performScrollTo().assertIsDisplayed()
 }
 @Test fun narrowDoubleFontSwitchLabelsStayOperable(){
  var high by mutableStateOf(false)
  rule.setContent {
   val density=LocalDensity.current
   CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
    MaterialTheme {
     Column(Modifier.width(260.dp).verticalScroll(rememberScrollState())) {
      FormaGlassPanel("Оформление"){
       SwitchRow("Повышенная контрастность",high){ high=it }
      }
     }
    }
   }
  }
  rule.onNodeWithText("Повышенная контрастность")
   .performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
  rule.runOnIdle { org.junit.Assert.assertTrue(high) }
  rule.onNodeWithText("Повышенная контрастность")
   .performScrollTo().performClick()
  rule.runOnIdle { org.junit.Assert.assertFalse(high) }
 }

 @Test fun narrowAccountScreenAcceptsCredentialsAndOffersVerificationAndSignOut() {
  var session by mutableStateOf(AccountSession.SIGNED_OUT)
  rule.setContent {
   val density=LocalDensity.current
   CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
    MaterialTheme {
     Column(Modifier.width(260.dp)) {
      AccountScreen(AccountScreenState(session),{ action, email, password ->
       session=when(action) {
        AccountAction.SIGN_IN -> {
         org.junit.Assert.assertEquals("demo@example.test",email)
         org.junit.Assert.assertEquals("Synthetic-password",password)
         AccountSession.EMAIL_UNVERIFIED
        }
        AccountAction.REFRESH -> AccountSession.EMAIL_VERIFIED
        AccountAction.SIGN_OUT -> AccountSession.SIGNED_OUT
        else -> error("Unexpected action: $action")
       }
      },{})
     }
    }
   }
  }
  rule.onNodeWithText("Войти").performScrollTo().assertIsNotEnabled()
  rule.onNode(hasSetTextAction() and hasText("Электронная почта"))
   .performScrollTo().performTextInput("demo@example.test")
  rule.onNode(hasSetTextAction() and hasText("Пароль"))
   .performScrollTo().performTextInput("Synthetic-password")
  rule.onNodeWithText("Войти").performScrollTo().assertIsEnabled().performClick()
  rule.onNodeWithText("Проверить подтверждение").performScrollTo().performClick()
  rule.onNodeWithText("Почта подтверждена.",substring=true).assertExists()
  rule.onNodeWithText("Выйти из аккаунта").performScrollTo().performClick()
  rule.onNodeWithText("Войти").performScrollTo().assertIsNotEnabled()
  rule.onNodeWithText("demo@example.test").assertDoesNotExist()
 }
}
