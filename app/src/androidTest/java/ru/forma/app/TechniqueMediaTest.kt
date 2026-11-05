package ru.forma.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.*

/** All fixture bytes/metadata live in the test APK, never the user APK. CI is offline. */
@RunWith(AndroidJUnit4::class)
class TechniqueMediaTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 private val exercise=exerciseCatalog.first()
 private fun fixtureSource():AssetTechniqueMediaSource {
  val assets=InstrumentationRegistry.getInstrumentation().context.assets
  val manifest=assets.open(TECHNIQUE_MANIFEST).bufferedReader().use{Json.decodeFromString<TechniqueManifest>(it.readText())}
  return AssetTechniqueMediaSource(assets,manifest.entries)
 }
 private fun awaitText(text:String){rule.waitUntil(10_000){rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
 private fun awaitPlay(){rule.waitUntil(10_000){rule.onAllNodes(hasText("Воспроизвести демонстрацию") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}}

 @Test fun realAssetsExtractorAndMutedPlayerWorkOfflineAndPauseOnBackground() {
  val source=fixtureSource()
  assertTrue(runBlocking{source.load(exercise)} is TechniqueMediaResult.Ready)
  rule.setContent{MaterialTheme{Column(Modifier.verticalScroll(rememberScrollState())){ExerciseTechnique(exercise,source)}}}
  rule.onNodeWithText("Показать демонстрацию").performClick()
  awaitText("SYNTHETIC — setup")
  rule.onNodeWithText("Воспроизвести демонстрацию").performScrollTo()
  awaitPlay()
  rule.onNodeWithText("Воспроизвести демонстрацию").performClick()
  awaitText("Демонстрация воспроизводится")
  rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
  rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
  awaitPlay()
  rule.onNodeWithText("Демонстрация воспроизводится").assertDoesNotExist()
 }
 @Test fun changingExerciseImmediatelyRemovesOldDemonstrationAndTranscript() {
  val selected=mutableStateOf(exercise);val source=fixtureSource()
  rule.setContent{MaterialTheme{Column(Modifier.verticalScroll(rememberScrollState())){ExerciseTechnique(selected.value,source)}}}
  rule.onNodeWithText("Показать демонстрацию").performClick();awaitText("SYNTHETIC — setup")
  rule.runOnIdle{selected.value=exercise.copy(equipment=Equipment.entries.first{it!=exercise.equipment})}
  rule.onNodeWithText("SYNTHETIC — setup").assertDoesNotExist()
  rule.onNodeWithText("Показать демонстрацию").performClick()
  awaitText("Материал не прошёл проверку. Доступна текстовая инструкция.")
  val other=exerciseCatalog.first{it.id!=exercise.id}
  rule.runOnIdle{selected.value=other}
  rule.onNodeWithText("SYNTHETIC — setup").assertDoesNotExist()
  rule.onNodeWithContentDescription("Демонстрация техники: ${exercise.name}").assertDoesNotExist()
  rule.onNodeWithText(other.cue).assertExists()
  rule.onNodeWithText("Показать демонстрацию").performClick()
  awaitText("Подтверждённая демонстрация пока недоступна.")
 }
 @Test fun userApkHasHonestEmptyManifestAndKeepsTechniqueAndMistakes() {
  val source=AssetTechniqueMediaSource(InstrumentationRegistry.getInstrumentation().targetContext.assets)
  assertEquals(TechniqueMediaResult.Unavailable,runBlocking{source.load(exercise)})
  rule.setContent{MaterialTheme{Column(Modifier.verticalScroll(rememberScrollState())){ExerciseTechnique(exercise,source)}}}
  rule.onNodeWithText(exercise.cue).assertExists()
  rule.onNodeWithText("Частые ошибки: ${exercise.mistakes.ifBlank{"Пока не описаны."}}").assertExists()
  rule.onNodeWithText("Показать демонстрацию").performClick()
  awaitText("Подтверждённая демонстрация пока недоступна.")
  rule.onNodeWithText("Воспроизвести демонстрацию").assertDoesNotExist()
 }
 @Test fun largeFontTranscriptSemanticsAndDisclosureRemainReachable() {
  val source=fixtureSource()
  rule.setContent{MaterialTheme{
   val density=LocalDensity.current
   CompositionLocalProvider(LocalDensity provides Density(density.density,2f)){
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){
     ExerciseTechnique(exercise,source)
     TextButton({},Modifier.heightIn(min=48.dp)){Text("Дальше")}
    }
   }
  }}
  rule.onNodeWithText("Показать демонстрацию").performScrollTo().assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
  awaitText("SYNTHETIC — setup")
  rule.onNodeWithText("SYNTHETIC — setup").performScrollTo().assertIsDisplayed()
  rule.onNodeWithText("SYNTHETIC — errors").performScrollTo().assertIsDisplayed()
  rule.onNodeWithText("Дальше").performScrollTo().assertIsDisplayed().assertHasClickAction()
  rule.onNodeWithText("Скрыть демонстрацию").performScrollTo().performClick()
  rule.onNodeWithText("SYNTHETIC — setup").assertDoesNotExist()
 }
 @Test fun matchingHashCannotMakeCorruptContainerPlayable() {
  val assets=InstrumentationRegistry.getInstrumentation().context.assets
  val original=assets.open(TECHNIQUE_MANIFEST).bufferedReader().use{Json.decodeFromString<TechniqueManifest>(it.readText())}.entries.single()
  val garbage="SYNTHETIC corrupt container".toByteArray()
  val hash=java.security.MessageDigest.getInstance("SHA-256").digest(garbage).joinToString(""){"%02x".format(it)}
  val entry=original.copy(sha256=hash)
  val manifest=kotlinx.serialization.json.Json.encodeToString(TechniqueManifest.serializer(),TechniqueManifest(1,listOf(entry))).toByteArray()
  val source=AssetTechniqueMediaSource(openAsset={java.io.ByteArrayInputStream(if(it==TECHNIQUE_MANIFEST)manifest else garbage)},admitted=listOf(entry))
  assertEquals(TechniqueMediaResult.Unsupported,runBlocking{source.load(exercise)})
  rule.setContent{MaterialTheme{Column(Modifier.verticalScroll(rememberScrollState())){ExerciseTechnique(exercise,source)}}}
  rule.onNodeWithText("Показать демонстрацию").performClick()
  awaitText("Формат демонстрации не поддерживается или файл повреждён.")
  rule.onNodeWithText("Воспроизвести демонстрацию").assertDoesNotExist()
 }
}
