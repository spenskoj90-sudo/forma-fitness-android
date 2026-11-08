package ru.forma.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.*

/** Real Android Compose witnesses for unit semantics; all data is synthetic. */
@RunWith(AndroidJUnit4::class)
class RecordedLoadUiTest {
 @get:Rule val rule=createComposeRule()

 @Test fun historySeparatesTwoDumbbellExercisesAndNeverSumsBandLoad(){
  val press=exerciseCatalog.single{it.id=="floor_press"}
  val goblet=exerciseCatalog.single{it.id=="goblet"}
  val band=exerciseCatalog.single{it.id=="band_row"}
  val day=TrainingDay(1,"Synthetic",exercises=listOf(
   Prescription(press,1,12),Prescription(goblet,1,10),Prescription(band,1,15)
  ))
  val workout=Workout("synthetic-w",day,1000L,2000L,listOf(
   SetResult(press.id,12,10.0,7,false,1500L),
   SetResult(goblet.id,10,14.0,7,false,1600L),
   SetResult(band.id,15,0.0,7,false,1700L)
  ),Phase.STOPPED)
  rule.setContent { MaterialTheme { ProgressScreen(Profile(name="Tester",history=listOf(workout))) } }
  rule.onNodeWithText("Объём по упражнениям:",substring=true).assertExists()
  rule.onNodeWithText("Жим гантелей на полу: 120.0 кг·повт",substring=true).assertExists()
  rule.onNodeWithText("Приседание с гантелью: 140.0 кг·повт",substring=true).assertExists()
  rule.onNodeWithText("Тяга резинки к поясу: сопротивление резинки",substring=true).assertExists()
  rule.onNodeWithText("Тяга резинки к поясу: 0.0 кг",substring=true).assertDoesNotExist()
 }

 @Test fun savedSetCardClearlyStatesOneDumbbellOfPair(){
  val ex=exerciseCatalog.single{it.id=="floor_press"}
  val current=Prescription(ex,sets=1,reps=12)
  val day=TrainingDay(1,"Synthetic",exercises=listOf(current))
  val started=skipStage(startSession("synthetic",day,1000L),1000L)
  val done=logSet(started,10.0,12,7,false,1500L)
  assertEquals(Phase.DONE,done.phase)
  rule.setContent { MaterialTheme { Column { WorkoutSetCards(done,current) } } }
  rule.onNodeWithText("Запись 1: 10.0 кг на одну гантель из пары",substring=true).assertExists()
 }
}
