package ru.forma.app
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import android.content.Intent
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.forma.domain.*
class MainActivity:ComponentActivity(){
 private val vm:FormaViewModel by viewModels()

 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{FormaRoot(vm)}}
}
val dayNames=listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс")
@Composable fun FormaRoot(vm:FormaViewModel){
 val p by vm.current.collectAsState();val profiles by vm.profiles.collectAsState();val settings by vm.prefs.collectAsState();val error by vm.error.collectAsState();val notice by vm.notice.collectAsState()
 var tab by rememberSaveable{mutableIntStateOf(0)};var editing by rememberSaveable{mutableStateOf(false)};var creating by rememberSaveable{mutableStateOf(false)}
 // Dark is the unconfigured default; System follows the Android theme.
 val keyboardFocus=LocalFocusManager.current;val rootKeyboard=LocalSoftwareKeyboardController.current
 LaunchedEffect(creating,editing,p?.id){if(!creating&&!editing&&p!=null){keyboardFocus.clearFocus(force=true);rootKeyboard?.hide()}}
 val dark=settings.dark?:isSystemInDarkTheme()
 val colors=if(dark)darkColorScheme(primary=Color(0xFF3BF276),onPrimary=Color(0xFF02140C),secondary=Color(0xFF79DCA6),onSecondary=Color(0xFF061F15),secondaryContainer=Color(0xFF143A29),onSecondaryContainer=Color(0xFFD9FBE5),background=Color(0xFF050C10),surface=Color(0xFF101B21),surfaceVariant=Color(0xFF17252B),onSurface=Color(0xFFF5F8F8),onSurfaceVariant=Color(0xFFAEBCC2),outline=Color(0xFF64767B))else lightColorScheme(primary=Color(0xFF086D35),onPrimary=Color.White,secondary=Color(0xFF23764A),onSecondary=Color.White,secondaryContainer=Color(0xFFDCF4E5),onSecondaryContainer=Color(0xFF12492C),background=Color(0xFFF3F7F4),surface=Color.White,surfaceVariant=Color(0xFFE5EEE8),onSurface=Color(0xFF10221A),onSurfaceVariant=Color(0xFF41574B))
 MaterialTheme(colorScheme=colors,shapes=Shapes(medium=RoundedCornerShape(20.dp),large=RoundedCornerShape(28.dp))){
  val titles=listOf("Главная","Тренировки","Статистика","Профиль");val destinations=listOf(0,1,3,4);val icons=listOf(Icons.Outlined.Home,Icons.Outlined.FitnessCenter,Icons.Outlined.BarChart,Icons.Outlined.Person)
  Scaffold(containerColor=colors.background,bottomBar={if(p!=null&&p?.active==null&&!editing&&!creating)NavigationBar(containerColor=colors.surface.copy(alpha=0.98f)){titles.forEachIndexed{i,t->NavigationBarItem(tab==destinations[i]||(tab==2&&i==0),{tab=destinations[i]},icon={Icon(icons[i],t)},label={Text(t,style=MaterialTheme.typography.labelSmall)},alwaysShowLabel=true,colors=NavigationBarItemDefaults.colors(selectedIconColor=colors.primary,selectedTextColor=colors.primary,indicatorColor=colors.primary.copy(alpha=0.14f),unselectedIconColor=colors.onSurfaceVariant,unselectedTextColor=colors.onSurfaceVariant))}}}){padding->
   Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp)){
    Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=9.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
     if(p?.active!=null){
      Text("Тренировка",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=colors.onSurface)
      val active=p!!.active!!
      val exerciseCount=active.day.exercises.size
      Text(if(exerciseCount>0)"${(active.exerciseIndex+1).coerceAtMost(exerciseCount)}/$exerciseCount" else active.day.cardio?.label.orEmpty(),style=MaterialTheme.typography.titleMedium,color=colors.onSurfaceVariant)
     }else{
      Row(verticalAlignment=Alignment.CenterVertically){Text("FORM",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black,color=colors.onSurface);Text("A",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black,color=colors.primary)}
      p?.let{Text(it.name.take(22),Modifier.padding(start=14.dp),color=colors.onSurfaceVariant,maxLines=1,style=MaterialTheme.typography.labelMedium)}
     }
    }
    if(notice!=null)Panel("Данные сохранены"){Text(notice!!);TextButton({vm.notice.value=null}){Text("Понятно")}}
    if(error!=null)Panel("Не удалось выполнить действие"){Text(error!!);TextButton({vm.error.value=null}){Text("Понятно")}}
    when{creating||editing||profiles.isEmpty()->ProfileEditor(if(creating||profiles.isEmpty())null else p,{vm.saveProfile(it){creating=false;editing=false}},{creating=false;editing=false},profiles.isNotEmpty())
     p?.active!=null->Runner(p!!.active!!,vm)
     p==null->Text("Загрузка…")
     else->when(tab){0->Home(p!!,vm,{tab=1},{tab=4},{tab=2},{tab=3});1->TrainingScreen(p!!,vm);2->BodyScreen(p!!,vm);3->ProgressScreen(p!!);else->ProfileScreen(p!!,profiles,settings,vm,{editing=true},{creating=true})}
    }
   }
  }
 }
}
@Composable fun Page(title:String,subtitle:String="",compact:Boolean=false,content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(if(compact)10.dp else 14.dp)){Text(title,style=if(compact)MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold);if(subtitle.isNotBlank())Text(subtitle,style=if(compact)MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant);content();Spacer(Modifier.height(24.dp))}}
@Composable fun Panel(title:String,content:@Composable ColumnScope.()->Unit){Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);content()}}}
@Composable fun Action(label:String,onClick:()->Unit,enabled:Boolean=true){Button(onClick,Modifier.fillMaxWidth().heightIn(min=52.dp),enabled=enabled){Text(label)}}
fun number(value:String)=value.replace(',','.').toDoubleOrNull()?.takeIf{it.isFinite()}
@Composable fun NumericField(label:String,value:String,onChange:(String)->Unit){OutlinedTextField(value,onChange,Modifier.fillMaxWidth(),label={Text(label)},singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal))}

