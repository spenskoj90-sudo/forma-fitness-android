package ru.forma.app

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.Equipment

@RunWith(AndroidJUnit4::class)
class ExerciseTechniqueCatalogTest {
 @get:Rule val rule=createComposeRule()

 @Test fun offlineSearchExpandSafetyAndNoFalseVideoClaim(){
  rule.setContent{MaterialTheme{
   Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){
    ExerciseTechniqueCatalog("profile-a",setOf(Equipment.BODYWEIGHT,Equipment.RESISTANCE_BAND))
   }
  }}
  rule.onNodeWithTag("technique-search").performTextInput("тяга резинки")
  rule.onNodeWithTag("technique-item-band_row").assertExists()
   .performScrollTo().performClick()
  rule.onNodeWithText("Ненадёжная точка крепления",substring=true).assertExists()
  rule.onNodeWithText("Это не видеодемонстрация",substring=true).assertExists()
  rule.onNodeWithTag("technique-item-band_row").performScrollTo().performClick()
  rule.onNodeWithText("Ненадёжная точка крепления",substring=true).assertDoesNotExist()
 }

 @Test fun filterByMuscleAndOptInToUnavailableExercises(){
  rule.setContent{MaterialTheme{
   Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){
    ExerciseTechniqueCatalog("profile-b",setOf(Equipment.BODYWEIGHT))
   }
  }}
  rule.onNodeWithTag("technique-search").performTextInput("жим ногами")
  rule.onNodeWithText("По выбранным условиям упражнений нет.",substring=true).assertExists()
  rule.onNodeWithText("Весь каталог").performScrollTo().performClick()
  rule.onNodeWithTag("technique-item-leg_press").assertExists().performScrollTo().performClick()
  rule.onNodeWithText("Этот снаряд не выбран",substring=true).assertExists()
  rule.onNodeWithText("Очистить поиск и группу").performScrollTo().performClick()
  rule.onNodeWithText("Все мышцы").performScrollTo().assertExists()
 }
}
