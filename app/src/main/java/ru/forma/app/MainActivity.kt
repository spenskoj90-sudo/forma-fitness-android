package ru.forma.app
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.forma.domain.*
class MainActivity:ComponentActivity(){
 private val vm:FormaViewModel by viewModels()
 private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);if(Build.VERSION.SDK_INT>=33)permission.launch(Manifest.permission.POST_NOTIFICATIONS);setContent{FormaRoot(vm)}}
}
val dayNames=listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс")
@Composable fun FormaRoot(vm:FormaViewModel){
 val p by vm.current.collectAsState();val profiles by vm.profiles.collectAsState();val settings by vm.prefs.collectAsState();val error by vm.error.collectAsState()
 var tab by rememberSaveable{mutableIntStateOf(0)};var editing by rememberSaveable{mutableStateOf(false)};var creating by rememberSaveable{mutableStateOf(false)}
 val dark=settings.dark?:isSystemInDarkTheme()
 val colors=if(dark)darkColorScheme(primary=Color(0xFFC8F88A),background=Color(0xFF101B18),surface=Color(0xFF182720))else lightColorScheme(primary=Color(0xFF176A50),background=Color(0xFFF5F7F2),surface=Color.White)
 MaterialTheme(colorScheme=colors,shapes=Shapes(medium=RoundedCornerShape(20.dp),large=RoundedCornerShape(28.dp))){
  val titles=listOf("Главная","Тренировки","Моё тело","Прогресс","Профиль");val icons=listOf(Icons.Outlined.Home,Icons.Outlined.FitnessCenter,Icons.Outlined.Straighten,Icons.Outlined.TrendingUp,Icons.Outlined.Person)
  Scaffold(containerColor=colors.background,bottomBar={if(p!=null&&p?.active==null&&!editing&&!creating)NavigationBar{titles.forEachIndexed{i,t->NavigationBarItem(tab==i,{tab=i},icon={Icon(icons[i],t)},label={Text(t,style=MaterialTheme.typography.labelSmall)})}}}){padding->
   Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp)){
    Row(Modifier.fillMaxWidth().padding(vertical=16.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("FORMA",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Black);p?.let{Text(it.name,color=colors.primary)}}
    if(error!=null)Panel("Не удалось выполнить действие"){Text(error!!);TextButton({vm.error.value=null}){Text("Понятно")}}
    when{creating||editing||profiles.isEmpty()->ProfileEditor(if(creating||profiles.isEmpty())null else p,{vm.saveProfile(it);creating=false;editing=false},{creating=false;editing=false},profiles.isNotEmpty())
     p?.active!=null->Runner(p!!.active!!,vm)
     p==null->Text("Загрузка…")
     else->when(tab){0->Home(p!!,{tab=1},{tab=4});1->TrainingScreen(p!!,vm);2->BodyScreen(p!!,vm);3->ProgressScreen(p!!);else->ProfileScreen(p!!,profiles,settings,vm,{editing=true},{creating=true})}
    }
   }
  }
 }
}
@Composable fun Page(title:String,subtitle:String="",content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){Text(title,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold);if(subtitle.isNotBlank())Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant);content();Spacer(Modifier.height(24.dp))}}
@Composable fun Panel(title:String,content:@Composable ColumnScope.()->Unit){Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);content()}}}
@Composable fun Action(label:String,onClick:()->Unit,enabled:Boolean=true){Button(onClick,Modifier.fillMaxWidth().heightIn(min=52.dp),enabled=enabled){Text(label)}}
fun number(value:String)=value.replace(',','.').toDoubleOrNull()?.takeIf{it.isFinite()}
@Composable fun NumericField(label:String,value:String,onChange:(String)->Unit){OutlinedTextField(value,onChange,Modifier.fillMaxWidth(),label={Text(label)},singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal))}
@Composable fun Home(p:Profile,onTrain:()->Unit,onProfile:()->Unit){
 val weekly=p.history.count{it.status==Phase.DONE&&it.finishedMs>=weekStart(System.currentTimeMillis())}
 Page("Твоя форма.","Последовательность важнее рекорда."){
  Panel("На этой неделе"){Text("$weekly завершённых занятий",style=MaterialTheme.typography.headlineSmall);Text("План: ${generateProgram(p).size} занятий · ${p.primary.label}");LinearProgressIndicator(progress={if(p.days.isEmpty())0f else(weekly.toFloat()/p.days.size).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())}
  if(p.limitations.isNotBlank()||p.recovery<=1)Panel("Сначала восстановление"){Text("Автоматическая программа приостановлена. При боли остановитесь. Уточните допустимые нагрузки со специалистом; обновите самочувствие, когда занятия снова допустимы.");Action("Открыть профиль",onProfile)}
  else Panel("Программа под тебя"){Text("${p.experience.label} · ${p.equipment.joinToString{it.label}}");Action("К тренировкам",onTrain)}
  Panel("Без интернета"){Text("Данные хранятся на этом телефоне. Для голоса нужна русская офлайн-модель Android TTS. Синхронизация и резервное копирование пока не подключены.")}
 }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun ProfileEditor(existing:Profile?,save:(Profile)->Unit,cancel:()->Unit,canCancel:Boolean){
 var p by remember(existing?.id){mutableStateOf(existing?:Profile())};var name by remember{mutableStateOf(p.name)};var age by remember{mutableStateOf(p.age.toString())};var height by remember{mutableStateOf(p.heightCm.toString())};var weight by remember{mutableStateOf(p.weightKg.toString())}
 Page(if(existing==null)"Начнём с тебя"else"Твой профиль","Для взрослых. История каждого пользователя изолирована."){
  OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text("Имя")},singleLine=true);NumericField("Возраст",age){age=it};NumericField("Рост, см",height){height=it};NumericField("Вес, кг",weight){weight=it}
  Panel("Опыт"){FlowRow{Experience.entries.forEach{v->FilterChip(p.experience==v,{p=p.copy(experience=v)},label={Text(v.label)},modifier=Modifier.padding(end=6.dp))}}}
  Panel("Основная цель"){FlowRow{Goal.entries.forEach{v->FilterChip(p.primary==v,{p=p.copy(primary=v,secondary=if(p.secondary==v)null else p.secondary)},label={Text(v.label)},modifier=Modifier.padding(end=6.dp))}}}
  Panel("Дополнительная цель"){FlowRow{FilterChip(p.secondary==null,{p=p.copy(secondary=null)},label={Text("Без дополнительной")});Goal.entries.filter{it!=p.primary}.forEach{v->FilterChip(p.secondary==v,{p=p.copy(secondary=v)},label={Text(v.label)},modifier=Modifier.padding(end=6.dp))}}}
  Panel("Оборудование"){FlowRow{Equipment.entries.forEach{v->FilterChip(v in p.equipment,{p=p.copy(equipment=if(v in p.equipment)p.equipment-v else p.equipment+v)},label={Text(v.label)},modifier=Modifier.padding(end=6.dp))}};Text("Упражнения без оборудования всегда доступны.")}
  Panel("Мышечные акценты"){FlowRow{Muscle.entries.forEach{v->FilterChip(v in p.priorities,{p=p.copy(priorities=if(v in p.priorities)p.priorities-v else p.priorities+v)},label={Text(v.label)},modifier=Modifier.padding(end=6.dp))}}}
  Panel("Дни занятий"){FlowRow{(1..7).forEach{d->FilterChip(d in p.days,{p=p.copy(days=if(d in p.days)p.days-d else p.days+d)},label={Text(dayNames[d-1])},modifier=Modifier.padding(end=6.dp))}}}
  OutlinedTextField(p.limitations,{p=p.copy(limitations=it)},Modifier.fillMaxWidth(),label={Text("Ограничения и боль")},supportingText={Text("Заполнение блокирует автоматический подбор: приложение не определяет медицинскую допустимость упражнений.")})
  Panel("Восстановление: ${p.recovery}/5"){Text("1 — выраженное недомогание; 3 — нормально; 5 — хорошо.");Slider(p.recovery.toFloat(),{p=p.copy(recovery=it.toInt())},valueRange=1f..5f,steps=3)}
  val candidate=p.copy(name=name.trim(),age=age.toIntOrNull()?:0,heightCm=number(height)?:0.0,weightKg=number(weight)?:0.0)
  Action("Сохранить профиль",{save(candidate)},candidate.valid());if(canCancel)TextButton(cancel){Text("Отмена")}
 }
}
@Composable fun TrainingScreen(p:Profile,vm:FormaViewModel){val plan=generateProgram(p);Page("Тренировки","Оборудование, восстановление и твои цели."){if(plan.isEmpty())Text("Выберите дни и проверьте ограничения и восстановление в профиле.");plan.forEach{day->Panel("${dayNames[day.day-1]} · ${day.name}"){Text(day.explanation);day.exercises.forEach{item->Text("${item.exercise.name} · ${item.sets} × ${item.reps}");Text(item.reason,style=MaterialTheme.typography.bodySmall)};if(day.cardio!=null)CardioControls(day,vm)else Action("Начать тренировку",{vm.start(day)})}}}}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun CardioControls(day:TrainingDay,vm:FormaViewModel){
 var kind by remember{mutableStateOf(day.cardio?:CardioKind.WALK)};var duration by remember{mutableStateOf((day.durationSec/60).toString())};var walk by remember{mutableStateOf("120")};var run by remember{mutableStateOf("60")}
 FlowRow{CardioKind.entries.forEach{k->FilterChip(kind==k,{kind=k},label={Text(k.label)},modifier=Modifier.padding(end=6.dp))}}
 NumericField("Длительность, минут (1–120)",duration){duration=it};if(kind==CardioKind.INTERVAL){NumericField("Ходьба, секунд (10–1800)",walk){walk=it};NumericField("Бег, секунд (10–1800)",run){run=it}}
 val m=duration.toIntOrNull()?:0;val w=walk.toIntOrNull()?:0;val r=run.toIntOrNull()?:0
 Action("Начать кардио",{vm.start(day.copy(cardio=kind,durationSec=m*60,intervals=listOf(w,r)))},m in 1..120&&w in 10..1800&&r in 10..1800)
}
@Composable fun Runner(s:Session,vm:FormaViewModel){
 var now by remember{mutableLongStateOf(System.currentTimeMillis())};LaunchedEffect(s.id){while(true){kotlinx.coroutines.delay(500);now=System.currentTimeMillis()}}
 var weight by rememberSaveable(s.id,s.exerciseIndex,s.setNumber){mutableStateOf((s.sets.lastOrNull{it.exerciseId==s.current?.exercise?.id}?.weight?:s.current?.weight?:0.0).toString())}
 var reps by rememberSaveable(s.id,s.exerciseIndex,s.setNumber){mutableStateOf((s.sets.lastOrNull{it.exerciseId==s.current?.exercise?.id}?.reps?:s.current?.reps?:12).toString())}
 var effort by rememberSaveable(s.id,s.exerciseIndex,s.setNumber){mutableFloatStateOf(7f)}
 Page(if(s.paused)"Пауза"else stageLabel(s),"${s.day.name} · ${s.sets.size} сохранённых подходов"){
  if(s.terminal)Panel("Результат"){Text(if(s.phase==Phase.DONE)"Занятие завершено."else"Остановлено. Выполненные подходы сохранены.");Action("Сохранить и завершить",vm::finish)}
  else{
   Panel("Текущий этап"){
    if(s.phase in listOf(Phase.WARMUP,Phase.REST,Phase.CARDIO))Text("${remainingSeconds(s,now)/60}:${(remainingSeconds(s,now)%60).toString().padStart(2,'0')}",style=MaterialTheme.typography.displayMedium)
    if(s.phase==Phase.WORK)s.current?.let{Text("Подход ${s.setNumber} из ${it.sets}");Text(it.exercise.cue);Text(it.reason,style=MaterialTheme.typography.bodySmall)}
    if(s.phase==Phase.WARMUP)Text("Лёгкая ходьба на месте, плавные движения плеч и таза. Без боли и рывков.");if(s.phase==Phase.CARDIO)Text("Комфортный темп. При дискомфорте остановитесь.")
   }
   if(s.phase==Phase.WORK){if(s.current?.exercise?.equipment!=Equipment.BODYWEIGHT)NumericField("Вес, кг · гантель: на одну руку",weight){weight=it};NumericField("Повторения",reps){reps=it};Text("Сложность: ${effort.toInt()}/10");Slider(effort,{effort=it},valueRange=1f..10f,steps=8)
    val w=number(weight);val r=reps.toIntOrNull();Action("Подход выполнен",{vm.done(w?:0.0,r?:1,effort.toInt(),false)},!s.paused&&w!=null&&w in 0.0..500.0&&r!=null&&r in 1..100)

   }
   OutlinedButton(vm::painStop,Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("Боль / дискомфорт — стоп")}
   Action(if(s.paused)"Продолжить сопровождение"else"Пауза",if(s.paused)vm::resume else vm::pause)
   OutlinedButton(vm::skip,Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!s.paused){Text(if(s.phase==Phase.WORK)"Пропустить подход"else"Пропустить этап")}
   TextButton(vm::stop,Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Остановить тренировку")}
  }
 }
}
@Composable fun ProfileScreen(p:Profile,profiles:List<Profile>,settings:Preferences,vm:FormaViewModel,edit:()->Unit,create:()->Unit){Page("Профиль",p.primary.label){
 Panel("Пользователи"){profiles.forEach{v->OutlinedButton({vm.select(v.id)},Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(p.id==v.id)"✓ ${v.name}"else v.name)}};Action("Добавить пользователя",create)}
 Panel("${p.name} · ${p.age} лет"){Text("${p.heightCm} см · ${p.weightKg} кг");Text("${p.experience.label} · восстановление ${p.recovery}/5");Action("Изменить профиль и цели",edit)}
 Panel("Сопровождение"){SwitchRow("Голос Android",settings.voice){vm.audio(it,settings.sound,settings.metronome)};SwitchRow("Звуковые сигналы",settings.sound){vm.audio(settings.voice,it,settings.metronome)};SwitchRow("Метроном · 60 уд/мин",settings.metronome){vm.audio(settings.voice,settings.sound,it)};Text("Для голоса нужна русская офлайн-модель TTS. Громкость — мультимедиа Android.")}
 Panel("Оформление"){Row{TextButton({vm.dark(false)}){Text("Светлое")};TextButton({vm.dark(true)}){Text("Тёмное")};TextButton({vm.dark(null)}){Text("Система")}}}
 Panel("FORMA · 0.1.0"){Text("Локальные данные. Удаление приложения удаляет историю. Конфигурация сборки GetFitPro © 2026 warpirate — MIT; уведомление включено в APK.")}
}}
@Composable fun SwitchRow(label:String,value:Boolean,change:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(value,change)}}
