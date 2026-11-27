package ru.forma.app

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.Exercise
import ru.forma.domain.exerciseCatalog

/**
 * Offline, synthetic pixel evidence from the actual GLSurfaceView (API26+).
 * Compose semantics alone cannot establish that the separate GL Surface drew.
 * Screenshots contain no personal profile, workout, or downloaded asset.
 */
@RunWith(AndroidJUnit4::class)
class OriginalVolumetricPreviewTest {
 @get:Rule val rule=createComposeRule()

 private fun findGl(v:View?):GLSurfaceView? {
  if(v is GLSurfaceView)return v
  if(v is ViewGroup)for(i in 0 until v.childCount) {
   findGl(v.getChildAt(i))?.let{return it}
  }
  return null
 }

 private fun pixelCopy(surface:GLSurfaceView):Bitmap? {
  val out=Bitmap.createBitmap(surface.width,surface.height,Bitmap.Config.ARGB_8888)
  val result=AtomicInteger(-1)
  val complete=CountDownLatch(1)
  try {
   PixelCopy.request(surface,out,{code->
    result.set(code)
    complete.countDown()
   },Handler(Looper.getMainLooper()))
  } catch (_:IllegalArgumentException) {
   // The Surface may have been created but its first buffer not yet posted.
   out.recycle()
   return null
  }
  if(!complete.await(4,TimeUnit.SECONDS)||result.get()!=PixelCopy.SUCCESS) {
   out.recycle()
   return null
  }
  return out
 }

 private fun litSamples(bitmap:Bitmap):Int {
  var lit=0
  for(y in 0 until bitmap.height step 4)
   for(x in 0 until bitmap.width step 4) {
    if(isMeshPixel(bitmap.getPixel(x,y)))lit++
   }
  return lit
 }

 // Renderer clear color is RGB(6,13,17). Bright pixels identify the actual mesh.
 private fun isMeshPixel(color:Int)=Color.red(color)>38||Color.green(color)>52||Color.blue(color)>52

 private fun assertReadableFraming(bitmap:Bitmap) {
  val meshRows=(0 until bitmap.height step 4).filter{y->
   (0 until bitmap.width step 4).any{x->isMeshPixel(bitmap.getPixel(x,y))}
  }
  assertTrue("3D mesh should fill most of the preview height",
   meshRows.last()-meshRows.first()>=bitmap.height*0.60f)
  assertTrue("3D mesh should retain top and bottom margins",
   meshRows.first()>bitmap.height*0.02f&&meshRows.last()<bitmap.height*0.98f)
 }

 private fun differingSamples(a:Bitmap,b:Bitmap):Int {
  var different=0
  for(y in 0 until minOf(a.height,b.height) step 4)
   for(x in 0 until minOf(a.width,b.width) step 4) {
    val ca=a.getPixel(x,y)
    val cb=b.getPixel(x,y)
    val delta=kotlin.math.abs(Color.red(ca)-Color.red(cb))+
     kotlin.math.abs(Color.green(ca)-Color.green(cb))+
     kotlin.math.abs(Color.blue(ca)-Color.blue(cb))
    if(delta>45)different++
   }
  return different
 }

 private fun awaitRenderedMesh(surface:GLSurfaceView,previous:Bitmap?=null):Bitmap {
  repeat(9) {
   surface.requestRender()
   Thread.sleep(180)
   val capture=pixelCopy(surface)
   if(capture!=null) {
    val drawn=litSamples(capture)>35
    val changed=previous==null || differingSamples(previous,capture)>35
    if(drawn&&changed)return capture
    capture.recycle()
   }
  }
  fail("GLSurfaceView did not provide distinct visible mesh pixels after bounded render requests")
  error("unreachable")
 }

 private fun saveSyntheticFrame(bitmap:Bitmap,name:String) {
  val dir=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"evidence")
  assertTrue("Could not create private synthetic evidence dir",dir.isDirectory||dir.mkdirs())
  File(dir,name).outputStream().use {
   assertTrue("Unable to write PNG PixelCopy evidence",bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
  }
 }

