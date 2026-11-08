package ru.forma.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.*

/** Actual Android Compose semantics, with synthetic offline workout data. */
@RunWith(AndroidJUnit4::class)
class AccessibleWorkoutUiTest {
 @get:Rule val rule=createComposeRule()

 @Test fun pageAndPanelTitlesAreNavigableHeadings(){
  rule.setContent{MaterialTheme{
   Page("Сеанс"){Panel("Настройки тренировки"){Text("Локально")}}
  }}
  rule.onNodeWithText("Сеанс")
   .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
  rule.onNodeWithText("Настройки тренировки")
   .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
 }

 @Test fun coachSlidersHaveSpecificNamesAndCurrentUnits(){
  rule.setContent{MaterialTheme{Column{
   CoachSettingSlider("Громкость подсказок",65f,"65%",0f..100f){}
   CoachSettingSlider("Темп",90f,"90 уд/мин",40f..180f){}
   CoachSettingSlider("Скорость речи",1.1f,"x1.1",0.7f..1.3f){}
  }}}
  rule.onNodeWithContentDescription("Громкость подсказок",useUnmergedTree=true)
   .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"65%"))
  rule.onNodeWithContentDescription("Темп",useUnmergedTree=true)
   .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"90 уд/мин"))
  rule.onNodeWithContentDescription("Скорость речи",useUnmergedTree=true)
   .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"x1.1"))
 }

 @Test fun stageChangeHasPoliteAnnouncementWithoutCountdownSpam(){
  val exercise=exerciseCatalog.single{it.id=="squat"}
  val day=TrainingDay(1,"Доступность",exercises=listOf(Prescription(exercise,1,12)))
  val session=startSession("synthetic",day,1000L)
  rule.setContent{MaterialTheme{WorkoutJourneyProgress(session)}}
  rule.onNodeWithText("Разминка перед основной частью тренировки.")
   .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion,LiveRegionMode.Polite))
  rule.onNodeWithText("Ход тренировки")
   .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
 }

 @Test fun chartReadsCompleteHistoryOnceInsteadOfAxisLabels(){
  rule.setContent{MaterialTheme{
   HistoryChart(listOf(ChartSample(1000L,10.0),ChartSample(2000L,15.0)),"Прогресс","кг")
  }}
  rule.onNodeWithContentDescription("Прогресс: 2 значений, кг.",substring=true).assertExists()
  rule.onNodeWithText("Макс. 15.0 кг").assertDoesNotExist()
 }
}
