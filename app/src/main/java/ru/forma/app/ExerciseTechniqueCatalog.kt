package ru.forma.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.forma.domain.*

/**
 * Offline searchable and accessible technique guide. No external videos,
 * microphone, remote API, user accounts or exercise-media rights assumed.
 * Search/filter/disclosure state is only transient UI state per profile.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExerciseTechniqueCatalog(profileId:String,available:Set<Equipment>){
 var query by rememberSaveable(profileId){mutableStateOf("")}
 var muscleName by rememberSaveable(profileId){mutableStateOf("")}
 var allEquipment by rememberSaveable(profileId){mutableStateOf(false)}
 var openId by rememberSaveable(profileId){mutableStateOf("")}
 var shown by rememberSaveable(profileId){mutableIntStateOf(8)}
 val muscle=Muscle.entries.firstOrNull{it.name==muscleName}
 val matches=findTechniqueExercises(
  exerciseCatalog,available,includeUnavailable=allEquipment,muscle=muscle,query=query
 )
 fun resetDisclosure(){openId="";shown=8}
 Panel("Техника упражнений"){
  Text("Текстовые подсказки доступны без интернета. Это не видеодемонстрация и не оценка техники. При боли остановите упражнение.")
  OutlinedTextField(
   value=query,
   onValueChange={query=it.take(120);resetDisclosure()},
   label={Text("Найти упражнение")},
   placeholder={Text("Название, мышцы, снаряд или ошибка")},
   singleLine=true,
   modifier=Modifier.fillMaxWidth().testTag("technique-search")
  )
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
   FilterChip(selected=!allEquipment,onClick={allEquipment=false;resetDisclosure()},
    label={Text("Мои снаряды")},modifier=Modifier.heightIn(min=48.dp))
   FilterChip(selected=allEquipment,onClick={allEquipment=true;resetDisclosure()},
    label={Text("Весь каталог")},modifier=Modifier.heightIn(min=48.dp))
  }
  Text("Мышечная группа",style=MaterialTheme.typography.titleSmall)
  FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
   FilterChip(selected=muscleName.isEmpty(),onClick={muscleName="";resetDisclosure()},
    label={Text("Все мышцы")},modifier=Modifier.heightIn(min=48.dp))
   Muscle.entries.forEach{item->
    FilterChip(selected=muscleName==item.name,
     onClick={muscleName=item.name;resetDisclosure()},
     label={Text(item.label)},modifier=Modifier.heightIn(min=48.dp))
   }
  }
  if(query.isNotEmpty()||muscleName.isNotEmpty()){
   TextButton(onClick={query="";muscleName="";resetDisclosure()},
    modifier=Modifier.heightIn(min=48.dp)){Text("Очистить поиск и группу")}
  }
  Text("Найдено: "+matches.size+" из "+exerciseCatalog.size+". Показано: "+minOf(shown,matches.size),
   style=MaterialTheme.typography.bodySmall)
  if(matches.isEmpty()){
   Text("По выбранным условиям упражнений нет. Очистите поиск или выберите «Весь каталог».")
  }
  matches.take(shown).forEach{exercise->
   val expanded=openId==exercise.id
   OutlinedButton(
    onClick={openId=if(expanded)"" else exercise.id},
    modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("technique-item-"+exercise.id)
   ){
    Column(modifier=Modifier.fillMaxWidth()){
     Text(exercise.name,style=MaterialTheme.typography.titleSmall)
     Text(exercise.muscle.label+" · "+exercise.equipment.label+
      if(expanded)" · Скрыть инструкцию" else " · Показать инструкцию",
      style=MaterialTheme.typography.bodySmall)
    }
   }
   if(expanded){
    Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),
     verticalArrangement=Arrangement.spacedBy(8.dp)){
     Text("Как выполнять",style=MaterialTheme.typography.titleSmall)
     Text(exercise.cue)
     Text("Частые ошибки",style=MaterialTheme.typography.titleSmall)
     Text(exercise.mistakes.ifBlank{"Подробные ошибки для упражнения пока не проверены."})
     if(exercise.equipment !in available)Text(
      "Этот снаряд не выбран для текущего места тренировки. Инструкция доступна для ознакомления.",
      color=MaterialTheme.colorScheme.onSurfaceVariant)
     Text("При боли или головокружении прекратите упражнение. Не увеличивайте нагрузку через дискомфорт.",
      color=MaterialTheme.colorScheme.onSurfaceVariant,
      style=MaterialTheme.typography.bodySmall)
    }
   }
  }
  if(shown<matches.size)OutlinedButton(
   onClick={shown=(shown+8).coerceAtMost(matches.size)},
   modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("technique-more")
  ){Text("Показать ещё упражнения")}
 }
}
