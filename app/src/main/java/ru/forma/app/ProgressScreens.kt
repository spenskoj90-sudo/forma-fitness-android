package ru.forma.app
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.forma.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
fun weekStart(now:Long):Long{val d=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate();return d.minusDays(d.dayOfWeek.value-1L).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()}
fun dateLabel(ms:Long)=Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
/** Persisted values are plotted in time order; history is never rewritten. */
data class ChartSample(val timeMs:Long,val value:Double)

fun chartSummary(samples:List<ChartSample>,label:String,unit:String):String{
 val valid=samples.filter{it.value.isFinite()}.sortedBy{it.timeMs}
 if(valid.isEmpty())return "${label}: нет сохранённых значений."
 val start=valid.first()
 val end=valid.last()
 return buildString{
  append("${label}: ${valid.size} значений, ${unit}. ")
  append("Первое ${dateLabel(start.timeMs)} — ${start.value}; ")
  append("последнее ${dateLabel(end.timeMs)} — ${end.value}. ")
  append("Минимум ${valid.minOf{it.value}}, максимум ${valid.maxOf{it.value}}.")
 }
}

@Composable fun HistoryChart(samples:List<ChartSample>,label:String,unit:String) {
 val color=MaterialTheme.colorScheme.primary
 val axis=MaterialTheme.colorScheme.onSurfaceVariant
 val ordered=samples.filter{it.value.isFinite()}.sortedBy{it.timeMs}
 val summary=chartSummary(ordered,label,unit)
 if(ordered.size<2) {
  Text(if(ordered.isEmpty())"Нет сохранённых значений." else
   "Для графика нужно два сохранённых значения. Первое: ${dateLabel(ordered[0].timeMs)} · ${ordered[0].value} ${unit}")
 } else {
  val min=ordered.minOf{it.value}
  val max=ordered.maxOf{it.value}
  val span=(max-min).coerceAtLeast(1.0)
  Column(Modifier.fillMaxWidth().clearAndSetSemantics {
   // One complete dated spoken summary, rather than duplicate axis labels.
   contentDescription=summary
  }) {
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
    Text("Макс. ${max} ${unit}",style=MaterialTheme.typography.labelSmall,color=axis)
    Text("Значений: ${ordered.size}",style=MaterialTheme.typography.labelSmall,color=axis)
   }
   Canvas(Modifier.fillMaxWidth().height(135.dp)) {
    val left=12f
    val right=size.width-12f
    val top=12f
    val bottom=size.height-12f
    drawLine(axis.copy(alpha=0.65f),Offset(left,top),Offset(left,bottom),1.5f)
    drawLine(axis.copy(alpha=0.65f),Offset(left,bottom),Offset(right,bottom),1.5f)
    val from=ordered.first().timeMs
    val timeSpan=ordered.last().timeMs-from
    fun point(i:Int):Offset {
     val progress=if(timeSpan>0L) ((ordered[i].timeMs-from).toDouble()/timeSpan).toFloat()
      else i.toFloat()/ordered.lastIndex
     return Offset(left+(right-left)*progress,
      bottom-((ordered[i].value-min)/span*(bottom-top)).toFloat())
    }
    for(i in 1..ordered.lastIndex)drawLine(color,point(i-1),point(i),3f)
    ordered.indices.forEach{drawCircle(color,5f,point(it))}
   }
   Text("Мин. ${min} ${unit}",style=MaterialTheme.typography.labelSmall,color=axis)
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
    Text(dateLabel(ordered.first().timeMs).substringBefore(" "),style=MaterialTheme.typography.labelSmall,color=axis)
    Text(dateLabel(ordered.last().timeMs).substringBefore(" "),style=MaterialTheme.typography.labelSmall,color=axis)
   }
  }
 }
}
// Save only a small input map and a stable record ID. Never cache complete
// per-user measurement history in the Activity saved-state Bundle.
internal val measurementDraftSaver=Saver<Map<String,String>,String>(
 save={Json.encodeToString(it)},
 restore={Json.decodeFromString<Map<String,String>>(it)}
)
@Composable fun BodyScreen(p:Profile,vm:FormaViewModel)=BodyScreen(p){values,id,dateMs,draftId,done->
 vm.measurement(values,id,dateMs,draftId,done)
}
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun BodyScreen(
 p:Profile,
 onMeasurement:(Map<String,Double>,String?,Long,String,()->Unit)->Unit
){
 var values by rememberSaveable(p.id,stateSaver=measurementDraftSaver){
  mutableStateOf(measurementLabels.keys.associateWith{""})
 }
 var editingId by rememberSaveable(p.id){mutableStateOf<String?>(null)}
 // One draft identity and timestamp per unsaved entry. Double-taps and
 // retries cannot invent multiple persisted measurements for one entry.
 var draftId by rememberSaveable(p.id){mutableStateOf(java.util.UUID.randomUUID().toString())}
 var draftDateMs by rememberSaveable(p.id){mutableLongStateOf(System.currentTimeMillis())}
 val submittingDraftId=draftId
 var selected by rememberSaveable(p.id){mutableStateOf("weight")}
 var shownMeasurements by rememberSaveable(p.id){mutableIntStateOf(10)}
 val measurementEditorRequester=remember{BringIntoViewRequester()}
 LaunchedEffect(editingId){if(editingId!=null)measurementEditorRequester.bringIntoView()}
 val orderedMeasurements=remember(p.measurements){p.measurements.sortedWith(compareByDescending<Measurement>{it.dateMs}.thenByDescending{it.id})}
 val editing=p.measurements.firstOrNull{it.id==editingId}
 val missingEditedRecord=editingId!=null&&editing==null
 Page("Моё тело","Замеры в одинаковых условиях помогают видеть изменения."){
  Panel("Манекен · параметры тела"){BodyMannequin(p)}
  Box(Modifier.fillMaxWidth().bringIntoViewRequester(measurementEditorRequester)){
  Panel(if(editingId==null)"Новый замер" else "Исправить замер"){
   if(missingEditedRecord)Text("Исходная запись больше недоступна. Отмените исправление, чтобы не создать случайный дубликат.")
   measurementLabels.forEach{(key,label)->
    NumericField(label,values[key].orEmpty(),Modifier.testTag("body-measure-$key")){values=values+(key to it)}
   }
   val parsed=values.filterValues{it.isNotBlank()}.mapValues{number(it.value)?:-1.0}
   Action("Сохранить замеры",{
    onMeasurement(parsed,editingId,editing?.dateMs?:draftDateMs,submittingDraftId){
     values=measurementLabels.keys.associateWith{""};editingId=null
     draftId=java.util.UUID.randomUUID().toString()
     draftDateMs=System.currentTimeMillis()
    }
   },!missingEditedRecord&&Measurement(dateMs=0,values=parsed).valid())
   if(editingId!=null)TextButton({
    editingId=null;values=measurementLabels.keys.associateWith{""}
    draftId=java.util.UUID.randomUUID().toString()
    draftDateMs=System.currentTimeMillis()
   }){Text("Отменить исправление")}
  }
  }
  Panel("Динамика"){
   measurementLabels.forEach{(key,label)->TextButton({selected=key}){
    Text(if(selected==key)"✓ $label" else label)
   }}
   val samples=p.measurements.mapNotNull{m->
    m.values[selected]?.let{ChartSample(m.dateMs,it)}
   }.sortedBy{it.timeMs}
   val unit=if(selected=="weight")"кг" else "см"
   Text(measurementLabels[selected].orEmpty())
   HistoryChart(samples,measurementLabels[selected].orEmpty(),unit)
   if(samples.isNotEmpty())Text("Первый: ${samples.first().value} $unit · последний: ${samples.last().value} $unit")
  }
  Panel("История замеров"){
   if(orderedMeasurements.isEmpty())Text("Сохранённых замеров пока нет.")
   else {
    Text("Показано ${minOf(shownMeasurements,orderedMeasurements.size)} из ${orderedMeasurements.size} замеров. Все записи хранятся локально и не удаляются при сворачивании списка.",
     style=MaterialTheme.typography.bodySmall)
    orderedMeasurements.take(shownMeasurements).forEach{m->
     Panel(dateLabel(m.dateMs)){
      m.values.forEach{(key,value)->Text("${measurementLabels[key]}: $value")}
      TextButton({
       editingId=m.id
       values=measurementLabels.keys.associateWith{m.values[it]?.toString().orEmpty()}
      },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Исправить запись")}
     }
    }
    if(shownMeasurements<orderedMeasurements.size)OutlinedButton({
     shownMeasurements=(shownMeasurements+10).coerceAtMost(orderedMeasurements.size)
    },modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("body-measurements-more")){
     Text("Показать ещё замеры")
    }
   }
  }
 }
}
@Composable fun ProgressScreen(p:Profile){
 var visibleCount by rememberSaveable(p.id){mutableIntStateOf(10)}
 var chartsExpanded by rememberSaveable(p.id){mutableStateOf(false)}
 // Transient in-memory preview: no Bundle/DataStore/Room persistence or cross-profile carryover.
 var reportPreview by remember(p.id){mutableStateOf<String?>(null)}
 var reportError by remember(p.id){mutableStateOf<String?>(null)}
 val visibleHistory=remember(p.history,visibleCount){recentWorkoutHistory(p.history,visibleCount)}
 val now=System.currentTimeMillis();val month=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();Page("Прогресс","Только сохранённые результаты этого профиля."){
 val context=LocalContext.current
 // After "Save and open history", confirm the durable newest workout immediately.
 visibleHistory.firstOrNull()?.let { latest ->
  Panel("Последняя сохранённая тренировка"){
   Text("${latest.day.name} · ${dateLabel(latest.finishedMs)}",style=MaterialTheme.typography.titleMedium)
   Text(if(latest.status==Phase.DONE)"Завершена"else"Остановлена")
   Text("Записанные подходы: ${latest.sets.size} · активное время: ${latest.activeSeconds/60} мин ${latest.activeSeconds%60} сек")
  }
 }
 Text("История тренировок",style=MaterialTheme.typography.titleLarge)
 if(p.history.isEmpty())Text("История появится после первой сохранённой тренировки.")
 visibleHistory.forEach{w->Panel(w.day.name){
  Text(dateLabel(w.finishedMs))
  Text("${if(w.status==Phase.DONE)"Завершено"else"Остановлено"} · ${w.sets.size} подходов")
  w.sets.forEach{s->
   val recordedExercise=w.day.exercises.firstOrNull{it.exercise.id==s.exerciseId}?.exercise
   Text("${recordedExercise?.name?:s.exerciseId}: ${recordedExerciseLoadDisplay(s,recordedExercise)} × ${s.reps} · сложность ${s.effort}")
  }
 }}
 if(p.history.size>10){
  Text("Показано ${visibleHistory.size} из ${p.history.size} сохранённых тренировок.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  if(visibleHistory.size<p.history.size)OutlinedButton({visibleCount=(visibleCount+10).coerceAtMost(p.history.size)},Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("Показать ещё тренировки")}
 }
 Panel("Еженедельный отчёт"){
  Text("Отчёт содержит имя профиля, цель и результаты тренировок. Перед передачей в другое приложение его можно просмотреть и отменить.")
  Action("Поделиться отчётом",{
   reportError=null
   reportPreview=weeklyReport(p,now)
  })
  if(reportError!=null)Text(reportError!!,color=MaterialTheme.colorScheme.error)
 }
 // The preview must match the bytes sent; switching profile resets this state.
 val preview=reportPreview
 if(preview!=null)AlertDialog(
  onDismissRequest={reportPreview=null},
  title={Text("Поделиться недельным отчётом?")},
  text={
   Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState()),
    verticalArrangement=Arrangement.spacedBy(10.dp)){
    Text("Ниже именно те данные, которые будут переданы. Проверьте получателя в системном меню. Ничего не отправляется без вашего подтверждения.")
    Text(preview,style=MaterialTheme.typography.bodySmall)
   }
  },
  confirmButton={TextButton(onClick={
   reportPreview=null
   val intent=Intent(Intent.ACTION_SEND).apply{
    type="text/plain"
    putExtra(Intent.EXTRA_TEXT,preview)
   }
   runCatching{context.startActivity(Intent.createChooser(intent,"Отчёт FORMA"))}
    .onFailure{reportError="На устройстве не удалось открыть выбор приложения для отправки. Отчёт остаётся локальным."}
  }){Text("Открыть выбор приложения")}},
  dismissButton={TextButton(onClick={reportPreview=null}){Text("Отмена")}}
 )
 listOf("Неделя" to weekStart(now),"Месяц" to month,"Всё время" to 0L).forEach{(label,start)->val records=p.history.filter{it.finishedMs>=start};val sets=records.flatMap{it.sets};Panel(label){Text("Завершено: ${records.count{it.status==Phase.DONE}} · остановлено: ${records.count{it.status==Phase.STOPPED}}");Text("Подходы: ${sets.size} · повторения: ${sets.sumOf{it.reps}}");val loadByExercise=recordedLoadVolumeByExercise(records);Text("Объём по упражнениям: кг × повторения. Для пары гантелей указан вес одной, без удвоения. Разные упражнения и тренажёры не суммируются.")
 if(loadByExercise.isEmpty())Text("Подходы без записанного дополнительного веса.")
 else loadByExercise.forEach{entry->
  Text("${entry.exercise.name}: ${"%.1f".format(entry.kgRepetitions)} кг·повт (${recordedExerciseLoadUnit(entry.exercise)})")
 };Text("Кардио: ${records.filter{it.day.cardio!=null&&it.status==Phase.DONE}.sumOf{it.day.durationSec}/60} минут")}}

 if(p.history.any{it.sets.isNotEmpty()}){
  OutlinedButton({chartsExpanded=!chartsExpanded},Modifier.fillMaxWidth().heightIn(min=52.dp)){
   Text(if(chartsExpanded)"Скрыть динамику упражнений"else"Динамика упражнений")
  }
  if(chartsExpanded){
 p.history.flatMap{it.sets}.groupBy{it.exerciseId}.forEach{(id,sets)->Panel(exerciseCatalog.firstOrNull{it.id==id}?.name?:id){val savedEquipment=p.history.asSequence().flatMap{it.day.exercises.asSequence()}
  .firstOrNull{it.exercise.id==id}?.exercise?.equipment
 if(savedEquipment!=null&&!recordsExternalKilograms(savedEquipment)){
  Text("${if(savedEquipment==Equipment.RESISTANCE_BAND)"Резинка (сопротивление без кг)" else "Собственный вес"} · максимум: ${sets.maxOf{it.reps}} повторений")
  HistoryChart(sets.map{ChartSample(it.timeMs,it.reps.toDouble())},exerciseCatalog.firstOrNull{it.id==id}?.name?:id,"повторений")
 }else{
  Text("Максимум: ${sets.maxOf{it.weight}} ${p.history.asSequence().flatMap{it.day.exercises.asSequence()}.firstOrNull{it.exercise.id==id}?.exercise?.let(::recordedExerciseLoadUnit)?:"кг (единица не определена)"} · ${sets.maxOf{it.reps}} повторений")
  HistoryChart(sets.map{ChartSample(it.timeMs,it.weight)},exerciseCatalog.firstOrNull{it.id==id}?.name?:id,p.history.asSequence().flatMap{it.day.exercises.asSequence()}.firstOrNull{it.exercise.id==id}?.exercise?.let(::recordedExerciseLoadUnit)?:"кг (единица не определена)")
 }}}
  }
 }
}}

/** Local-only export: no automatic server upload of personal fitness data. */
fun weeklyReport(p:Profile,now:Long=System.currentTimeMillis()):String{
 val records=p.history.filter{it.finishedMs>=weekStart(now)&&it.finishedMs<=now}
 val sets=records.flatMap{it.sets}
 val cardio=records.filter{it.day.cardio!=null&&it.status==Phase.DONE}.sumOf{it.activeSeconds}/60
 return buildString{
  appendLine("FORMA · недельный отчёт")
  appendLine("Профиль: "+p.name)
  appendLine("Период: "+dateLabel(weekStart(now))+" — "+dateLabel(now))
  appendLine("Завершено: "+records.count{it.status==Phase.DONE})
  appendLine("Остановлено: "+records.count{it.status==Phase.STOPPED})
  appendLine("Подходы: "+sets.size+"; повторения: "+sets.sumOf{it.reps})
  appendLine("Кардио: "+cardio+" минут активного времени")
  appendLine("Цель: "+p.primary.label)
  append("Сводка локальная; не является медицинским заключением.")
 }
}
