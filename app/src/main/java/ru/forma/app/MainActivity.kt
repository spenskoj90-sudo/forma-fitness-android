package ru.forma.app
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import android.content.Intent
import androidx.compose.ui.graphics.Brush
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.Saver
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ru.forma.domain.*
class MainActivity:ComponentActivity(){
 private val vm:FormaViewModel by viewModels()

 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{FormaRoot(vm)}}
}
/** Keep the complete selected identity available to TalkBack even when visually elided.
 * The weighted name never pushes the brand outside a small 2x-font viewport.
 */
@Composable internal fun FormaBrandAndProfileHeader(profileName:String?) {
 Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
  Text("FORM",style=MaterialTheme.typography.headlineMedium,
   fontWeight=FontWeight.Black,color=MaterialTheme.colorScheme.onSurface)
  Text("A",style=MaterialTheme.typography.headlineMedium,
   fontWeight=FontWeight.Black,color=MaterialTheme.colorScheme.primary)
  if(profileName!=null)Text(
   profileName,
   modifier=Modifier.weight(1f).padding(start=12.dp)
    .testTag("active-profile-header")
    .semantics { contentDescription="Активный профиль: $profileName" },
   maxLines=2,
   overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,
   color=MaterialTheme.colorScheme.onSurfaceVariant,
   style=MaterialTheme.typography.labelMedium
  )
 }
}
val dayNames=listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс")
@Composable fun FormaRoot(vm:FormaViewModel){
 val p by vm.current.collectAsState()
 val profilesSnapshot by vm.profileSnapshot.collectAsState()
 val settingsSnapshot by vm.settingsSnapshot.collectAsState()
 val profiles=profilesSnapshot.profiles
 val settings=settingsSnapshot.settings
 val error by vm.error.collectAsState();val notice by vm.notice.collectAsState()
 val profileSaving by vm.profileSaveInProgress.collectAsState()
 var tab by rememberSaveable{mutableIntStateOf(0)};var editing by rememberSaveable{mutableStateOf(false)};var creating by rememberSaveable{mutableStateOf(false)}
 // Dark is the unconfigured default; System follows the Android theme.
 val keyboardFocus=LocalFocusManager.current;val rootKeyboard=LocalSoftwareKeyboardController.current
 LaunchedEffect(creating,editing,p?.id){if(!creating&&!editing&&p!=null){keyboardFocus.clearFocus(force=true);rootKeyboard?.hide()}}
 val restoredScreens=rememberSaveableStateHolder()
 val dark=settings.dark?:isSystemInDarkTheme()
 // The navigation bar must not steal the primary action's IME-area tap.
 val keyboardVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
 val colors=if(dark)darkColorScheme(primary=Color(0xFF3BF276),onPrimary=Color(0xFF02140C),secondary=Color(0xFF79DCA6),onSecondary=Color(0xFF061F15),secondaryContainer=Color(0xFF143A29),onSecondaryContainer=Color(0xFFD9FBE5),background=Color(0xFF050C10),surface=Color(0xFF101B21),surfaceVariant=Color(0xFF17252B),onSurface=Color(0xFFF5F8F8),onSurfaceVariant=Color(0xFFAEBCC2),outline=Color(0xFF64767B))else lightColorScheme(primary=Color(0xFF086D35),onPrimary=Color.White,secondary=Color(0xFF23764A),onSecondary=Color.White,secondaryContainer=Color(0xFFDCF4E5),onSecondaryContainer=Color(0xFF12492C),background=Color(0xFFF3F7F4),surface=Color.White,surfaceVariant=Color(0xFFE5EEE8),onSurface=Color(0xFF10221A),onSurfaceVariant=Color(0xFF41574B))
 MaterialTheme(colorScheme=colors,shapes=Shapes(medium=RoundedCornerShape(20.dp),large=RoundedCornerShape(28.dp))){
  val titles=listOf("Главная","Тренировки","Статистика","Профиль");val destinations=listOf(0,1,3,4);val icons=listOf(Icons.Outlined.Home,Icons.Outlined.FitnessCenter,Icons.Outlined.BarChart,Icons.Outlined.Person)
  Scaffold(containerColor=colors.background,bottomBar={if(p!=null&&p?.active==null&&!editing&&!creating&&!keyboardVisible)NavigationBar(containerColor=colors.surface.copy(alpha=0.98f)){titles.forEachIndexed{i,t->NavigationBarItem(tab==destinations[i]||(tab==2&&i==0),{tab=destinations[i]},icon={Icon(icons[i],t)},label={Text(t,style=MaterialTheme.typography.labelSmall)},alwaysShowLabel=true,colors=NavigationBarItemDefaults.colors(selectedIconColor=colors.primary,selectedTextColor=colors.primary,indicatorColor=colors.primary.copy(alpha=0.14f),unselectedIconColor=colors.onSurfaceVariant,unselectedTextColor=colors.onSurfaceVariant))}}}){padding->
   Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp)){
    Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=9.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
     if(p?.active!=null){
      Text("Тренировка",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=colors.onSurface)
      val active=p!!.active!!
      val exerciseCount=active.day.exercises.size
      Text(if(exerciseCount>0)"${(active.exerciseIndex+1).coerceAtMost(exerciseCount)}/$exerciseCount" else active.day.cardio?.label.orEmpty(),style=MaterialTheme.typography.titleMedium,color=colors.onSurfaceVariant)
     }else{
      FormaBrandAndProfileHeader(p?.name)
     }
    }
    if(notice!=null)Panel("Данные сохранены"){Text(notice!!);TextButton({vm.notice.value=null}){Text("Понятно")}}
    if(error!=null)Panel("Не удалось выполнить действие"){Text(error!!);TextButton({vm.error.value=null}){Text("Понятно")}}
    when{
     // Never show new-profile onboarding or somebody else's first profile
     // while Room/DataStore still deliver their persisted initial values.
     !canRenderProfileUi(profilesSnapshot,settingsSnapshot,p)->
      Text(if(profilesSnapshot.status==LocalLoadStatus.FAILED||settingsSnapshot.status==LocalLoadStatus.FAILED)
       "Локальные профили или настройки недоступны. Данные не изменены." else "Загрузка…")
     creating||editing||profiles.isEmpty()->ProfileEditor(if(creating||profiles.isEmpty())null else p,{vm.saveProfile(it){creating=false;editing=false}},{creating=false;editing=false},profiles.isNotEmpty(),profileSaving,{vm.noteProfileSaveTouch(it)})
     p?.active!=null->Runner(p!!.active!!,vm){tab=3}
     p==null->Text("Загрузка…")
     else->when(tab){0->Home(p!!,settings.trainingPlace,vm,{tab=1},{tab=4},{tab=2},{tab=3});1->restoredScreens.SaveableStateProvider("training:${p!!.id}"){TrainingScreen(p!!,settings.trainingPlace,vm)};2->restoredScreens.SaveableStateProvider("body:${p!!.id}"){BodyScreen(p!!,vm)};3->ProgressScreen(p!!);else->ProfileScreen(p!!,profiles,settings,vm,{editing=true},{creating=true})}
    }
   }
  }
 }
}
@Composable fun Page(title:String,subtitle:String="",compact:Boolean=false,content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(if(compact)10.dp else 14.dp)){Text(title,modifier=Modifier.semantics{heading()},style=if(compact)MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold);if(subtitle.isNotBlank())Text(subtitle,style=if(compact)MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant);content();Spacer(Modifier.height(24.dp))}}
@Composable fun Panel(title:String,content:@Composable ColumnScope.()->Unit){Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,modifier=Modifier.semantics{heading()},style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);content()}}}
@Composable fun Action(label:String,onClick:()->Unit,enabled:Boolean=true){Button(onClick,Modifier.fillMaxWidth().heightIn(min=52.dp),enabled=enabled){Text(label)}}
fun number(value:String)=value.replace(',','.').toDoubleOrNull()?.takeIf{it.isFinite()}
@Composable fun NumericField(label:String,value:String,modifier:Modifier=Modifier,onChange:(String)->Unit){
 OutlinedTextField(value,onChange,Modifier.fillMaxWidth().then(modifier),label={Text(label)},
  singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(
   keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal))
}

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
@Composable fun MuscleFocusMap(
 muscle:Muscle,modifier:Modifier=Modifier,secondary:Set<Muscle> = emptySet(),
 backView:Boolean?=null
){
 val green=MaterialTheme.colorScheme.primary
 Canvas(modifier.semantics{
  contentDescription="Условная схема"+(when(backView){true->" сзади";false->" спереди";null->""})+
   ": основная "+muscle.label+(if(secondary.isEmpty())"" else "; дополнительные "+secondary.joinToString{it.label})
 }){
  val w=size.width;val h=size.height;val ink=Color(0xFFB4C4C4).copy(alpha=0.65f)
  drawCircle(ink,w*0.075f,Offset(w*0.50f,h*0.16f))
  drawLine(ink,Offset(w*0.50f,h*0.24f),Offset(w*0.50f,h*0.65f),2.dp.toPx())
  drawLine(ink,Offset(w*0.27f,h*0.31f),Offset(w*0.73f,h*0.31f),2.dp.toPx())
  drawLine(ink,Offset(w*0.27f,h*0.31f),Offset(w*0.20f,h*0.67f),2.dp.toPx())
  drawLine(ink,Offset(w*0.73f,h*0.31f),Offset(w*0.80f,h*0.67f),2.dp.toPx())
  drawLine(ink,Offset(w*0.41f,h*0.65f),Offset(w*0.33f,h*0.94f),2.dp.toPx())
  drawLine(ink,Offset(w*0.59f,h*0.65f),Offset(w*0.67f,h*0.94f),2.dp.toPx())
  fun zones(group:Muscle)=when(group){
   Muscle.BACK->if(backView==false)emptyList() else listOf(0.40f to 0.42f,0.60f to 0.42f)
   Muscle.CHEST->if(backView==true)emptyList() else listOf(0.41f to 0.37f,0.59f to 0.37f)
   Muscle.SHOULDERS->listOf(0.28f to 0.31f,0.72f to 0.31f)
   Muscle.ARMS->listOf(0.23f to 0.46f,0.77f to 0.46f)
   Muscle.CORE->listOf(0.50f to 0.52f)
   Muscle.LEGS->listOf(0.39f to 0.78f,0.61f to 0.78f)
  }
  secondary.filter{it!=muscle}.forEach{group->
   zones(group).forEach{(x,y)->drawCircle(green.copy(alpha=0.13f),w*0.11f,Offset(w*x,h*y));drawCircle(ink,w*0.03f,Offset(w*x,h*y))}
  }
  zones(muscle).forEach{(x,y)->drawCircle(green.copy(alpha=0.26f),w*0.12f,Offset(w*x,h*y));drawCircle(green,w*0.048f,Offset(w*x,h*y))}
 }
}

