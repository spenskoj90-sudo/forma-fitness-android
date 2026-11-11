package ru.forma.app

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import ru.forma.domain.Exercise
import ru.forma.domain.VolumeTriangles
import ru.forma.domain.originalVolumeAt
import ru.forma.domain.originalVolumeTriangles
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/**
 * A real 3-D triangle renderer, not a 2-D Canvas projection or copied model.
 * Source geometry is generated offline for an exact exercise/equipment pair.
 * SOURCE_ONLY until medically grounded qualified technique review per exercise.
 */
private class Original3DRenderer : GLSurfaceView.Renderer {
 @Volatile var triangles: FloatArray = floatArrayOf()
 @Volatile var cameraAngle: Float = 25f
 private var width=1
 private var height=1
 private var program=0
 private var positionAttribute=0
 private var normalAttribute=0
 private var colorAttribute=0
 private var matrixUniform=0
 private val vertexShader = """
  uniform mat4 uMvp;
  attribute vec3 aPosition;
  attribute vec3 aNormal;
  attribute vec3 aColor;
  varying vec3 vColor;
  varying float vBrightness;
  void main() {
   gl_Position = uMvp * vec4(aPosition, 1.0);
   vec3 light = normalize(vec3(0.4, 0.7, 0.8));
   vBrightness = 0.40 + 0.60 * max(dot(normalize(aNormal), light), 0.0);
   vColor = aColor;
  }
 """
 private val fragmentShader = """
  precision mediump float;
  varying vec3 vColor;
  varying float vBrightness;
  void main() {
   gl_FragColor = vec4(vColor * vBrightness, 1.0);
  }
 """
 private fun shader(type:Int,source:String):Int {
  val result=GLES20.glCreateShader(type)
  require(result!=0) { "OpenGL ES shader unavailable" }
  GLES20.glShaderSource(result,source)
  GLES20.glCompileShader(result)
  val compiled=IntArray(1)
  GLES20.glGetShaderiv(result,GLES20.GL_COMPILE_STATUS,compiled,0)
  if(compiled[0]==0){
   val log=GLES20.glGetShaderInfoLog(result)
   GLES20.glDeleteShader(result)
   throw IllegalStateException("FORMА 3D shader compile failed: " + log.take(200))
  }
  return result
 }
 override fun onSurfaceCreated(gl:javax.microedition.khronos.opengles.GL10?,config:javax.microedition.khronos.egl.EGLConfig?) {
  GLES20.glClearColor(0.025f,0.052f,0.065f,1f)
  GLES20.glEnable(GLES20.GL_DEPTH_TEST)
  val vert=shader(GLES20.GL_VERTEX_SHADER,vertexShader)
  val frag=shader(GLES20.GL_FRAGMENT_SHADER,fragmentShader)
  program=GLES20.glCreateProgram()
  GLES20.glAttachShader(program,vert)
  GLES20.glAttachShader(program,frag)
  GLES20.glLinkProgram(program)
  GLES20.glDeleteShader(vert)
  GLES20.glDeleteShader(frag)
  val linked=IntArray(1)
  GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0)
  check(linked[0]!=0) { "FORMА 3D shader link failed" }
  positionAttribute=GLES20.glGetAttribLocation(program,"aPosition")
  normalAttribute=GLES20.glGetAttribLocation(program,"aNormal")
  colorAttribute=GLES20.glGetAttribLocation(program,"aColor")
  matrixUniform=GLES20.glGetUniformLocation(program,"uMvp")
 }
 override fun onSurfaceChanged(gl:javax.microedition.khronos.opengles.GL10?,w:Int,h:Int) {
  width=w.coerceAtLeast(1);height=h.coerceAtLeast(1)
  GLES20.glViewport(0,0,width,height)
 }
 override fun onDrawFrame(gl:javax.microedition.khronos.opengles.GL10?) {
  GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
  val vertices=triangles
  if(vertices.isEmpty() || program==0)return
  val buffer=ByteBuffer.allocateDirect(vertices.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
  buffer.put(vertices)
  buffer.position(0)
  val projection=FloatArray(16)
  val view=FloatArray(16)
  val matrix=FloatArray(16)
  Matrix.perspectiveM(projection,0,45f,width.toFloat()/height,0.1f,100f)
  val angle=Math.toRadians(cameraAngle.toDouble())
  Matrix.setLookAtM(view,0,(sin(angle)*22.0).toFloat(),7.6f,(cos(angle)*22.0).toFloat(),
   0f,5.0f,0f,0f,1f,0f)
  Matrix.multiplyMM(matrix,0,projection,0,view,0)
  GLES20.glUseProgram(program)
  GLES20.glUniformMatrix4fv(matrixUniform,1,false,matrix,0)
  val stride=9*4
  buffer.position(0)
  GLES20.glEnableVertexAttribArray(positionAttribute)
  GLES20.glVertexAttribPointer(positionAttribute,3,GLES20.GL_FLOAT,false,stride,buffer)
  buffer.position(3)
  GLES20.glEnableVertexAttribArray(normalAttribute)
  GLES20.glVertexAttribPointer(normalAttribute,3,GLES20.GL_FLOAT,false,stride,buffer)
  buffer.position(6)
  GLES20.glEnableVertexAttribArray(colorAttribute)
  GLES20.glVertexAttribPointer(colorAttribute,3,GLES20.GL_FLOAT,false,stride,buffer)
  GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,vertices.size/9)
  GLES20.glDisableVertexAttribArray(positionAttribute)
  GLES20.glDisableVertexAttribArray(normalAttribute)
  GLES20.glDisableVertexAttribArray(colorAttribute)
 }
}

