package ru.forma.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.forma.domain.*

/** A read-only view of the Room-backed session: no separate stage or timer state. */
@Composable fun WorkoutJourneyProgress(session: Session) {
 val state = workoutJourneyOverview(session)
 Panel("Ход тренировки") {
  if (state.plannedSets > 0) {
   Text("Упражнение ${state.exerciseNumber} из ${state.exerciseCount} · записано ${state.loggedSets} из ${state.plannedSets} подходов", style=MaterialTheme.typography.bodyMedium)
   LinearProgressIndicator(progress={state.loggedSets.toFloat()/state.plannedSets},modifier=Modifier.fillMaxWidth())
  }
  Text(state.nextStep,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
 }
}

/** Text statuses replace glyph-only status cells and avoid cramped fixed-width columns. */
@Composable fun WorkoutSetCards(session:Session,current:Prescription,weight:String,reps:String) {
 val logged=session.sets.filter{it.exerciseId==current.exercise.id}
 for(index in 1..current.sets) {
  val result=logged.getOrNull(index-1)
  val status=if(result!=null)"Готово" else if(index==session.setNumber)"Текущий" else "Впереди"
  val load=if(current.exercise.equipment==Equipment.BODYWEIGHT)"собственный вес"
   else (result?.weight?.toString()?:if(index==session.setNumber)weight else "—")+" "+recordedLoadUnit(current.exercise.equipment)
  val count=result?.reps?.toString()?:if(index==session.setNumber)reps else "—"
  Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
   Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
     Text("Подход $index из ${current.sets}",style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
     Text(status,style=MaterialTheme.typography.labelLarge,color=if(result!=null)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("Нагрузка: $load · Повторения: $count",style=MaterialTheme.typography.bodyMedium)
   }
  }
 }
}