/**
 * Text grows to three lines and cards expand instead of clipping at 2x font.
 * The original three-column layout is used at standard font scale.
 */
@Composable fun FormaShortcut(
 label:String,
 icon:androidx.compose.ui.graphics.vector.ImageVector,
 tint:Color,
 onClick:()->Unit,
 modifier:Modifier=Modifier,
 onLabelLayout:(TextLayoutResult)->Unit={}
){
 Card(modifier.heightIn(min=112.dp).clickable(onClick=onClick),
  shape=RoundedCornerShape(19.dp),
  colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)){
  Column(Modifier.fillMaxWidth().heightIn(min=112.dp).padding(horizontal=8.dp,vertical=13.dp),
   horizontalAlignment=Alignment.CenterHorizontally,
   verticalArrangement=Arrangement.SpaceBetween){
   Icon(icon,contentDescription=null,tint=tint,modifier=Modifier.size(28.dp))
   Text(label,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Medium,
    color=MaterialTheme.colorScheme.onSurface,maxLines=3,lineHeight=16.sp,
    textAlign=TextAlign.Center,onTextLayout=onLabelLayout)
  }
 }
}

private data class HomeShortcutItem(
 val text:String,
 val icon:androidx.compose.ui.graphics.vector.ImageVector,
 val tint:Color,
 val onClick:()->Unit
)