/** Decorative brand artwork: not a depiction of correct exercise technique. */
@Composable fun FormaAthleteArtwork(modifier:Modifier=Modifier){
 val green=Color(0xFF37F375)
 Canvas(modifier){
  val w=size.width;val h=size.height
  drawCircle(brush=Brush.radialGradient(listOf(green.copy(alpha=0.24f),Color.Transparent),center=Offset(w*0.66f,h*0.47f),radius=w*0.49f),radius=w*0.49f,center=Offset(w*0.66f,h*0.47f))
  drawArc(color=green.copy(alpha=0.82f),startAngle=145f,sweepAngle=260f,useCenter=false,topLeft=Offset(w*0.23f,h*0.14f),size=Size(w*0.70f,h*0.67f),style=Stroke(width=6.dp.toPx(),cap=StrokeCap.Round))
  val upper=Path().apply{
   moveTo(w*0.64f,h*0.18f)
   cubicTo(w*0.75f,h*0.10f,w*0.85f,h*0.18f,w*0.82f,h*0.35f)
   cubicTo(w*0.80f,h*0.39f,w*0.74f,h*0.41f,w*0.73f,h*0.45f)
   cubicTo(w*0.93f,h*0.47f,w*1.04f,h*0.65f,w*0.97f,h*0.95f)
   lineTo(w*0.27f,h*0.95f)
   cubicTo(w*0.20f,h*0.70f,w*0.35f,h*0.49f,w*0.59f,h*0.45f)
   cubicTo(w*0.54f,h*0.36f,w*0.55f,h*0.25f,w*0.64f,h*0.18f)
   close()
  }
  drawPath(upper,brush=Brush.linearGradient(listOf(Color(0xFF61767A),Color(0xFF23383F),Color(0xFF091318)),start=Offset(w*0.53f,0f),end=Offset(w*0.96f,h)))
  val back=Path().apply{
   moveTo(w*0.59f,h*0.46f);cubicTo(w*0.66f,h*0.5f,w*0.73f,h*0.5f,w*0.79f,h*0.46f)
   moveTo(w*0.67f,h*0.48f);cubicTo(w*0.69f,h*0.65f,w*0.67f,h*0.78f,w*0.66f,h*0.9f)
   moveTo(w*0.58f,h*0.52f);cubicTo(w*0.47f,h*0.59f,w*0.44f,h*0.71f,w*0.48f,h*0.84f)
   moveTo(w*0.76f,h*0.52f);cubicTo(w*0.84f,h*0.59f,w*0.82f,h*0.71f,w*0.76f,h*0.84f)
  }
  drawPath(back,color=Color(0xFFA9BCB9).copy(alpha=0.36f),style=Stroke(width=2.dp.toPx(),cap=StrokeCap.Round))
 }
}

