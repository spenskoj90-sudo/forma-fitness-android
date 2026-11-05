package ru.forma.app

import android.content.Context
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** No sound, focus requests, autoplay, persistence or workout callbacks. */
private class TechniquePlayback(
 private val media:VerifiedTechniqueMedia,
 private val status:(Boolean,Boolean)->Unit,
 private val dimensions:(Float)->Unit,
 private val rendered:(Boolean)->Unit,
 private val failure:()->Unit
) {
 private var player:MediaPlayer?=null
 private var prepared=false
 private var disposed=false
 fun view(context:Context)=SurfaceView(context).apply{
  holder.addCallback(object:SurfaceHolder.Callback {
   override fun surfaceCreated(holder:SurfaceHolder){attach(holder)}
   override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int)=Unit
   override fun surfaceDestroyed(holder:SurfaceHolder){detach()}
  })
 }
 private fun attach(holder:SurfaceHolder){
  if(disposed)return
  detach()
  val next=MediaPlayer();player=next
  try {
   next.setVolume(0f,0f);next.isLooping=true;next.setDisplay(holder)
   next.setOnVideoSizeChangedListener{_,w,h->if(w>0 && h>0)dimensions(w.toFloat()/h)}
   next.setOnInfoListener{active,what,_->
    if(!disposed && player===active && what==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START)rendered(true)
    false
   }
   next.setOnPreparedListener{
    if(!disposed && player===it){prepared=true;status(true,false)}
   }
   next.setOnErrorListener{failed,_,_->
    if(!disposed && player===failed){detach();failure()};true
   }
   next.setDataSource(media.dataSource());next.prepareAsync()
  }catch(_:Exception){detach();if(!disposed)failure()}
 }
 fun toggle(){
  val current=player?:return
  if(!prepared || disposed)return
  try{if(current.isPlaying)current.pause() else current.start();status(true,current.isPlaying)}
  catch(_:Exception){detach();failure()}
 }
 fun pause(){
  val current=player?:return
  if(prepared)try{if(current.isPlaying)current.pause();status(true,false)}
   catch(_:Exception){detach();if(!disposed)failure()}
 }
 private fun detach(){
  val old=player;player=null;prepared=false
  old?.setOnPreparedListener(null);old?.setOnErrorListener(null);old?.setOnVideoSizeChangedListener(null);old?.setOnInfoListener(null)
  old?.release()
  if(!disposed){rendered(false);status(false,false)}
 }
 fun dispose(){disposed=true;detach()}
}

@Composable internal fun TechniqueVideo(media:VerifiedTechniqueMedia,name:String,onFailure:()->Unit){
 var prepared by remember(media){mutableStateOf(false)}
 var playing by remember(media){mutableStateOf(false)}
 var rendered by remember(media){mutableStateOf(false)}
 var ratio by remember(media){mutableFloatStateOf(16f/9f)}
 val currentFailure by rememberUpdatedState(onFailure)
 val controller=remember(media){TechniquePlayback(media,{ready,active->prepared=ready;playing=active},{ratio=it},{rendered=it},{currentFailure()})}
 val owner=LocalLifecycleOwner.current
 DisposableEffect(controller,owner){
  val observer=LifecycleEventObserver{_,event->when(event){
   Lifecycle.Event.ON_PAUSE,Lifecycle.Event.ON_STOP->controller.pause()
   Lifecycle.Event.ON_DESTROY->controller.dispose()
   else->Unit
  }}
  owner.lifecycle.addObserver(observer)
  onDispose{owner.lifecycle.removeObserver(observer);controller.dispose()}
 }
 Box(Modifier.fillMaxWidth().height(220.dp).background(Color.Black),contentAlignment=Alignment.Center){
  AndroidView(factory={controller.view(it)},modifier=Modifier.aspectRatio(ratio).semantics{
   contentDescription="Демонстрация техники: $name"
  })
 }
 Text(if(playing && rendered)"Демонстрация воспроизводится" else if(playing)"Запуск демонстрации…" else if(prepared)"Демонстрация готова к воспроизведению" else "Подготовка демонстрации…")
 Text("Видео без звука. Текстовые подписи доступны ниже.",style=MaterialTheme.typography.bodySmall)
 OutlinedButton({controller.toggle()},Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=prepared){
  Text(if(playing)"Приостановить демонстрацию" else "Воспроизвести демонстрацию")
 }
}