/** Two wider columns are readable at 1.6x–2x; 3x2 is retained normally. */
@Composable fun HomeShortcutGrid(
 onTrain:()->Unit,onProgress:()->Unit,onBody:()->Unit,onProfile:()->Unit,
 onLabelLayout:(String,TextLayoutResult)->Unit={_,_->}
){
 val items=listOf(
  HomeShortcutItem("Программа\nтренировок",Icons.Outlined.FitnessCenter,MaterialTheme.colorScheme.primary,onTrain),
  HomeShortcutItem("Моя\nстатистика",Icons.Outlined.BarChart,Color(0xFF72D4AD),onProgress),
  HomeShortcutItem("Цели и\nпрогресс",Icons.Outlined.TrackChanges,Color(0xFFF8858F),onProgress),
  HomeShortcutItem("Замеры\nтела",Icons.Outlined.Straighten,Color(0xFFFFBD70),onBody),
  HomeShortcutItem("Голос и\nтаймер",Icons.Outlined.Timer,Color(0xFF78BEF4),onProfile),
  HomeShortcutItem("Мой\nпрофиль",Icons.Outlined.Person,Color(0xFFAFB6D4),onProfile)
 )
 val columns=if(LocalDensity.current.fontScale>=1.6f)2 else 3
 Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(9.dp)){
  items.chunked(columns).forEach{row->
   Row(Modifier.fillMaxWidth().testTag("home-shortcut-row"),
    horizontalArrangement=Arrangement.spacedBy(9.dp)){
    row.forEach{item->
     val extra=if(item.text=="Замеры\nтела")Modifier.testTag("shortcut-body") else Modifier
     FormaShortcut(item.text,item.icon,item.tint,item.onClick,
      Modifier.weight(1f).then(extra),
      onLabelLayout={layout->onLabelLayout(item.text,layout)})
    }
   }
  }
 }
}

/** The hero is content-sized (minimum 316dp), not forcibly clipped at 2x. */
@Composable fun HomeHero(
 next:TrainingDay?,place:TrainingPlace,
 onStart:(TrainingDay)->Unit,onProfile:()->Unit
){
 val today=java.time.LocalDate.now().dayOfWeek.value
 Box(Modifier.fillMaxWidth().testTag("home-hero")
  .clip(RoundedCornerShape(27.dp)).background(Color(0xFF0F2027))){
  Image(painter=painterResource(R.drawable.forma_hero),contentDescription=null,
   modifier=Modifier.align(Alignment.TopEnd).height(316.dp).width(246.dp),
   contentScale=ContentScale.Crop)
  Box(Modifier.matchParentSize().background(
   Brush.horizontalGradient(listOf(Color(0xFF0C171D),
    Color(0xFF0C171D).copy(alpha=0.72f),Color.Transparent))))
  Column(Modifier.fillMaxWidth().heightIn(min=316.dp).padding(20.dp),
   verticalArrangement=Arrangement.SpaceBetween){
   Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
    Text(if(next==null)"День восстановления"else if(next.day==today)"Сегодня\nтренировка"else"Следующая\nтренировка",
     style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black,
     lineHeight=32.sp,color=Color.White)
    Text(next?.name?:"Забота о себе",style=MaterialTheme.typography.titleMedium,
     fontWeight=FontWeight.Bold,color=Color(0xFF3BF276))
   }
   Spacer(Modifier.height(24.dp))
   Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
    if(next!=null){
     Text(if(next.cardio!=null)"Ходьба · "+(next.durationSec/60)+" минут"
      else next.exercises.size.toString()+" упражнения · "+next.exercises.sumOf{it.sets}+" подходов",
      color=Color(0xFFD0D9DA),style=MaterialTheme.typography.bodyMedium)
     Text("Место: "+place.label,color=Color(0xFFC8D6D8),style=MaterialTheme.typography.labelSmall)
     Action("▶  Начать тренировку",{onStart(next)})
    }else Action("Открыть профиль",onProfile)
   }
  }
 }
}