/** On-demand GLSurfaceView: paused 3-D never burns a continuous rendering loop. */
private class Original3DSurface(context:Context):GLSurfaceView(context) {
 private val shapeRenderer=Original3DRenderer()
 init {
  setEGLContextClientVersion(2)
  setRenderer(shapeRenderer)
  renderMode=RENDERMODE_WHEN_DIRTY
  preserveEGLContextOnPause=true
 }
 fun show(mesh:VolumeTriangles,angle:Float) {
  shapeRenderer.triangles=mesh.vertices
  shapeRenderer.cameraAngle=angle
  requestRender()
 }
}

@Composable
internal fun OriginalVolumetricPreview(exercise:Exercise) {
 val context=LocalContext.current
 val visual=LocalFormaVisualOptions.current
 val supported=remember(context) {
  (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
   .deviceConfigurationInfo.reqGlEsVersion>=0x20000
 }
 if(!supported || originalVolumeAt(exercise,0f)==null)return
 key(exercise.id,exercise.equipment) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  OutlinedButton(
   onClick={expanded=!expanded},
   modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("volume-preview-toggle")
  ){Text(if(expanded)"Скрыть 3D-прототип" else "Открыть 3D-прототип")}
  if(expanded) {
   var playing by remember { mutableStateOf(false) } // never auto-play after process recreation
   var still by rememberSaveable { mutableStateOf(visual.reducedMotion) }
   LaunchedEffect(visual.reducedMotion) {
    if(visual.reducedMotion){still=true;playing=false}
   }
   var angle by rememberSaveable { mutableFloatStateOf(25f) }
   val phase=remember { Animatable(0f) }
   val scope=rememberCoroutineScope()
   val lifecycle=LocalLifecycleOwner.current
   val view=remember(context){Original3DSurface(context)}
   DisposableEffect(lifecycle,view){
    val observer=LifecycleEventObserver{_,event ->
     when(event){
      Lifecycle.Event.ON_PAUSE,Lifecycle.Event.ON_STOP -> {playing=false;view.onPause()}
      Lifecycle.Event.ON_RESUME -> view.onResume()
      else -> Unit
     }
    }
    lifecycle.lifecycle.addObserver(observer)
    if(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
    onDispose {lifecycle.lifecycle.removeObserver(observer);view.onPause()}
   }
   val motionDisabled=still || visual.reducedMotion
   LaunchedEffect(playing,motionDisabled){
    if(playing && !motionDisabled) {
     phase.animateTo(1f,animationSpec=infiniteRepeatable(
      animation=tween(2200,easing=LinearEasing),repeatMode=RepeatMode.Reverse))
    }
   }
   val scene=remember(exercise.id,exercise.equipment,phase.value) {
    originalVolumeAt(exercise,phase.value)
   }
   val mesh=remember(scene){scene?.let(::originalVolumeTriangles)}
   ElevatedCard(Modifier.fillMaxWidth().testTag("volume-preview-card")){
    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
     Text("Авторская объёмная 3D-модель · прототип",style=MaterialTheme.typography.titleSmall)
     Text("Независимая проверка техники ещё не завершена. Не используйте эту модель как руководство по безопасной амплитуде.",
      style=MaterialTheme.typography.bodySmall,
      color=MaterialTheme.colorScheme.onSurfaceVariant)
     if(mesh!=null)AndroidView(
      factory={view},
      update={it.show(mesh,angle)},
      modifier=Modifier.fillMaxWidth().height(236.dp).testTag("volume-preview-surface")
       .semantics { contentDescription="Объёмная модель движения: " + exercise.name + ". Прототип, техника не подтверждена." }
     )
     Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
      OutlinedButton(onClick={playing=!playing},enabled=!motionDisabled,
       modifier=Modifier.heightIn(min=48.dp).testTag("volume-preview-play")){
       Text(if(playing)"Пауза 3D" else "Воспроизвести 3D")
      }
      FilterChip(selected=motionDisabled,
       onClick={if(!visual.reducedMotion){still=!still;playing=false}},
       enabled=!visual.reducedMotion,
       label={Text("Без движения")},modifier=Modifier.heightIn(min=48.dp))
     }
     Text("Фаза движения",style=MaterialTheme.typography.labelMedium)
     Slider(value=phase.value.coerceIn(0f,1f),
      onValueChange={v->playing=false;scope.launch{phase.snapTo(v)}},
      modifier=Modifier.fillMaxWidth().testTag("volume-preview-scrub"))
     Text("Поворот объёмной модели",style=MaterialTheme.typography.labelMedium)
     Slider(value=angle,onValueChange={angle=it},valueRange= -180f..180f,
      modifier=Modifier.fillMaxWidth().testTag("volume-preview-orbit"))
     Text("Текстовые шаги и типичные ошибки находятся ниже. При боли прекратите упражнение.",
      style=MaterialTheme.typography.bodySmall)
    }
   }
  }
 }
}
