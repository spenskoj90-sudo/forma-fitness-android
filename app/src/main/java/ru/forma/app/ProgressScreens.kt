package ru.forma.app
import androidx.compose.foundation.Canvas
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import ru.forma.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
fun weekStart(now:Long):Long{val d=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate();return d.minusDays(d.dayOfWeek.value-1L).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()}
fun dateLabel(ms:Long)=Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
@Composable fun HistoryChart(values:List<Double>) {
 val color=MaterialTheme.colorScheme.primary
 if(values.size<2) Text("Для графика нужно два сохранённых значения.")
 else Canvas(Modifier.fillMaxWidth().height(130.dp)) {
  val min=values.minOrNull()!!
  val span=(values.maxOrNull()!!-min).coerceAtLeast(1.0)
  fun point(i:Int):Offset {
   return Offset(8f+i*(size.width-16f)/(values.size-1),size.height-8f-((values[i]-min)/span*(size.height-16f)).toFloat())
  }
  for(i in 1..values.lastIndex) drawLine(color,point(i-1),point(i),4f)
  values.indices.forEach { drawCircle(color,5f,point(it)) }
 }
}
@Composable fun BodyScreen(p:Profile,vm:FormaViewModel){var values by remember(p.id){mutableStateOf(measurementLabels.keys.associateWith{""})};var editing by remember(p.id){mutableStateOf<Measurement?>(null)};var selected by remember(p.id){mutableStateOf("weight")};Page("Моё тело","Замеры в одинаковых условиях помогают видеть изменения."){
 Panel("Манекен · параметры тела"){BodyMannequin(p)}
 Panel(if(editing==null)"Новый замер"else "Исправить замер"){measurementLabels.forEach{(key,label)->NumericField(label,values[key].orEmpty()){values=values+(key to it)}};val parsed=values.filterValues{it.isNotBlank()}.mapValues{number(it.value)?:-1.0};Action("Сохранить замеры",{vm.measurement(parsed,editing?.id,editing?.dateMs?:System.currentTimeMillis()){values=measurementLabels.keys.associateWith{""};editing=null}},Measurement(dateMs=0,values=parsed).valid())}
 Panel("Динамика"){measurementLabels.forEach{(key,label)->TextButton({selected=key}){Text(if(selected==key)"✓ $label"else label)}};val samples=p.measurements.sortedBy{it.dateMs}.mapNotNull{it.values[selected]};Text(measurementLabels[selected].orEmpty());HistoryChart(samples);if(samples.isNotEmpty())Text("Первый: ${samples.first()} · последний: ${samples.last()}")}
 p.measurements.sortedByDescending{it.dateMs}.forEach{m->Panel(dateLabel(m.dateMs)){m.values.forEach{(key,value)->Text("${measurementLabels[key]}: $value")};TextButton({editing=m;values=measurementLabels.keys.associateWith{m.values[it]?.toString().orEmpty()}}){Text("Исправить запись")}}}
}}
@Composable fun ProgressScreen(p:Profile){val now=System.currentTimeMillis();val month=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();Page("Прогресс","Только сохранённые результаты этого профиля."){
 val context=LocalContext.current
 Panel("Еженедельный отчёт"){
  Text("Сводка составляется только на телефоне для текущего профиля. Отправка — исключительно по вашему выбору.")
  Action("Поделиться отчётом",{
   val send=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,weeklyReport(p,now))}
   context.startActivity(Intent.createChooser(send,"Отчёт FORMA"))
  })
 }
 listOf("Неделя" to weekStart(now),"Месяц" to month,"Всё время" to 0L).forEach{(label,start)->val records=p.history.filter{it.finishedMs>=start};val sets=records.flatMap{it.sets};Panel(label){Text("Завершено: ${records.count{it.status==Phase.DONE}} · остановлено: ${records.count{it.status==Phase.STOPPED}}");Text("Подходы: ${sets.size} · повторения: ${sets.sumOf{it.reps}}");Text("Нагрузка: ${"%.1f".format(sets.sumOf{it.weight*it.reps})} кг·повт (на гантель)");Text("Кардио: ${records.filter{it.day.cardio!=null&&it.status==Phase.DONE}.sumOf{it.day.durationSec}/60} минут")}}
 p.history.flatMap{it.sets}.groupBy{it.exerciseId}.forEach{(id,sets)->Panel(exerciseCatalog.firstOrNull{it.id==id}?.name?:id){Text("Максимум: ${sets.maxOf{it.weight}} кг · ${sets.maxOf{it.reps}} повторений");HistoryChart(sets.sortedBy{it.timeMs}.map{it.weight})}}
 if(p.history.isEmpty())Text("История появится после первой сохранённой тренировки.")
 p.history.sortedByDescending{it.finishedMs}.forEach{w->Panel(w.day.name){Text(dateLabel(w.finishedMs));Text("${if(w.status==Phase.DONE)"Завершено"else"Остановлено"} · ${w.sets.size} подходов");w.sets.forEach{s->Text("${exerciseCatalog.firstOrNull{it.id==s.exerciseId}?.name?:s.exerciseId}: ${s.weight} кг × ${s.reps} · сложность ${s.effort}")}}}
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