 private fun showExercise(exercise:Exercise,options:State<FormaVisualOptions>):AtomicReference<View> {
  val root=AtomicReference<View>()
  rule.setContent {
   val host=LocalView.current
   SideEffect { root.set(host.rootView) }
   CompositionLocalProvider(LocalFormaVisualOptions provides options.value) {
    MaterialTheme {
     Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){
      OriginalVolumetricPreview(exercise)
     }
    }
   }
  }
  return root
 }

 private fun readySurface(root:AtomicReference<View>):GLSurfaceView {
  rule.waitUntil(timeoutMillis=12000) {
   findGl(root.get())?.let {
    it.width>80 && it.height>80 && it.holder.surface.isValid
   }==true
  }
  return checkNotNull(findGl(root.get()))
 }

 @Test fun threeDimensionalSurfaceOnlyOpensOnRequestAndManualControlsRemainAccessible() {
  val options=mutableStateOf(FormaVisualOptions())
  val root=showExercise(exerciseCatalog.first(),options)
  rule.onNodeWithTag("volume-preview-surface").assertDoesNotExist()
  rule.onNodeWithTag("volume-preview-toggle").assertExists().performScrollTo().performClick()
  rule.onNodeWithTag("volume-preview-surface").performScrollTo().assertIsDisplayed()
  rule.onNodeWithText("Независимая проверка техники ещё не завершена.",substring=true).assertExists()
  val orbit=rule.onNodeWithTag("volume-preview-orbit")
   .assertContentDescriptionEquals("Поворот объёмной модели")
   .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"55°"))
  val scrub=rule.onNodeWithTag("volume-preview-scrub")
   .assertContentDescriptionEquals("Фаза движения")
   .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Исходное положение"))

  val gl=readySurface(root)
  val first=awaitRenderedMesh(gl)
  assertReadableFraming(first)
  saveSyntheticFrame(first,"volume-gl-start.png")

  // Use the same manual controls that a user has: no autoplay, no network.
  scrub.performSemanticsAction(SemanticsActions.SetProgress){it(1f)}
  orbit.performSemanticsAction(SemanticsActions.SetProgress){it(-130f)}
  rule.waitForIdle()
  scrub.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Крайнее положение модели"))
  orbit.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"-130°"))
  val rotated=awaitRenderedMesh(gl,first)
  saveSyntheticFrame(rotated,"volume-gl-orbit.png")
  first.recycle()
  rotated.recycle()

  rule.onNodeWithTag("volume-preview-play").performScrollTo().performClick()
  rule.onNodeWithText("Пауза 3D").assertExists()
  rule.onNodeWithTag("volume-preview-play").performScrollTo().performClick()
  rule.onNodeWithText("Воспроизвести 3D").assertExists()
  rule.onNodeWithText("Без движения").performScrollTo().performClick()
  rule.onNodeWithTag("volume-preview-play").assertIsNotEnabled()
  // A global accessibility preference cannot be bypassed by this chip.
  rule.runOnIdle { options.value=FormaVisualOptions(reducedMotion=true) }
  rule.onNodeWithTag("volume-preview-play").assertIsNotEnabled()
  rule.onNodeWithText("Без движения").assertIsNotEnabled()
  rule.onNodeWithTag("volume-preview-scrub").assertExists()
  // Turning off the global preference restores a user-controlled manual mode.
  rule.runOnIdle { options.value=FormaVisualOptions() }
  rule.onNodeWithText("Без движения").performScrollTo().performClick()
  rule.onNodeWithTag("volume-preview-play").assertIsEnabled()
  rule.onNodeWithTag("volume-preview-toggle").performScrollTo().performClick()
  rule.onNodeWithTag("volume-preview-surface").assertDoesNotExist()
 }

 @Test fun wallSlideRendersDistinctManualPosesWithoutChangingCamera() {
  val wallSlide=exerciseCatalog.single{it.id=="wall_slide"}
  val root=showExercise(wallSlide,mutableStateOf(FormaVisualOptions()))
  rule.onNodeWithTag("volume-preview-toggle").performScrollTo().performClick()
  rule.onNodeWithTag("volume-preview-surface").performScrollTo().assertIsDisplayed()
  val gl=readySurface(root)
  val first=awaitRenderedMesh(gl)
  try {
   saveSyntheticFrame(first,"wall-slide-start.png")
   val scrub=rule.onNodeWithTag("volume-preview-scrub")
   scrub.performSemanticsAction(SemanticsActions.SetProgress){it(1f)}
   rule.waitForIdle()
   scrub.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Крайнее положение модели"))
   val raised=awaitRenderedMesh(gl,first)
   try {
    saveSyntheticFrame(raised,"wall-slide-raised.png")
   } finally {
    raised.recycle()
   }
  } finally {
   first.recycle()
  }
 }
}
