package ru.forma.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.forma.domain.Muscle

/** Keep anterior/posterior schematics readable without claiming clinical anatomy. */
@Composable
internal fun WorkoutMuscleViews(primary:Muscle,secondary:Set<Muscle>){
 BoxWithConstraints(Modifier.fillMaxWidth()){
  // Normal-sized screens preserve the compact side-by-side presentation.
  // At 2x fonts or narrow layout each accessible figure gets full width.
  val stack=maxWidth<300.dp || LocalDensity.current.fontScale>=1.6f
  if(stack){
   Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
    WorkoutMuscleTile(primary,secondary,false,Modifier.fillMaxWidth())
    WorkoutMuscleTile(primary,secondary,true,Modifier.fillMaxWidth())
   }
  }else{
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
    WorkoutMuscleTile(primary,secondary,false,Modifier.weight(1f))
    WorkoutMuscleTile(primary,secondary,true,Modifier.weight(1f))
   }
  }
 }
}

@Composable
private fun WorkoutMuscleTile(
 primary:Muscle,secondary:Set<Muscle>,back:Boolean,modifier:Modifier
){
 Column(modifier.testTag(if(back)"workout-muscle-view-back" else "workout-muscle-view-front"),
  horizontalAlignment=Alignment.CenterHorizontally){
  Text(if(back)"Сзади" else "Спереди",style=MaterialTheme.typography.labelMedium)
  MuscleFocusMap(primary,Modifier.fillMaxWidth().height(166.dp),secondary,backView=back)
 }
}
