package ru.forma.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.forma.domain.Exercise
import ru.forma.domain.authoredTechniqueFrames

/**
 * Original offline text frames are supplemental instruction, not an admitted
 * exercise demonstration. No borrowed footage, network access, profile edits
 * or implicit media playback. Page state is scoped to the exact exercise.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AuthoredTechniqueWalkthrough(exercise: Exercise) {
 val frames = remember(exercise.id, exercise.equipment) { authoredTechniqueFrames(exercise) }
 if(frames.isEmpty()) return
 key(exercise.id, exercise.equipment) {
  OriginalMotionDemo(exercise)
  var page by rememberSaveable { mutableIntStateOf(0) }
  val current = page.coerceIn(0, frames.lastIndex)
  ElevatedCard(Modifier.fillMaxWidth().testTag("authored-technique-card")) {
   Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
    Text("Пошаговая техника · авторская схема", style=MaterialTheme.typography.titleSmall)
    Text("4 текстовых шага без интернета. Не видео и не персональная проверка техники.",
     style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Шаг ${current+1} из ${frames.size}", style=MaterialTheme.typography.labelMedium)
    Text(frames[current].title, style=MaterialTheme.typography.titleSmall)
    Text(frames[current].instruction, modifier=Modifier.testTag("authored-technique-frame")
      .semantics { liveRegion=LiveRegionMode.Polite })
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),
      verticalArrangement=Arrangement.spacedBy(4.dp)) {
     OutlinedButton(onClick={page=(current-1).coerceAtLeast(0)},
      enabled=current>0,modifier=Modifier.heightIn(min=48.dp).testTag("authored-technique-previous")){
      Text("Назад")
     }
     OutlinedButton(onClick={page=(current+1).coerceAtMost(frames.lastIndex)},
      enabled=current<frames.lastIndex,
      modifier=Modifier.heightIn(min=48.dp).testTag("authored-technique-next")){
      Text("Далее")
     }
    }
   }
  }
 }
}
