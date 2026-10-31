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
@Composable fun WorkoutSetCards(session:Session,current:Prescription) {
 val logged=session.sets.filter{it.exerciseId==current.exercise.id}
 for(index in 1..current.sets) {
  val slot=workoutSetSlotStatus(session,index)
  val status=when(slot) {
   WorkoutSetSlotStatus.PASSED -> "Пройден"
   WorkoutSetSlotStatus.CURRENT -> "Текущий"
   WorkoutSetSlotStatus.UPCOMING -> "Впереди"
  }
  Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
   Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
    Text("Подход $index из ${current.sets}",style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
    Text(status,style=MaterialTheme.typography.labelLarge,color=if(slot==WorkoutSetSlotStatus.CURRENT)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    if(slot==WorkoutSetSlotStatus.CURRENT) Text("План: ${current.reps} повторений",style=MaterialTheme.typography.bodyMedium)
   }
  }
 }
 if(session.setNumber>1) Text("Пройденный подход мог быть выполнен или пропущен. Сохранённые результаты приведены отдельно.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
 if(logged.isNotEmpty()) {
  Text("Записанные результаты упражнения",style=MaterialTheme.typography.titleSmall)
  logged.forEachIndexed { recordIndex,result ->
   val load=recordedSetLoadDisplay(result,current.exercise.equipment)
   Text("Запись ${recordIndex+1}: $load · ${result.reps} повторений · сложность ${result.effort}/10",style=MaterialTheme.typography.bodyMedium)
  }
 }
}