/** Schematic zone highlight only; not an animation of safe exercise technique. */
@Composable fun MuscleFocusMap(muscle:Muscle,modifier:Modifier=Modifier){
 val green=MaterialTheme.colorScheme.primary
 Canvas(modifier.semantics{contentDescription="Условная схема: "+muscle.label}){
  val w=size.width;val h=size.height;val ink=Color(0xFFB4C4C4).copy(alpha=0.65f)
  drawCircle(ink,w*0.075f,Offset(w*0.50f,h*0.16f))
  drawLine(ink,Offset(w*0.50f,h*0.24f),Offset(w*0.50f,h*0.65f),2.dp.toPx())
  drawLine(ink,Offset(w*0.27f,h*0.31f),Offset(w*0.73f,h*0.31f),2.dp.toPx())
  drawLine(ink,Offset(w*0.27f,h*0.31f),Offset(w*0.20f,h*0.67f),2.dp.toPx())
  drawLine(ink,Offset(w*0.73f,h*0.31f),Offset(w*0.80f,h*0.67f),2.dp.toPx())
  drawLine(ink,Offset(w*0.41f,h*0.65f),Offset(w*0.33f,h*0.94f),2.dp.toPx())
  drawLine(ink,Offset(w*0.59f,h*0.65f),Offset(w*0.67f,h*0.94f),2.dp.toPx())
  val zones=when(muscle){
   Muscle.BACK->listOf(0.40f to 0.42f,0.60f to 0.42f)
   Muscle.CHEST->listOf(0.41f to 0.37f,0.59f to 0.37f)
   Muscle.SHOULDERS->listOf(0.28f to 0.31f,0.72f to 0.31f)
   Muscle.ARMS->listOf(0.23f to 0.46f,0.77f to 0.46f)
   Muscle.CORE->listOf(0.50f to 0.52f)
   Muscle.LEGS->listOf(0.39f to 0.78f,0.61f to 0.78f)
  }
  zones.forEach{(x,y)->drawCircle(green.copy(alpha=0.22f),w*0.12f,Offset(w*x,h*y));drawCircle(green,w*0.048f,Offset(w*x,h*y))}
 }
}

