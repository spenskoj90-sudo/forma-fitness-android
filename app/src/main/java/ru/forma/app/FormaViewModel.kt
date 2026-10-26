package ru.forma.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.forma.domain.*

class FormaViewModel(application:Application):AndroidViewModel(application){
 private val app=application as FormaApp
 val error=app.serviceError
 val notice=MutableStateFlow<String?>(null)
 val coachStatus=app.coachStatus
 val profiles=app.repository.profiles.catch{error.value="Не удалось прочитать локальные данные"}.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
 val prefs=app.settings.settings.stateIn(viewModelScope,SharingStarted.Eagerly,Preferences())
 val current=combine(profiles,prefs){ps,s->ps.firstOrNull{it.id==s.selected}?:ps.firstOrNull()}.stateIn(viewModelScope,SharingStarted.Eagerly,null)
 // Recovery applies only to sessions last ticked by an older process.
 // A late init coroutine must never re-pause a workout the Owner just resumed.
 init{action{if(!app.serviceRunning)app.repository.profiles.first().filter{it.active?.terminal==false}.forEach{profile->
  app.repository.mutateSession(profile.id,profile.active!!.id){recoverIfPreviousProcess(it,app.processStartedMs,app.workoutNow())}
 }}}
 private fun action(block:suspend()->Unit){viewModelScope.launch{try{block()}catch(e:CancellationException){throw e}catch(e:Exception){error.value=e.message?:"Ошибка сохранения"}}}
 fun select(id:String){action{require(app.repository.profiles.first().none{it.active!=null}){"Сначала завершите тренировку"};app.settings.select(id)}}
 fun saveProfile(p:Profile,onSaved:()->Unit={}){action{require(p.valid()){ "Проверьте имя, возраст (18–100), рост и вес" };if(app.repository.load(p.id)==null)app.repository.saveProfile(p)else app.repository.mutate(p.id){p.copy(history=it.history,measurements=it.measurements,active=it.active)};app.settings.select(p.id);onSaved()}}
 fun start(day:TrainingDay){val p=current.value?:return;action{val s=app.repository.start(p.id,day,app.workoutNow());app.audioFocusEpoch.invalidate();startServiceOrPause(p.id,s.id)}}
 private fun startService(id:String,session:String){ContextCompat.startForegroundService(app,Intent(app,WorkoutService::class.java).putExtra("profile",id).putExtra("session",session))}
 private suspend fun startServiceOrPause(id:String,session:String){
  val epoch=app.audioFocusEpoch.snapshot()
  requestWorkoutServiceOrPause(app.repository,id,session,app::workoutNow,
   {app.audioFocusEpoch.isCurrent(epoch)}){startService(id,session)}
 }
 // Callbacks include the session actually rendered in Runner. A queued tap
 // from an old screen must never act on whichever workout is current later.
 private fun traceResume(message:String){
  if(app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)android.util.Log.d("FORMA-RESUME",message)
 }
 fun resume(expected:String){
  // Debug evidence contains only transition outcomes, never profile/session data.
  traceResume("tap current=${current.value!=null} matches=${current.value?.active?.id==expected}")
  val p=current.value?:return;val s=p.active?.takeIf{it.id==expected}?:return
  // Invalidate queued audio-focus losses before scheduling the Room Resume.
  app.audioFocusEpoch.invalidate()
  action{
   try{
    // Session identity is sufficient: a stage-bound stale snapshot can reject a real Resume tap.
    app.repository.mutateSession(p.id,expected){
     val now=app.workoutNow()
     // A cold-start Resume may beat the asynchronous ViewModel recovery.
     // Recover first, then resume atomically so the offline gap is never credited.
     resumeSession(recoverIfPreviousProcess(it,app.processStartedMs,now),now)
    }
    val active=app.repository.load(p.id)?.active
    traceResume("persisted matches=${active?.id==expected} paused=${active?.paused} terminal=${active?.terminal}")
    if(active?.id==expected&&!active.terminal&&!active.paused){
     startServiceOrPause(p.id,expected)
     traceResume("foreground start requested")
    }
   }catch(e:Exception){
    traceResume("failed type=${e.javaClass.simpleName}")
    throw e
   }
  }
 }
 // Stage guard stays for set/skip actions; session-bound pause/stop remains
 // valid across asynchronously advancing phases (and rapid Resume -> Stop).
 fun pause(expected:String){mutateSession(expected,stageBound=false,invalidateFocus=true){pauseSession(it,app.workoutNow())}}
 fun skip(expected:String){mutateSession(expected){skipStage(it,app.workoutNow())}}
 fun done(expected:String,w:Double,r:Int,e:Int,pain:Boolean){mutateSession(expected){logSet(it,w,r,e,pain,app.workoutNow())}}
 fun stop(expected:String){mutateSession(expected,stageBound=false,invalidateFocus=true){stopSession(it,false,app.workoutNow())}}
 fun painStop(expected:String){mutateSession(expected,stageBound=false,invalidateFocus=true){stopSession(it,true,app.workoutNow())}}
 private fun mutateSession(expected:String,stageBound:Boolean=true,invalidateFocus:Boolean=false,op:(Session)->Session){
  val p=current.value?:return;val s=p.active?.takeIf{it.id==expected}?:return
  if(invalidateFocus)app.audioFocusEpoch.invalidate()
  action{
   app.repository.mutateSession(p.id,expected,if(stageBound)sessionStage(s) else null){
    // STOP/PAUSE can be tapped before the cold-start init coroutine completes.
    // Both recovery and the command must see the same Room transaction.
    op(recoverIfPreviousProcess(it,app.processStartedMs,app.workoutNow()))
   }
  }
 }
 fun finish(expected:String){
  val p=current.value?:return
  if(p.active?.id!=expected)return
  action{app.repository.mutate(p.id){v->v.active?.takeIf{it.id==expected}?.let{finishSession(v,it,app.workoutNow())}?:v}}
 }
 fun measurement(values:Map<String,Double>,id:String?=null,dateMs:Long=System.currentTimeMillis(),onSaved:()->Unit={}){val profile=current.value?.id?:return;action{val m=Measurement(id=id?:java.util.UUID.randomUUID().toString(),dateMs=dateMs,values=values);require(m.valid());app.repository.mutate(profile){p->require(id==null||p.measurements.any{it.id==id}){"Замер больше не существует"};val records=if(id==null)p.measurements+m else p.measurements.map{if(it.id==id)m else it};val latest=records.sortedBy{it.dateMs};p.copy(measurements=records,weightKg=latest.lastOrNull{it.values.containsKey("weight")}?.values?.get("weight")?:p.weightKg,heightCm=latest.lastOrNull{it.values.containsKey("height")}?.values?.get("height")?:p.heightCm)};onSaved()}}
 fun exportBackup(uri:Uri,allProfiles:Boolean=false){val id:String?=if(allProfiles)null else (current.value?.id?:return);action{withContext(Dispatchers.IO){val text=app.repository.exportBackup(id);val output=app.contentResolver.openOutputStream(uri,"wt")?:error("Не удалось открыть файл");output.use{it.write(text.toByteArray(Charsets.UTF_8))}};notice.value=if(allProfiles)"Копия всех локальных профилей сохранена. Она содержит персональные данные других пользователей — храните её в защищённом месте." else "Копия текущего профиля сохранена. Храните её в защищённом месте: она содержит историю и замеры."}}
 fun importBackup(uri:Uri){action{val ids=withContext(Dispatchers.IO){val input=app.contentResolver.openInputStream(uri)?:error("Не удалось открыть файл");val bytes=input.use{stream->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=stream.read(buffer);if(n<0)break;require(out.size()+n<=MAX_BACKUP_BYTES){"Файл превышает 32 МБ"};out.write(buffer,0,n)};out.toByteArray()};app.repository.importBackup(bytes.toString(Charsets.UTF_8))};notice.value="Восстановлено профилей: ${ids.size}. Они добавлены отдельно; существующие данные сохранены."}}
 fun audio(v:Boolean,s:Boolean,m:Boolean){action{app.settings.audio(v,s,m)}}
 fun coach(volume:Int,bpm:Int,rate:Float){action{app.settings.coach(volume,bpm,rate)}}
 fun dark(v:Boolean?){action{app.settings.dark(v)}}
 fun trainingPlace(place:TrainingPlace){action{app.settings.trainingPlace(place)}}
}
