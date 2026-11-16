package ru.forma.app

import kotlinx.coroutines.launch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.forma.domain.*
import kotlin.math.min

/**
 * Entirely hand-authored vector rig (no external footage, frames, poses,
 * tracking files or copyrighted character assets). Motion is schematic,
 * not yet verified by a qualified coach. Explicit play; no autoplay.
 */
@Composable
internal fun OriginalMotionDemo(exercise: Exercise) {
 val spec=remember(exercise.id, exercise.equipment) { originalMotionFor(exercise) } ?: return
 key(exercise.id,exercise.equipment) {
  var playing by rememberSaveable { mutableStateOf(false) }
  val reducedMotion=LocalFormaVisualOptions.current.reducedMotion
  LaunchedEffect(reducedMotion){if(reducedMotion)playing=false}
  val phase=remember { Animatable(0f) }
  val scope=rememberCoroutineScope()
  // A single infiniteRepeatable animation is cancellable by LaunchedEffect;
  // unlike a hand-written while(true) of finite animations, it has no
  // composition-slot insertion/removal and retains its current phase on pause.
  LaunchedEffect(playing) {
   if(playing) phase.animateTo(
    targetValue=1f,
    animationSpec=infiniteRepeatable(
     animation=tween(1700,easing=LinearEasing),repeatMode=RepeatMode.Reverse))
  }
  val fraction=phase.value
  val frame=originalMotionAt(spec,fraction)
  val skeleton=MaterialTheme.colorScheme.onSurface
  val accent=MaterialTheme.colorScheme.primary
  val equipmentColor=MaterialTheme.colorScheme.tertiary
  val quiet=MaterialTheme.colorScheme.outline
  ElevatedCard(Modifier.fillMaxWidth().testTag("original-motion-card")) {
   Column(Modifier.fillMaxWidth().padding(14.dp),
    verticalArrangement=Arrangement.spacedBy(8.dp)) {
    Text("Авторская анимация движения",style=MaterialTheme.typography.titleSmall)
    Text("Условный вид сбоку · направление движения, не видео и не аттестованная техника. " +
     "Учитывайте текст, оборудование и собственную комфортную амплитуду.",
     style=MaterialTheme.typography.bodySmall,
     color=MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().height(204.dp).testTag("original-motion-canvas")
     .semantics { contentDescription="Условная анимированная схема: ${exercise.name}. ${spec.view}. Для доступного описания используйте текстовые шаги." }) {
     val k=min(size.width/120f,size.height/116f)
     val ox=(size.width-120f*k)/2f
     val oy=(size.height-116f*k)/2f
     fun xy(x:Float,y:Float)=Offset(ox+x*k,oy+y*k)
     fun p(i:Int)=xy(frame.joints[i].x,frame.joints[i].y)
     fun bone(a:Offset,b:Offset,color:androidx.compose.ui.graphics.Color=skeleton,width:Float=4f) {
      drawLine(color=color,start=a,end=b,strokeWidth=width*k,cap=StrokeCap.Round)
     }
     bone(xy(8f,109f),xy(113f,109f),quiet,0.8f)
     // This exact variant is performed with the back to a fixed wall.
     // Draw the wall behind the body/arms, not on top of the active joints.
     if(spec.exerciseId=="wall_slide")
      bone(xy(49f,12f),xy(49f,109f),quiet,2f)
     // Rear arm/leg are intentionally faint; this is a side-view schematic.
     val rear=quiet.copy(alpha=0.6f)
     bone(p(1)+Offset(-3*k,2*k),p(2)+Offset(-3*k,2*k),rear,3f)
     bone(p(2)+Offset(-3*k,2*k),p(3)+Offset(-3*k,2*k),rear,3f)
     bone(p(4)+Offset(-4*k,1*k),p(5)+Offset(-4*k,1*k),rear,3f)
     bone(p(5)+Offset(-4*k,1*k),p(6)+Offset(-4*k,1*k),rear,3f)
     // Main spine and controlled active limbs.
     bone(p(1),p(4),skeleton,6f)
     bone(p(1),p(2),accent,4.3f)
     bone(p(2),p(3),accent,4.3f)
     bone(p(4),p(5),skeleton,5f)
     bone(p(5),p(6),skeleton,4f)
     bone(p(0),p(1),skeleton,3.5f)
     drawCircle(color=skeleton,center=p(0),radius=6.4f*k)
     listOf(1,2,4,5).forEach { drawCircle(color=accent,center=p(it),radius=3.2f*k) }
     // Show the relevant reference surface, not a copied exercise clip.
     if(spec.exerciseId=="wall_push")bone(xy(104f,16f),xy(104f,107f),quiet,2f)
     if(spec.exerciseId=="incline_push")bone(xy(98f,64f),xy(116f,64f),equipmentColor,3f)
     if(spec.exerciseId=="chair_squat") {
      bone(xy(23f,80f),xy(53f,80f),equipmentColor,2.6f)
      bone(xy(25f,80f),xy(25f,107f),equipmentColor,2.4f)
     }
     // Distinct simple apparatus. Neither machine nor load is dimensionally realistic.
     when(spec.equipment) {
      Equipment.DUMBBELL -> {
       val grip=p(3)
       bone(grip+Offset(-9*k,0f),grip+Offset(9*k,0f),equipmentColor,2f)
       drawCircle(equipmentColor,3.4f*k,grip+Offset(-9*k,0f))
       drawCircle(equipmentColor,3.4f*k,grip+Offset(9*k,0f))
      }
      Equipment.BARBELL ->
       bone(p(3)+Offset(-17*k,0f),p(3)+Offset(17*k,0f),equipmentColor,4f)
      Equipment.SMITH_MACHINE -> {
       // The guided bar belongs across the shoulder, never in the hand
       // moving freely forward as in an unloaded air squat.
       bone(p(1)+Offset(-17*k,0f),p(1)+Offset(17*k,0f),equipmentColor,4f)
       bone(xy(42f,12f),xy(42f,108f),quiet,1.8f)
       bone(xy(81f,12f),xy(81f,108f),quiet,1.8f)
      }
      Equipment.KETTLEBELL ->
       drawCircle(equipmentColor,5.5f*k,p(3)+Offset(0f,5*k))
      Equipment.RESISTANCE_BAND -> {
       bone(p(3),xy(if(spec.kind==MotionKind.BAND_ROW)105f else 13f,56f),equipmentColor,1.7f)
       drawCircle(equipmentColor,2.4f*k,xy(if(spec.kind==MotionKind.BAND_ROW)105f else 13f,56f))
      }
      Equipment.PULL_UP_BAR -> bone(xy(30f,12f),xy(75f,12f),equipmentColor,3f)
      Equipment.BENCH -> bone(xy(69f,87f),xy(110f,87f),equipmentColor,3.5f)
      Equipment.MAT -> bone(xy(11f,108f),xy(109f,108f),equipmentColor,2.5f)
      Equipment.CABLE_MACHINE,Equipment.LAT_PULLDOWN,Equipment.LEG_PRESS_MACHINE,
      Equipment.LEG_CURL_MACHINE,Equipment.SEATED_ROW_MACHINE,Equipment.CHEST_PRESS_MACHINE ->
       bone(xy(112f,15f),xy(112f,106f),equipmentColor,2f)
      else -> Unit
     }
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
     OutlinedButton(
      onClick={playing=!playing},
      enabled=!reducedMotion,
      modifier=Modifier.heightIn(min=48.dp).testTag("original-motion-play")) {
      Text(if(playing)"Пауза" else "Воспроизвести схему")
     }
    }
    Slider(
     value=fraction.coerceIn(0f,1f),
     onValueChange={ v -> playing=false;scope.launch { phase.snapTo(v) } },
     modifier=Modifier.fillMaxWidth().testTag("original-motion-scrubber"),
     valueRange=0f..1f
    )
    Text((if(reducedMotion) "Уменьшение движения включено. Используйте ручную прокрутку. "
      else "Прокрутите движение вручную или запустите плавный цикл. ") +
     "При боли остановитесь; точные углы и ограничения индивидуальны.",
     style=MaterialTheme.typography.bodySmall,
     color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
 }
}