@Composable fun FormaShortcut(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,tint:Color,onClick:()->Unit,modifier:Modifier=Modifier){
 Card(modifier.height(112.dp).clickable(onClick=onClick),shape=RoundedCornerShape(19.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)){
  Column(Modifier.fillMaxSize().padding(horizontal=8.dp,vertical=13.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.SpaceBetween){
   Icon(icon,contentDescription=null,tint=tint,modifier=Modifier.size(28.dp))
   Text(label,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onSurface,maxLines=2,lineHeight=14.sp)
  }
 }
}
@Composable fun Home(p:Profile,vm:FormaViewModel,onTrain:()->Unit,onProfile:()->Unit,onBody:()->Unit,onProgress:()->Unit){
 val now=System.currentTimeMillis();val weekStartMs=weekStart(now)
 val weekly=p.history.count{it.status==Phase.DONE&&it.finishedMs in weekStartMs..now}
 val plan=generateProgram(p)
 val plannedCompleted=countCompletedPlannedDays(p.history,plan,weekStartMs,now)
 val today=java.time.LocalDate.now().dayOfWeek.value;val next=plan.minByOrNull{(it.day-today+7)%7}
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
  Text("Твой путь к сильной и здоровой версии себя",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Box(Modifier.fillMaxWidth().height(316.dp).clip(RoundedCornerShape(27.dp)).background(Color(0xFF0F2027))){
   Image(painter=painterResource(R.drawable.forma_hero),contentDescription=null,modifier=Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(246.dp),contentScale=ContentScale.Crop)
   Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color(0xFF0C171D),Color(0xFF0C171D).copy(alpha=0.72f),Color.Transparent))))
   Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.SpaceBetween){
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
     Text(if(next==null)"День восстановления"else if(next.day==today)"Сегодня\nтренировка"else"Следующая\nтренировка",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black,lineHeight=32.sp,color=Color.White)
     Text(next?.name?:"Забота о себе",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=Color(0xFF3BF276))
    }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
     if(next!=null){
      Text(if(next.cardio!=null)"Ходьба · "+(next.durationSec/60)+" минут"else next.exercises.size.toString()+" упражнения · "+next.exercises.sumOf{it.sets}+" подходов",color=Color(0xFFD0D9DA),style=MaterialTheme.typography.bodyMedium)
      Action("▶  Начать тренировку",{vm.start(next)})
     }else Action("Открыть профиль",onProfile)
    }
   }
  }
  val green=MaterialTheme.colorScheme.primary
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)){
   FormaShortcut("Программа\nтренировок",Icons.Outlined.FitnessCenter,green,onTrain,Modifier.weight(1f))
   FormaShortcut("Моя\nстатистика",Icons.Outlined.BarChart,Color(0xFF72D4AD),onProgress,Modifier.weight(1f))
   FormaShortcut("Цели и\nпрогресс",Icons.Outlined.TrackChanges,Color(0xFFF8858F),onProgress,Modifier.weight(1f))
  }
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)){
   FormaShortcut("Замеры\nтела",Icons.Outlined.Straighten,Color(0xFFFFBD70),onBody,Modifier.weight(1f).testTag("shortcut-body"))
   FormaShortcut("Голос и\nтаймер",Icons.Outlined.Timer,Color(0xFF78BEF4),onProfile,Modifier.weight(1f))
   FormaShortcut("Мой\nпрофиль",Icons.Outlined.Person,Color(0xFFAFB6D4),onProfile,Modifier.weight(1f))
  }
  Panel("На этой неделе"){
   Text(weekly.toString()+" завершённых занятий",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
   if(plan.isEmpty())Text("Сейчас нет доступного недельного плана.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   else{
    Text("По плану: $plannedCompleted из ${plan.size} дней · ${p.primary.label}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    LinearProgressIndicator(progress={plannedCompleted.toFloat()/plan.size},modifier=Modifier.fillMaxWidth())
   }
  }
  if(p.limitations.isNotBlank()||p.recovery<=1)Panel("Сначала восстановление"){
   Text("При ограничениях и боли автоматическая программа приостановлена. Уточните допустимые нагрузки и обновите самочувствие.")
   Action("Открыть профиль",onProfile)
  }
  Spacer(Modifier.height(18.dp))
 }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun ProfileEditor(existing:Profile?,save:(Profile)->Unit,cancel:()->Unit,canCancel:Boolean){
 val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
 var p by remember(existing?.id){mutableStateOf(existing?:Profile())};var name by remember(existing?.id){mutableStateOf(p.name)};var age by remember(existing?.id){mutableStateOf(p.age.toString())};var height by remember(existing?.id){mutableStateOf(p.heightCm.toString())};var weight by remember(existing?.id){mutableStateOf(p.weightKg.toString())}
 Page(if(existing==null)"Начнём с тебя"else"Твой профиль","Для взрослых. История каждого пользователя изолирована."){
  OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text("Имя")},singleLine=true);NumericField("Возраст",age){age=it};NumericField("Рост, см",height){height=it};NumericField("Вес, кг",weight){weight=it}
  Panel("Опыт"){FlowRow{Experience.entries.forEach{v->FilterChip(p.experience==v,{p=p.copy(experience=v)},label={Text(v.label)},modifier=Modifier.padding(end=6.dp))}}}
  Panel("Основная цель"){FlowRow{Goal.entries.forEach{v->FilterChip(p.primary==v,{p=p.copy(primary=v,secondary=if(p.secondary==v)null else p.secondary)},label={Text(v.label)},modifier=Modifier.padding(end=6.dp))}}}
  Panel("Дополнительная цель"){FlowRow{FilterChip(p.secondary==null,{p=p.copy(secondary=null)},label={Text("Без дополнительной")});Goal.entries.filter{it!=p.primary}.forEach{v->FilterChip(p.secondary==v,{p=p.copy(secondary=v)},label={Text(v.label)},modifier=Modifier.padding(end=6.dp))}}}
  Panel("Оборудование · Дом"){
   Text("Выберите доступные снаряды. Их можно комбинировать с тренажёрами зала.")
   FlowRow{equipmentAt(EquipmentVenue.HOME).forEach{v->FilterChip(v in p.equipment,{p=p.copy(equipment=if(v in p.equipment)p.equipment-v else p.equipment+v)},label={Text(v.label)},modifier=Modifier.padding(end=6.dp))}}
   Text("Упражнения со своим весом доступны всегда.")
  }
  Panel("Оборудование · Тренажёрный зал"){
   FlowRow{equipmentAt(EquipmentVenue.GYM).forEach{v->FilterChip(v in p.equipment,{p=p.copy(equipment=if(v in p.equipment)p.equipment-v else p.equipment+v)},label={Text(v.label)},modifier=Modifier.padding(end=6.dp))}}
  }
  Panel("Мышечные акценты"){FlowRow{Muscle.entries.forEach{v->FilterChip(v in p.priorities,{p=p.copy(priorities=if(v in p.priorities)p.priorities-v else p.priorities+v)},label={Text(v.label)},modifier=Modifier.padding(end=6.dp))}}}
  Panel("Дни занятий"){Text("Стартовый режим: силовые в пн, ср и пт. Дополнительные дни превращаются в лёгкое кардио; силовых — не более трёх в неделю.");FlowRow{(1..7).forEach{d->FilterChip(d in p.days,{p=p.copy(days=if(d in p.days)p.days-d else p.days+d)},label={Text(dayNames[d-1])},modifier=Modifier.padding(end=6.dp))}}}
  OutlinedTextField(p.limitations,{p=p.copy(limitations=it.take(2000))},Modifier.fillMaxWidth(),label={Text("Ограничения и боль")},supportingText={Text("${p.limitations.length}/2000 символов. Заполнение блокирует автоматический подбор; приложение не оценивает медицинскую допустимость упражнений.")})
  Panel("Восстановление: ${p.recovery}/5"){Text("1 — выраженное недомогание; 3 — нормально; 5 — хорошо.");Slider(p.recovery.toFloat(),{p=p.copy(recovery=it.toInt())},valueRange=1f..5f,steps=3)}
  val candidate=p.copy(name=name.trim(),age=age.toIntOrNull()?:0,heightCm=number(height)?:0.0,weightKg=number(weight)?:0.0)
  Action("Сохранить профиль",{focus.clearFocus(force=true);keyboard?.hide();save(candidate)},candidate.valid());if(canCancel)TextButton({focus.clearFocus(force=true);keyboard?.hide();cancel()}){Text("Отмена")}
 }
}
@Composable fun TrainingScreen(p:Profile,vm:FormaViewModel){val plan=generateProgram(p);Page("Тренировки","Оборудование, восстановление и твои цели."){
 if(plan.isEmpty())Text("Выберите дни и проверьте ограничения и восстановление в профиле.")
 plan.forEach{day->TrainingDayCard(day,p,vm)}
 // New profiles start with three spaced strength days. Optional cardio is
 // still directly accessible without changing the user's saved schedule.
 if(plan.isNotEmpty() && plan.none{it.cardio!=null})Panel("Кардио по желанию"){
  Text("Дополнительная ходьба или интервалы не обязательны и не добавляются в недельный силовой план.")
  CardioControls(TrainingDay(day=java.time.LocalDate.now().dayOfWeek.value,name="Кардио по желанию",cardio=CardioKind.WALK,durationSec=1200),vm)
 }
 Panel("Техника упражнений"){
  Text("${exerciseCatalog.size} упражнений с инструкциями. Начинайте с лёгкой нагрузки и комфортной амплитуды. Замены доступны в каждой силовой программе.")
  exerciseCatalog.filter{it.equipment in p.equipment||it.equipment==Equipment.BODYWEIGHT}.forEach{e->var open by remember(e.id){mutableStateOf(false)};TextButton({open=!open},Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(e.name)};if(open){Text(e.cue);Text("Частые ошибки: ${e.mistakes}")}}
 }
}}
@Composable fun TrainingDayCard(day:TrainingDay,p:Profile,vm:FormaViewModel){
 var replacements by remember(day,p.equipment){mutableStateOf<Map<Int,Exercise>>(emptyMap())}
 val chosen=day.copy(exercises=day.exercises.mapIndexed{i,item->replacements[i]?.let{item.copy(exercise=it,weight=0.0,reason="Замена выбрана вручную. Начните с лёгкого веса.")}?:item})
 Panel("${dayNames[day.day-1]} · ${day.name}"){
  Text(day.explanation)
  chosen.exercises.forEachIndexed{i,item->
   Text("${item.exercise.name} · ${item.sets} × ${item.reps}");Text(item.reason,style=MaterialTheme.typography.bodySmall)
   val alternatives=exerciseCatalog.filter{it.id!=item.exercise.id && it.muscle==item.exercise.muscle && (it.equipment==Equipment.BODYWEIGHT||it.equipment in p.equipment)}
   if(alternatives.isNotEmpty()){var expanded by remember{mutableStateOf(false)};TextButton({expanded=!expanded}){Text("Заменить упражнение")};if(expanded)alternatives.forEach{e->TextButton({replacements=replacements+(i to e);expanded=false}){Text(e.name)}}}
  }
  if(day.cardio!=null)CardioControls(chosen,vm)else Action("Начать тренировку",{vm.start(chosen)})
 }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun CardioControls(day:TrainingDay,vm:FormaViewModel){
 var kind by remember{mutableStateOf(day.cardio?:CardioKind.WALK)};var duration by remember{mutableStateOf((day.durationSec/60).toString())};var walk by remember{mutableStateOf("120")};var run by remember{mutableStateOf("60")}
 FlowRow{CardioKind.entries.forEach{k->FilterChip(kind==k,{kind=k},label={Text(k.label)},modifier=Modifier.padding(end=6.dp))}}
 NumericField("Длительность, минут (1–120)",duration){duration=it};if(kind==CardioKind.INTERVAL){NumericField("Ходьба, секунд (10–1800)",walk){walk=it};NumericField("Бег, секунд (10–1800)",run){run=it}}
 val m=duration.toIntOrNull()?:0;val w=walk.toIntOrNull()?:0;val r=run.toIntOrNull()?:0
 Action("Начать кардио",{vm.start(day.copy(cardio=kind,durationSec=m*60,intervals=listOf(w,r)))},m in 1..120&&w in 10..1800&&r in 10..1800)
}

@Composable fun Runner(s:Session,vm:FormaViewModel){
 val app=LocalContext.current.applicationContext as FormaApp
 var now by remember{mutableLongStateOf(app.workoutNow())}
 LaunchedEffect(s.id){while(true){kotlinx.coroutines.delay(500);now=app.workoutNow()}}
 var weight by rememberSaveable(s.id,s.exerciseIndex,s.setNumber){mutableStateOf((s.sets.lastOrNull{it.exerciseId==s.current?.exercise?.id}?.weight?:s.current?.weight?:0.0).toString())}
 var reps by rememberSaveable(s.id,s.exerciseIndex,s.setNumber){mutableStateOf((s.sets.lastOrNull{it.exerciseId==s.current?.exercise?.id}?.reps?:s.current?.reps?:12).toString())}
 var effort by rememberSaveable(s.id,s.exerciseIndex,s.setNumber){mutableFloatStateOf(7f)}
 val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
 LaunchedEffect(s.id,s.phase,s.exerciseIndex,s.setNumber,s.paused){focus.clearFocus(force=true);keyboard?.hide()}
 key(s.id,s.phase,s.exerciseIndex,s.setNumber,s.paused){
 Page(if(s.paused)"Пауза"else if(s.phase==Phase.WORK)s.current?.exercise?.name?:stageLabel(s) else stageLabel(s),if(s.phase==Phase.WORK)(s.current?.exercise?.muscle?.label?:s.day.name)+" · "+s.sets.size+" сохранённых подходов" else s.day.name+" · "+s.sets.size+" сохранённых подходов",compact=true){
  if(s.terminal)Panel("Результат"){
   Text(if(s.phase==Phase.DONE)"Занятие завершено."else"Остановлено. Выполненные подходы сохранены.")
   Action("Сохранить и завершить",{vm.finish(s.id)})
  }else{
   if(s.phase==Phase.WORK){
    val current=s.current
    if(current!=null){
     Text(current.exercise.muscle.label+" · "+current.exercise.equipment.label,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
     Box(Modifier.fillMaxWidth().height(205.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF0B1B20))){
      FormaAthleteArtwork(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(210.dp))
      Column(Modifier.align(Alignment.CenterEnd).width(108.dp).padding(end=10.dp),horizontalAlignment=Alignment.CenterHorizontally){
       MuscleFocusMap(current.exercise.muscle,Modifier.fillMaxWidth().height(145.dp))
       Text("Зона нагрузки",style=MaterialTheme.typography.labelSmall,color=Color(0xFFBBD5C5))
      }
      Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent,Color(0xFF091217)))).padding(14.dp)){
       Text("Основная группа: "+current.exercise.muscle.label,style=MaterialTheme.typography.bodyMedium,color=Color.White)
      }
     }
     var techniqueExpanded by rememberSaveable(s.id,s.exerciseIndex){mutableStateOf(false)}
     Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){
      FilledTonalButton({techniqueExpanded=!techniqueExpanded},modifier=Modifier.weight(1f)){
       Text(if(techniqueExpanded)"Скрыть инструкцию"else"Инструкция")
      }
      Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.secondaryContainer){
       Text(current.exercise.muscle.label,Modifier.padding(horizontal=14.dp,vertical=11.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSecondaryContainer)
      }
     }
     val secondary=secondaryMuscles(current.exercise)
     if(secondary.isNotEmpty())Text("Дополнительно работают: "+secondary.joinToString { it.label },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
     Text("Схема мышц не заменяет демонстрацию техники. Проверенные видео для отдельных упражнений пока готовятся.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
     if(techniqueExpanded)Panel("Техника упражнения"){
      Text(current.exercise.cue)
      if(current.exercise.mistakes.isNotBlank()){
       Text("Частые ошибки",fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
       Text(current.exercise.mistakes,style=MaterialTheme.typography.bodySmall)
      }
     }
     Panel("Подходы"){
      Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
       Text("№",modifier=Modifier.weight(0.3f),style=MaterialTheme.typography.labelMedium)
       Text("Вес, кг",modifier=Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
       Text("Повторы",modifier=Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
       Text("Статус",modifier=Modifier.weight(0.8f),style=MaterialTheme.typography.labelMedium)
      }
      val logged=s.sets.filter{it.exerciseId==current.exercise.id}
      for(index in 1..current.sets){
       val result=logged.getOrNull(index-1)
       Row(Modifier.fillMaxWidth().padding(vertical=3.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
        Text(index.toString(),modifier=Modifier.weight(0.3f))
        Text(result?.weight?.toString()?:if(index==s.setNumber)weight else "—",modifier=Modifier.weight(1f))
        Text(result?.reps?.toString()?:if(index==s.setNumber)reps else "—",modifier=Modifier.weight(1f))
        Text(if(result!=null)"✓"else if(index==s.setNumber)"●"else "○",modifier=Modifier.weight(0.8f),color=if(result!=null)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
       }
      }
      Text("Текущий подход: "+s.setNumber+" из "+current.sets,style=MaterialTheme.typography.bodySmall)
      if(current.exercise.equipment!=Equipment.BODYWEIGHT)NumericField(if(current.exercise.equipment==Equipment.BARBELL)"Вес, кг · штанга целиком" else "Вес, кг · одна гантель",weight){weight=it}
      NumericField("Повторения",reps){reps=it}
      Text("Сложность: "+effort.toInt()+"/10")
      Slider(effort,{effort=it},valueRange=1f..10f,steps=8)
      val w=number(weight);val n=reps.toIntOrNull()
      Action("Подход выполнен",{vm.done(s.id,w?:0.0,n?:1,effort.toInt(),false)},!s.paused&&w!=null&&w in 0.0..500.0&&n!=null&&n in 1..100)
     }
    }
   }else{
    Panel("Текущий этап"){
     if(s.phase in listOf(Phase.WARMUP,Phase.REST,Phase.CARDIO)){
      Text((remainingSeconds(s,now)/60).toString().padStart(2,'0')+":"+(remainingSeconds(s,now)%60).toString().padStart(2,'0'),style=MaterialTheme.typography.displayMedium,fontWeight=FontWeight.Black,color=MaterialTheme.colorScheme.primary)
     }
     if(s.phase==Phase.WARMUP)Text("Лёгкая ходьба на месте, плавные движения плеч и таза. Без боли и рывков.")
     if(s.phase==Phase.CARDIO)Text("Комфортный темп. При дискомфорте остановитесь.")
     if(s.phase==Phase.REST)Text("Восстановите дыхание перед следующим подходом.")
    }
   }
   Panel("Управление"){
    OutlinedButton({vm.painStop(s.id)},Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("Боль / дискомфорт — стоп")}
    Action(if(s.paused)"Продолжить сопровождение"else"Пауза",{if(s.paused)vm.resume(s.id) else vm.pause(s.id)})
    OutlinedButton({vm.skip(s.id)},Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!s.paused){
     Text(if(s.phase==Phase.WORK)"Пропустить подход"else"Пропустить этап")
    }
    TextButton({vm.stop(s.id)},Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Остановить тренировку")}
   }
  }
 }
 }
}
@Composable fun ProfileScreen(p:Profile,profiles:List<Profile>,settings:Preferences,vm:FormaViewModel,edit:()->Unit,create:()->Unit){
 var profileSection by remember{mutableIntStateOf(0)}
 var profileSearch by remember{mutableStateOf("")}
 Page("Профиль",p.primary.label){
  TabRow(selectedTabIndex=profileSection){
   Tab(selected=profileSection==0,onClick={profileSection=0},text={Text("Пользователи")})
   Tab(selected=profileSection==1,onClick={profileSection=1},text={Text("Обновление")})
  }
  if(profileSection==1){UpdateCenter()}else{
   Panel("Пользователи · ${profiles.size}"){
    if(profiles.size>4)OutlinedTextField(profileSearch,{profileSearch=it},label={Text("Найти профиль")},singleLine=true,modifier=Modifier.fillMaxWidth())
    val matches=profiles.filter{it.name.contains(profileSearch,ignoreCase=true)}
    matches.take(40).forEach{v->OutlinedButton({vm.select(v.id)},Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(p.id==v.id)"✓ ${v.name}"else v.name)}}
    if(matches.size>40)Text("Показаны первые 40. Уточните поиск.",style=MaterialTheme.typography.bodySmall)
    Action("Добавить пользователя",create)
    Text("Это локальные профили. До подключения защищённой синхронизации переносите данные между телефонами через резервные копии.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
 Panel("${p.name} · ${p.age} лет"){Text("${p.heightCm} см · ${p.weightKg} кг");Text("${p.experience.label} · восстановление ${p.recovery}/5");Action("Изменить профиль и цели",edit)}
 val coach by vm.coachStatus.collectAsState()
 val context=LocalContext.current
 Panel("Сопровождение"){SwitchRow("Голос Android",settings.voice){vm.audio(it,settings.sound,settings.metronome)};SwitchRow("Звуковые сигналы",settings.sound){vm.audio(settings.voice,it,settings.metronome)};SwitchRow("Метроном · ${settings.bpm} уд/мин",settings.metronome){vm.audio(settings.voice,settings.sound,it)};Text(coach);Text("Громкость подсказок: ${settings.volume}%");Slider(settings.volume.toFloat(),{vm.coach(it.toInt(),settings.bpm,settings.speechRate)},valueRange=0f..100f)
 Text("Темп: ${settings.bpm} уд/мин");Slider(settings.bpm.toFloat(),{vm.coach(settings.volume,it.toInt(),settings.speechRate)},valueRange=40f..180f,steps=27)
 Text("Скорость речи: ${"%.1f".format(settings.speechRate)}");Slider(settings.speechRate,{vm.coach(settings.volume,settings.bpm,it)},valueRange=0.7f..1.3f)
 TextButton({runCatching{context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))}.onFailure{vm.error.value="Настройки синтеза речи недоступны на этом телефоне"}}){Text("Настроить голос Android")}
 Text("Голос выбирается среди русских офлайн-голосов Android. Общая громкость также зависит от уровня мультимедиа телефона.")}
 Panel("Оформление"){Row{TextButton({vm.dark(false)}){Text("Светлое")};TextButton({vm.dark(true)}){Text("Тёмное")};TextButton({vm.dark(null)}){Text("Система")}}}
 NotificationPanel()
 BackupPanel(vm)
 Panel("FORMA · 0.2.0"){Text("Локальные данные. Удаление приложения удаляет историю. Конфигурация сборки GetFitPro © 2026 warpirate — MIT; уведомление включено в APK.")}
  }
 }
}
@Composable fun SwitchRow(label:String,value:Boolean,change:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(value,change)}}


@Composable fun BackupPanel(vm:FormaViewModel){
 val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->uri?.let(vm::exportBackup)}
 val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let(vm::importBackup)}
 var confirm by remember{mutableStateOf(false)}
 Panel("Резервная копия"){
  Text("Все профили, история и замеры — в одном локальном файле. Сначала завершите текущее занятие. Файл содержит личные данные; храните его в защищённом месте.")
  Action("Экспортировать копию",{export.launch("FORMA-backup-${java.time.LocalDate.now()}.json")})
  OutlinedButton({confirm=true},Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Восстановить из файла")}
 }
 if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Восстановление копии")},text={Text("Профили добавятся как отдельные копии. Текущие данные не заменяются. Повторный импорт создаёт ещё одну копию каждого профиля. Файл до 8 МБ.")},confirmButton={TextButton({confirm=false;import.launch(arrayOf("application/json","text/plain","application/octet-stream"))}){Text("Выбрать файл")}},dismissButton={TextButton({confirm=false}){Text("Отмена")}})
}

@Composable fun NotificationPanel(){
 val context=LocalContext.current
 var allowed by remember{mutableStateOf(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())}
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){allowed=it}
 Panel("Уведомления"){
  Text(if(allowed)"Управление паузой и остановкой доступно в уведомлении."else "Уведомления выключены. Таймер продолжает работу; для управления откройте FORMA.")
  if(!allowed)TextButton({if(Build.VERSION.SDK_INT>=33)permission.launch(Manifest.permission.POST_NOTIFICATIONS)else runCatching{context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName))}}){Text("Разрешить уведомления")}
 }
}
