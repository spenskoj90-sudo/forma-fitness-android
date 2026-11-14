package ru.forma.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Exercise media modes wrap rather than truncating when Android font scaling
 * or the device's narrow available width makes one fixed row inaccessible.
 * Session state is owned by the Room-backed workout screen, not this selector.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkoutTechniqueTabs(selectedIndex:Int,onSelect:(Int)->Unit,hasReviewedVideo:Boolean=true){
 FlowRow(
  modifier=Modifier.fillMaxWidth(),
  horizontalArrangement=Arrangement.spacedBy(6.dp),
  verticalArrangement=Arrangement.spacedBy(6.dp)
 ){
  (if(hasReviewedVideo) listOf(0 to "Видео",1 to "Инструкция",2 to "Мышцы")
   else listOf(1 to "Инструкция",2 to "Мышцы")).forEach{(index,title)->
   FilterChip(
    selected=selectedIndex==index,
    onClick={onSelect(index)},
    label={Text(title)},
    modifier=Modifier.heightIn(min=48.dp).testTag("workout-technique-tab-$index")
   )
  }
 }
}
