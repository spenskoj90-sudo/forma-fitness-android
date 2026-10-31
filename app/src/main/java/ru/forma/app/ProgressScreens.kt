package ru.forma.app
import androidx.compose.foundation.Canvas
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
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
  Column(Modifier.fillMaxWidth().semantics {
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
@Composable fun BodyScreen(p:Profile,vm:FormaViewModel){var values by remember(p.id){mutableStateOf(measurementLabels.keys.associateWith{""})};var editing by remember(p.id){mutableStateOf<Measurement?>(null)};var selected by remember(p.id){mutableStateOf("weight")};Page("Моё тело","Замеры в одинаковых условиях помогают видеть изменения."){
 Panel("Манекен · параметры тела"){BodyMannequin(p)}
 Panel(if(editing==null)"Новый замер"else "Исправить замер"){measurementLabels.forEach{(key,label)->NumericField(label,values[key].orEmpty()){values=values+(key to it)}};val parsed=values.filterValues{it.isNotBlank()}.mapValues{number(it.value)?:-1.0};Action("Сохранить замеры",{vm.measurement(parsed,editing?.id,editing?.dateMs?:System.currentTimeMillis()){values=measurementLabels.keys.associateWith{""};editing=null}},Measurement(dateMs=0,values=parsed).valid())}
 Panel("Динамика"){measurementLabels.forEach{(key,label)->TextButton({selected=key}){Text(if(selected==key)"✓ $label"else label)}};val samples=p.measurements.mapNotNull{m->m.values[selected]?.let{ChartSample(m.dateMs,it)}}.sortedBy{it.timeMs};val unit=if(selected=="weight")"кг"else"см";Text(measurementLabels[selected].orEmpty());HistoryChart(samples,measurementLabels[selected].orEmpty(),unit);if(samples.isNotEmpty())Text("Первый: ${samples.first().value} $unit · последний: ${samples.last().value} $unit")}
 p.measurements.sortedByDescending{it.dateMs}.forEach{m->Panel(dateLabel(m.dateMs)){m.values.forEach{(key,value)->Text("${measurementLabels[key]}: $value")};TextButton({editing=m;values=measurementLabels.keys.associateWith{m.values[it]?.toString().orEmpty()}}){Text("Исправить запись")}}}
}}
@Composable fun ProgressScreen(p:Profile){
 var visibleCount by rememberSaveable(p.id){mutableIntStateOf(10)}
 var chartsExpanded by rememberSaveable(p.id){mutableStateOf(false)}
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
   val recordedEquipment=w.day.exercises.firstOrNull{it.exercise.id==s.exerciseId}?.exercise?.equipment
   Text("${exerciseCatalog.firstOrNull{it.id==s.exerciseId}?.name?:s.exerciseId}: ${recordedSetLoadDisplay(s,recordedEquipment)} × ${s.reps} · сложность ${s.effort}")
  }
 }}
 if(p.history.size>10){
  Text("Показано ${visibleHistory.size} из ${p.history.size} сохранённых тренировок.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  if(visibleHistory.size<p.history.size)OutlinedButton({visibleCount=(visibleCount+10).coerceAtMost(p.history.size)},Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("Показать ещё тренировки")}
 }
 Panel("Еженедельный отчёт"){
  Text("Сводка составляется только на телефоне для текущего профиля. Отправка — исключительно по вашему выбору.")
  Action("Поделиться отчётом",{
   val send=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,weeklyReport(p,now))}
   context.startActivity(Intent.createChooser(send,"Отчёт FORMA"))
  })
 }
 listOf("Неделя" to weekStart(now),"Месяц" to month,"Всё время" to 0L).forEach{(label,start)->val records=p.history.filter{it.finishedMs>=start};val sets=records.flatMap{it.sets};Panel(label){Text("Завершено: ${records.count{it.status==Phase.DONE}} · остановлено: ${records.count{it.status==Phase.STOPPED}}");Text("Подходы: ${sets.size} · повторения: ${sets.sumOf{it.reps}}");val loadByGear=recordedLoadVolumeByEquipment(records);Text("Записанный объём: кг × повторения; значения разных снарядов не суммируются.")
 if(loadByGear.isEmpty())Text("Подходы без записанного дополнительного веса.")
 else loadByGear.forEach{(gear,total)->
  Text("${gear.label}: ${"%.1f".format(total)} кг·повт (${recordedLoadUnit(gear)})")
 };Text("Кардио: ${records.filter{it.day.cardio!=null&&it.status==Phase.DONE}.sumOf{it.day.durationSec}/60} минут")}}

 if(p.history.any{it.sets.isNotEmpty()}){
  OutlinedButton({chartsExpanded=!chartsExpanded},Modifier.fillMaxWidth().heightIn(min=52.dp)){
   Text(if(chartsExpanded)"Скрыть динамику упражнений"else"Динамика упражнений")
  }
  if(chartsExpanded){
 p.history.flatMap{it.sets}.groupBy{it.exerciseId}.forEach{(id,sets)->Panel(exerciseCatalog.firstOrNull{it.id==id}?.name?:id){val savedEquipment=p.history.asSequence().flatMap{it.day.exercises.asSequence()}
  .firstOrNull{it.exercise.id==id}?.exercise?.equipment
 if(savedEquipment==Equipment.BODYWEIGHT){
  Text("Собственный вес · максимум: ${sets.maxOf{it.reps}} повторений")
  HistoryChart(sets.map{ChartSample(it.timeMs,it.reps.toDouble())},exerciseCatalog.firstOrNull{it.id==id}?.name?:id,"повторений")
 }else{
  Text("Максимум: ${sets.maxOf{it.weight}} ${savedEquipment?.let(::recordedLoadUnit)?:"кг (единица не определена)"} · ${sets.maxOf{it.reps}} повторений")
  HistoryChart(sets.map{ChartSample(it.timeMs,it.weight)},exerciseCatalog.firstOrNull{it.id==id}?.name?:id,savedEquipment?.let(::recordedLoadUnit)?:"кг (единица не определена)")
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
