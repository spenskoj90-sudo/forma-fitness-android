package ru.forma.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.forma.domain.*

/** Entire subtree is keyed: no frame of the old exercise's player/captions survives replacement. */
@Composable internal fun ExerciseTechnique(exercise:Exercise,source:TechniqueMediaSource?=null){
 val assets=LocalContext.current.applicationContext.assets
 val localSource=remember(assets){AssetTechniqueMediaSource(assets)}
 key(exercise.id,exercise.equipment){
  var open by remember{mutableStateOf(false)}
  Text(exercise.cue)
  Text("Частые ошибки: ${exercise.mistakes.ifBlank{"Пока не описаны."}}")
  TextButton({open=!open},Modifier.fillMaxWidth().heightIn(min=48.dp)){
   Text(if(open)"Скрыть демонстрацию" else "Показать демонстрацию")
  }
  if(open){
   val loader=source?:localSource
   var result by remember(loader){mutableStateOf<TechniqueMediaResult?>(null)}
   LaunchedEffect(loader){result=loader.load(exercise)}
   when(val value=result){
    null->Text("Проверяем локальный материал…")
    TechniqueMediaResult.Unavailable->Text("Подтверждённая демонстрация пока недоступна.")
    TechniqueMediaResult.Rejected->Text("Материал не прошёл проверку. Доступна текстовая инструкция.")
    TechniqueMediaResult.Unsupported->Text("Формат демонстрации не поддерживается или файл повреждён.")
    is TechniqueMediaResult.Ready->{
     var failed by remember(value.media){mutableStateOf(false)}
     if(failed)Text("Не удалось воспроизвести демонстрацию. Доступна текстовая инструкция.")
     else TechniqueVideo(value.media,exercise.name){failed=true}
     val evidence=value.media.evidence;val c=evidence.caption
     listOf("Подготовка" to c.setup,"Исходное положение" to c.start,"Движение" to c.motion,
      "Завершение" to c.finish,"Амплитуда" to c.rangeOfMotion,"Дыхание" to c.breathing,"Ошибки" to c.errors)
      .forEach{(label,caption)->Text(label,Modifier.semantics{heading()},style=MaterialTheme.typography.titleSmall);Text(caption)}
     Text("Источник: ${evidence.provenance}",style=MaterialTheme.typography.bodySmall)
     Text("Лицензия: ${evidence.license}",style=MaterialTheme.typography.bodySmall)
     Text("Проверка техники: ${evidence.reviewer} · ${evidence.reviewReference}",style=MaterialTheme.typography.bodySmall)
    }
   }
  }
 }
}