@Composable fun Home(p:Profile,place:TrainingPlace,vm:FormaViewModel,onTrain:()->Unit,onProfile:()->Unit,onBody:()->Unit,onProgress:()->Unit){
 val now=System.currentTimeMillis();val weekStartMs=weekStart(now)
 val weekly=p.history.count{it.status==Phase.DONE&&it.finishedMs in weekStartMs..now}
 val plan=generateProgram(p,place=place)
 val plannedCompleted=countCompletedPlannedDays(p.history,plan,weekStartMs,now)
 val today=java.time.LocalDate.now().dayOfWeek.value;val next=plan.minByOrNull{(it.day-today+7)%7}
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
  Text("Твой путь к сильной и здоровой версии себя",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  HomeHero(next,place,{vm.start(it)},onProfile)
  HomeShortcutGrid(onTrain,onProgress,onBody,onProfile)
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
// Keep saved-state small: only editable fields, never history, measurements or
// an active Room workout. The original profile is authoritative on Save.
private val editableProfileSaver=Saver<Profile,String>(
 save={value->kotlinx.serialization.json.Json.encodeToString(
  Profile.serializer(),value.copy(history=emptyList(),measurements=emptyList(),active=null))},
 restore={saved->kotlinx.serialization.json.Json.decodeFromString(Profile.serializer(),saved)}
)
@OptIn(ExperimentalLayoutApi::class)
@Composable fun ProfileEditor(existing:Profile?,save:(Profile)->Unit,cancel:()->Unit,canCancel:Boolean,saving:Boolean=false,onSaveTouch:(SaveTouchStage)->Unit={}){
 val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
 val saveInteractionSource=remember{MutableInteractionSource()}
 val touchObserver by rememberUpdatedState(onSaveTouch)
 LaunchedEffect(saveInteractionSource){
  saveInteractionSource.interactions.collect{event->when(event){
   is PressInteraction.Press->touchObserver(SaveTouchStage.PRESS)
   is PressInteraction.Release->touchObserver(SaveTouchStage.RELEASE)
   is PressInteraction.Cancel->touchObserver(SaveTouchStage.CANCEL)
  }}
 }
 var p by rememberSaveable(existing?.id,stateSaver=editableProfileSaver){mutableStateOf(existing?:Profile())}
 var name by rememberSaveable(existing?.id){mutableStateOf(p.name)}
 var age by rememberSaveable(existing?.id){mutableStateOf(p.age.toString())}
 var height by rememberSaveable(existing?.id){mutableStateOf(p.heightCm.toString())}
 var weight by rememberSaveable(existing?.id){mutableStateOf(p.weightKg.toString())}
 // Keep the primary save reachable while editing long equipment lists or using an IME.
 // On API26 a touch on a scrolled-to footer could be swallowed by focus/keyboard
 // dismissal, leaving a valid profile unsaved with no ViewModel exception.
 // Rebase edited fields onto the current profile: no restored draft may
 // replace a history or measurement that Room saved while the editor lived.
 val candidate=(existing?:p).copy(
  name=name.trim(),age=age.toIntOrNull()?:0,heightCm=number(height)?:0.0,
  weightKg=number(weight)?:0.0,experience=p.experience,equipment=p.equipment,
  limitations=p.limitations,primary=p.primary,secondary=p.secondary,
  priorities=p.priorities,days=p.days,recovery=p.recovery
 )
 // Store only the editable baseline; do not place any history, measurements
 // or active workout in the Activity's saved-state Bundle.
 val baseline by rememberSaveable(existing?.id,stateSaver=editableProfileSaver){
  mutableStateOf((existing?:p).copy(history=emptyList(),measurements=emptyList(),active=null))
 }
 val hasChanges=candidate.copy(history=emptyList(),measurements=emptyList(),active=null)!=baseline
 var confirmDiscard by rememberSaveable(existing?.id){mutableStateOf(false)}
 val keyboardVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
 fun requestCancel(){
  if(hasChanges)confirmDiscard=true else cancel()
 }
 BackHandler(enabled=canCancel){
  when{
   saving->Unit // Durable save is already in flight; no premature screen exit.
   keyboardVisible->{focus.clearFocus(force=true);keyboard?.hide()}
   else->requestCancel()
  }
 }
 if(confirmDiscard)AlertDialog(
  onDismissRequest={confirmDiscard=false},
  title={Text("Несохранённые изменения")},
  text={Text("Изменения профиля ещё не сохранены. Выйти из редактирования без них? История тренировок и замеры останутся без изменений.")},
  confirmButton={TextButton(onClick={confirmDiscard=false;cancel()}){Text("Выйти без сохранения")}},
  dismissButton={TextButton(onClick={confirmDiscard=false}){Text("Продолжить редактирование")}}
 )
 Column(Modifier.fillMaxSize()){
  Box(Modifier.weight(1f)){
   Page(if(existing==null)"Начнём с тебя"else"Твой профиль","Для взрослых. История каждого пользователя изолирована."){
  OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text("Имя")},singleLine=true,
   keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=ImeAction.Done),
   keyboardActions=KeyboardActions(onDone={focus.clearFocus(force=true);keyboard?.hide()}));NumericField("Возраст",age){age=it};NumericField("Рост, см",height){height=it};NumericField("Вес, кг",weight){weight=it}
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
   }
  }
  // MainActivity uses adjustResize: its root already shrinks for the IME.
  // A second imePadding() on the fixed footer can push Save outside that
  // resized root (API30: save.bottom=1584 while root.bottom=990).
  // The weighted form above remains scrollable; the Save target stays fixed.
  Column(Modifier.fillMaxWidth().padding(vertical=8.dp)){
   // Dispatch the durable save before focus/IME changes can recompose the editor.
   // Target the actual Button in UI tests, not its nested text bounds.
   Button(onClick={save(candidate);focus.clearFocus(force=true);keyboard?.hide()},
    modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("profile-save"),
    enabled=candidate.valid()&&!saving,interactionSource=saveInteractionSource){Text("Сохранить профиль")}
   if(saving)Row(Modifier.fillMaxWidth().padding(top=6.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
    CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
    Spacer(Modifier.width(8.dp))
    Text("Сохранение профиля…")
   }
   if(canCancel)TextButton({focus.clearFocus(force=true);keyboard?.hide();requestCancel()},Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("profile-cancel"),enabled=!saving){Text("Отмена")}
  }
 }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun TrainingScreen(p:Profile,place:TrainingPlace,vm:FormaViewModel){val plan=generateProgram(p,place=place);Page("Тренировки","Оборудование, восстановление и твои цели."){
 Panel("Где тренируемся"){
  FlowRow{TrainingPlace.entries.forEach{option->FilterChip(place==option,{vm.trainingPlace(option)},label={Text(option.label)},modifier=Modifier.padding(end=6.dp))}}
  Text("Выбранное место сохраняется и для кнопки «Начать тренировку» на главной. При тренировке дома заловые тренажёры не предлагаются.",style=MaterialTheme.typography.bodySmall)
 }
 if(plan.isEmpty())Text("Выберите дни и проверьте ограничения и восстановление в профиле.")
 plan.forEach{day->TrainingDayCard(day,p,vm,place)}
 // New profiles start with three spaced strength days. Optional cardio is
 // still directly accessible without changing the user's saved schedule.
 if(plan.isNotEmpty() && plan.none{it.cardio!=null})Panel("Кардио по желанию"){
  Text("Дополнительная ходьба или интервалы не обязательны и не добавляются в недельный силовой план.")
  CardioControls(TrainingDay(day=java.time.LocalDate.now().dayOfWeek.value,name="Кардио по желанию",cardio=CardioKind.WALK,durationSec=1200)){vm.start(it)}
 }
 ExerciseTechniqueCatalog(profileId=p.id,available=availableEquipment(p,place))
}}
@Composable fun TrainingDayCard(day:TrainingDay,p:Profile,vm:FormaViewModel,place:TrainingPlace){
 val available=availableEquipment(p,place)
 var replacements by remember(day,p.equipment,place){mutableStateOf<Map<Int,Exercise>>(emptyMap())}
 val chosen=day.copy(exercises=day.exercises.mapIndexed{i,item->replacements[i]?.takeIf{it.equipment in available}?.let{item.copy(exercise=it,weight=0.0,reason="Замена выбрана вручную. Начните с лёгкого веса.")}?:item})
 Panel("${dayNames[day.day-1]} · ${day.name}"){
  Text(day.explanation)
  chosen.exercises.forEachIndexed{i,item->
   Text("${item.exercise.name} · ${item.sets} × ${item.reps}");Text(item.reason,style=MaterialTheme.typography.bodySmall)
   key(item.exercise.id,item.exercise.equipment){
    var techniqueOpen by remember{mutableStateOf(false)}
    TextButton({techniqueOpen=!techniqueOpen},Modifier.fillMaxWidth().heightIn(min=48.dp)){
     Text(if(techniqueOpen)"Скрыть технику: ${item.exercise.name}" else "Техника: ${item.exercise.name}")
    }
    if(techniqueOpen)ExerciseTechnique(item.exercise)
   }
   if(recordsExternalKilograms(item.exercise.equipment))Text("Плановый вес: ${item.weight} ${recordedExerciseLoadUnit(item.exercise)}",style=MaterialTheme.typography.bodySmall)
   else Text(unmeasuredLoadExplanation(item.exercise.equipment),style=MaterialTheme.typography.bodySmall)
   val alternatives=exerciseCatalog.filter{it.id!=item.exercise.id && it.muscle==item.exercise.muscle && it.equipment in availableEquipment(p,place)}
   if(alternatives.isNotEmpty()){var expanded by remember{mutableStateOf(false)};TextButton({expanded=!expanded}){Text("Заменить упражнение")};if(expanded)alternatives.forEach{e->TextButton({replacements=replacements+(i to e);expanded=false}){Text(e.name)}}}
  }
  if(day.cardio!=null)CardioControls(chosen){vm.start(it)}else Action("Начать тренировку",{vm.start(chosen)})
 }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun CardioControls(day:TrainingDay,onStart:(TrainingDay)->Unit){
 val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
 // Only editor inputs belong in Activity saved state; workout state is Room-owned.
 var kindName by rememberSaveable(day.day,day.name){mutableStateOf((day.cardio?:CardioKind.WALK).name)}
 var duration by rememberSaveable(day.day,day.name){mutableStateOf((day.durationSec/60).toString())}
 var walk by rememberSaveable(day.day,day.name){mutableStateOf(day.intervals.getOrNull(0)?.toString()?:"120")}
 var run by rememberSaveable(day.day,day.name){mutableStateOf(day.intervals.getOrNull(1)?.toString()?:"60")}
 val kind=CardioKind.entries.firstOrNull{it.name==kindName}?:CardioKind.WALK
 FlowRow{CardioKind.entries.forEach{k->FilterChip(kind==k,{kindName=k.name},label={Text(k.label)},modifier=Modifier.padding(end=6.dp))}}
 NumericField("Длительность, минут (1–120)",duration){duration=it};if(kind==CardioKind.INTERVAL){NumericField("Ходьба, секунд (10–1800)",walk){walk=it};NumericField("Бег, секунд (10–1800)",run){run=it}}
 val m=duration.toIntOrNull()?:0;val w=walk.toIntOrNull()?:0;val r=run.toIntOrNull()?:0
 Button(onClick={
  // Dispatch the durable workout start BEFORE changing IME/focus state.
  onStart(day.copy(cardio=kind,durationSec=m*60,intervals=listOf(w,r)))
  focus.clearFocus(force=true);keyboard?.hide()
 },modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("cardio-start"),
  enabled=m in 1..120&&w in 10..1800&&r in 10..1800){Text("Начать кардио")}
}

@Composable fun Runner(s:Session,vm:FormaViewModel,onOpenHistory:()->Unit){
 val app=LocalContext.current.applicationContext as FormaApp
 var now by remember{mutableLongStateOf(app.workoutNow())}
 LaunchedEffect(s.id){while(true){kotlinx.coroutines.delay(500);now=app.workoutNow()}}
 var weight by rememberSaveable(s.id,s.exerciseIndex,s.setNumber){mutableStateOf((s.sets.lastOrNull{it.exerciseId==s.current?.exercise?.id}?.weight?:s.current?.weight?:0.0).toString())}
 var reps by rememberSaveable(s.id,s.exerciseIndex,s.setNumber){mutableStateOf((s.sets.lastOrNull{it.exerciseId==s.current?.exercise?.id}?.reps?:s.current?.reps?:12).toString())}
 var effort by rememberSaveable(s.id,s.exerciseIndex,s.setNumber){mutableFloatStateOf(7f)}
 var confirmStop by rememberSaveable(s.id){mutableStateOf(false)}
 if(confirmStop)AlertDialog(onDismissRequest={confirmStop=false},
  title={Text("Остановить тренировку?")},
  text={Text("Сопровождение завершится. Уже записанные подходы останутся в тренировке; для истории подтвердите сохранение на следующем экране.")},
  confirmButton={TextButton({confirmStop=false;vm.stop(s.id)},modifier=Modifier.heightIn(min=48.dp)){Text("Подтвердить остановку",color=MaterialTheme.colorScheme.error)}},
  dismissButton={TextButton({confirmStop=false},modifier=Modifier.heightIn(min=48.dp)){Text("Не останавливать")}})
 val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
 LaunchedEffect(s.id,s.phase,s.exerciseIndex,s.setNumber,s.paused){focus.clearFocus(force=true);keyboard?.hide()}
 key(s.id,s.phase,s.exerciseIndex,s.setNumber,s.paused){
 Page(if(s.paused)"Пауза"else if(s.phase==Phase.WORK)s.current?.exercise?.name?:stageLabel(s) else stageLabel(s),if(s.phase==Phase.WORK)(s.current?.exercise?.muscle?.label?:s.day.name)+" · "+s.sets.size+" сохранённых подходов" else s.day.name+" · "+s.sets.size+" сохранённых подходов",compact=true){
  WorkoutJourneyProgress(s)
  if(s.terminal)Panel("Результат"){
   Text(if(s.phase==Phase.DONE)"Занятие завершено."else"Остановлено. Записанные подходы находятся в текущей сессии. Сохраните занятие в историю.")
   Text("Записано подходов: "+s.sets.size+" · активное время: "+s.activeSeconds/60+" мин "+s.activeSeconds%60+" сек.")
   Action("Сохранить и завершить",{vm.finish(s.id)})
   OutlinedButton({vm.finish(s.id,onOpenHistory)},Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("Сохранить и открыть историю")}
  }else{
   // Always reachable without scrolling past exercise artwork or set cards.
   OutlinedButton({vm.painStop(s.id)},Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("Боль / дискомфорт — стоп")}
   Action(if(s.paused)"Продолжить сопровождение"else"Пауза",{if(s.paused)vm.resume(s.id) else vm.pause(s.id)})
   if(s.phase==Phase.WORK){
    val current=s.current
    if(current!=null){
     Text(current.exercise.muscle.label+" · "+current.exercise.equipment.label,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
     Panel("Как выполнять"){Text(current.exercise.cue)}
      Panel("Текущий подход"){
       Text("Текущий",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
       Text("Подход "+s.setNumber+" из "+current.sets,style=MaterialTheme.typography.bodySmall)
      if(recordsExternalKilograms(current.exercise.equipment))NumericField(recordedLoadInputLabel(current.exercise),weight){weight=it}
      else Text(unmeasuredLoadExplanation(current.exercise.equipment),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
      NumericField("Повторения",reps){reps=it}
      Text("Сложность: "+effort.toInt()+"/10")
      Slider(effort,{effort=it},modifier=Modifier.semantics{contentDescription="Сложность подхода от 1 до 10"},valueRange=1f..10f,steps=8)
      val w=number(weight);val n=reps.toIntOrNull()
      val measuredLoad=recordsExternalKilograms(current.exercise.equipment)
      Action("Подход выполнен",{vm.done(s.id,if(measuredLoad)w?:0.0 else 0.0,n?:1,effort.toInt(),false)},!s.paused&&(!measuredLoad||(w!=null&&w in 0.0..500.0))&&n!=null&&n in 1..100)
     }
     // The detailed read-only list is optional: older phones should show the set action first.
     var setPlanExpanded by rememberSaveable(s.id,s.exerciseIndex){mutableStateOf(false)}
     OutlinedButton({setPlanExpanded=!setPlanExpanded},Modifier.fillMaxWidth().heightIn(min=52.dp)){
      Text(if(setPlanExpanded)"Скрыть план подходов"else"Показать план подходов")
     }
     if(setPlanExpanded)Panel("План и записи подходов"){WorkoutSetCards(s,current)}
     Box(Modifier.fillMaxWidth().height(205.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF0B1B20))){
      FormaAthleteArtwork(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(210.dp))
      Column(Modifier.align(Alignment.CenterEnd).width(108.dp).padding(end=10.dp),horizontalAlignment=Alignment.CenterHorizontally){
       MuscleFocusMap(current.exercise.muscle,Modifier.fillMaxWidth().height(145.dp),secondaryMuscles(current.exercise))
       Text("Схема мышц",style=MaterialTheme.typography.labelSmall,color=Color(0xFFBBD5C5))
      }
      Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent,Color(0xFF091217)))).padding(14.dp)){
       Text("Основная группа: "+current.exercise.muscle.label,style=MaterialTheme.typography.bodyMedium,color=Color.White)
      }
     }
     var techniqueTab by rememberSaveable(s.id,s.exerciseIndex){mutableIntStateOf(1)}
     // Set completion stays above nonessential media; video never autoplays.
     // Until a reviewed exact-ID/equipment media entry is admitted, do not
     // display a dead Video tab. An older restored selection also falls back
     // without opening a stale player or changing Room-owned workout state.
     val hasReviewedVideo=techniqueMediaCatalog.any{
      it.exerciseId==current.exercise.id && it.equipment==current.exercise.equipment
     }
     val visibleTechniqueTab=if(!hasReviewedVideo && techniqueTab==0) 1 else techniqueTab
     WorkoutTechniqueTabs(visibleTechniqueTab,{techniqueTab=it},hasReviewedVideo)
     when(visibleTechniqueTab){
      0->Panel("Демонстрация техники"){ExerciseTechnique(current.exercise,showInstructions=false,initiallyOpen=true)}
      1->Panel("Инструкция и ошибки"){
       Text(current.exercise.cue)
       Text("Частые ошибки",style=MaterialTheme.typography.titleSmall)
       Text(current.exercise.mistakes.ifBlank{"Дополнительные замечания для этого упражнения пока не проверены."})
       AuthoredTechniqueWalkthrough(current.exercise)
      }
      2->Panel("Работа мышц"){
       val secondary=secondaryMuscles(current.exercise)
       WorkoutMuscleViews(current.exercise.muscle,secondary)
       Text("Основная группа: "+current.exercise.muscle.label)
       if(secondary.isNotEmpty())Text("Дополнительно: "+secondary.joinToString{it.label})
       Text("Схематичные зоны нагрузки спереди и сзади. Это не инструкция по движению и не медицинская анатомия.",
        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
      }
     }
    }
   }else{
    Panel("Текущий этап"){
     if(s.phase in listOf(Phase.WARMUP,Phase.REST,Phase.CARDIO)){
      Text((remainingSeconds(s,now)/60).toString().padStart(2,'0')+":"+(remainingSeconds(s,now)%60).toString().padStart(2,'0'),Modifier.semantics{contentDescription="Осталось ${remainingSeconds(s,now)} секунд"},style=MaterialTheme.typography.displayMedium,fontWeight=FontWeight.Black,color=MaterialTheme.colorScheme.primary)
     }
     if(s.phase==Phase.WARMUP)Text("Лёгкая ходьба на месте, плавные движения плеч и таза. Без боли и рывков.")
     if(s.phase==Phase.CARDIO)Text("Комфортный темп. При дискомфорте остановитесь.")
     if(s.phase==Phase.REST)Text("Восстановите дыхание перед следующим подходом.")
    }
   }
   Panel("Другие действия"){
    OutlinedButton({vm.skip(s.id)},Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!s.paused){
     Text(if(s.phase==Phase.WORK)"Пропустить подход"else"Пропустить этап")
    }
    TextButton({confirmStop=true},Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Остановить тренировку")}
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
    matches.take(40).forEach { v ->
     // Selection is a real accessibility state, not only a visual checkmark.
     // Keep the button's 48dp hit target and a stable per-profile semantics key.
     OutlinedButton(
      {vm.select(v.id)},
      Modifier.fillMaxWidth().heightIn(min=48.dp)
       .testTag("profile-choice:${v.id}")
       .semantics { selected=p.id==v.id }
     ) { Text(if(p.id==v.id)"✓ ${v.name}"else v.name) }
    }
    if(matches.size>40)Text("Показаны первые 40. Уточните поиск.",style=MaterialTheme.typography.bodySmall)
    Action("Добавить пользователя",create)
    Text("Это локальные профили. До подключения защищённой синхронизации переносите данные между телефонами через резервные копии.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
 Panel("${p.name} · ${p.age} лет"){Text("${p.heightCm} см · ${p.weightKg} кг");Text("${p.experience.label} · восстановление ${p.recovery}/5");Action("Изменить профиль и цели",edit)}
 val coach by vm.coachStatus.collectAsState()
 val context=LocalContext.current
 Panel("Сопровождение"){
  SwitchRow("Голос Android",settings.voice){vm.toggleVoice()}
  SwitchRow("Звуковые сигналы",settings.sound){vm.toggleSound()}
  SwitchRow("Метроном · ${settings.bpm} уд/мин",settings.metronome){vm.toggleMetronome()}
  Text(coach)
  CoachSettingSlider("Громкость подсказок",settings.volume.toFloat(),"${settings.volume}%",0f..100f){
   vm.coach(it.toInt(),settings.bpm,settings.speechRate)
  }
  CoachSettingSlider("Темп",settings.bpm.toFloat(),"${settings.bpm} уд/мин",40f..180f,steps=27){
   vm.coach(settings.volume,it.toInt(),settings.speechRate)
  }
  CoachSettingSlider("Скорость речи",settings.speechRate,"x${"%.1f".format(settings.speechRate)}",0.7f..1.3f){
   vm.coach(settings.volume,settings.bpm,it)
  }
 TextButton({runCatching{context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))}.onFailure{vm.error.value="Настройки синтеза речи недоступны на этом телефоне"}}){Text("Настроить голос Android")}
 Text("Голос выбирается среди русских офлайн-голосов Android. Общая громкость также зависит от уровня мультимедиа телефона.")}
 Panel("Оформление"){Row{TextButton({vm.dark(false)}){Text("Светлое")};TextButton({vm.dark(true)}){Text("Тёмное")};TextButton({vm.dark(null)}){Text("Система")}}}
 NotificationPanel()
 EncryptedBackupPanel(vm)
 BackupPanel(vm)
 Panel("FORMA · "+FormaUpdateClient(context).installedVersion()){Text("Локальные данные. Удаление приложения удаляет историю. Конфигурация сборки GetFitPro © 2026 warpirate — MIT; уведомление включено в APK.")}
  }
 }
}
/** One accessible switch for the full label + control, with a 56dp tap target. */
/**
 * Named and unit-labelled sliders for TalkBack without extra UI state.
 * The underlying DataStore callback and ranges remain unchanged.
 */
@Composable fun CoachSettingSlider(
 label:String,value:Float,displayValue:String,
 range:ClosedFloatingPointRange<Float>,steps:Int=0,onValueChange:(Float)->Unit
){
 Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(2.dp)){
  Text(label+": "+displayValue)
  Slider(value=value,onValueChange=onValueChange,
   modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics{
    contentDescription=label
    stateDescription=displayValue
   },valueRange=range,steps=steps)
 }
}

@Composable fun SwitchRow(label:String,value:Boolean,change:(Boolean)->Unit){
 Row(Modifier.fillMaxWidth().heightIn(min=56.dp).toggleable(value=value,role=Role.Switch,onValueChange=change),
  horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
  Text(label,Modifier.weight(1f))
  // Parent owns OnClick and role; avoid two nested toggle targets for TalkBack.
  Switch(checked=value,onCheckedChange=null)
 }
}


@Composable fun BackupPanel(vm:FormaViewModel){
 val selectedProfile by vm.current.collectAsState()
 var stagedProfileId by remember{mutableStateOf<String?>(null)}
 var stagedAllProfileIds by remember{mutableStateOf<Set<String>?>(null)}
 val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
  val chosen=stagedProfileId
  stagedProfileId=null
  uri?.let{vm.exportBackup(it,expectedProfileId=chosen)}
 }
 val exportAll=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
  val chosen=stagedAllProfileIds
  stagedAllProfileIds=null
  uri?.let{vm.exportBackup(it,allProfiles=true,expectedProfileIds=chosen)}
 }
 val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let(vm::importBackup)}
 var confirm by remember{mutableStateOf(false)}
 var confirmAll by remember{mutableStateOf(false)}
 Panel("JSON без шифрования · совместимость"){
  Text("Этот формат НЕ шифруется и содержит персональные данные. Используйте его только для совместимости со старыми копиями; для нового переноса рекомендуем защищённый файл выше. FORMA повторно проверит записанный JSON.")
  Action("Экспортировать текущий профиль",{
   stagedProfileId=selectedProfile?.id
   export.launch("FORMA-profile-${java.time.LocalDate.now()}.json")
  },selectedProfile!=null)
  TextButton({confirmAll=true},Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Экспортировать все локальные профили")}
  OutlinedButton({confirm=true},Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Восстановить из файла")}
 }
 if(confirmAll)AlertDialog(onDismissRequest={confirmAll=false},title={Text("Экспорт всех пользователей")},text={Text("Копия будет включать тренировки, замеры и другие персональные данные ВСЕХ локальных пользователей этого телефона. Делитесь таким файлом только с разрешения каждого владельца данных.")},confirmButton={TextButton({
  confirmAll=false
  stagedAllProfileIds=vm.profiles.value.map{it.id}.toSet()
  exportAll.launch("FORMA-all-profiles-${java.time.LocalDate.now()}.json")
 }){Text("Создать общую копию")}},dismissButton={TextButton({confirmAll=false}){Text("Отмена")}})
 if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Восстановление копии")},text={Text("Профили добавятся как отдельные копии. Текущие данные не заменяются. Повторный импорт создаёт ещё одну копию каждого профиля. Файл до 32 МБ.")},confirmButton={TextButton({confirm=false;import.launch(arrayOf("application/json","text/plain","application/octet-stream"))}){Text("Выбрать файл")}},dismissButton={TextButton({confirm=false}){Text("Отмена")}})
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
