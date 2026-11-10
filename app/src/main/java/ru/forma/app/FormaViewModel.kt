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

// Initial state must not masquerade as an empty Room database or default
// selected user. Both sources load asynchronously on Android cold start.
internal enum class LocalLoadStatus { LOADING, READY, FAILED }
internal data class ProfileSnapshot(
 val status:LocalLoadStatus,
 val profiles:List<Profile> = emptyList()
)
internal data class SettingsSnapshot(
 val status:LocalLoadStatus,
 val settings:Preferences = Preferences()
)
internal fun canRenderProfileUi(
 profiles:ProfileSnapshot,settings:SettingsSnapshot,current:Profile?
):Boolean {
 if(profiles.status!=LocalLoadStatus.READY||settings.status!=LocalLoadStatus.READY)return false
 val expected=profiles.profiles.firstOrNull{it.id==settings.settings.selected}
  ?:profiles.profiles.firstOrNull()
 return current?.id==expected?.id
}
// Non-identifying in-memory gesture diagnostics for IME/Save regressions.
enum class SaveTouchStage { PRESS, RELEASE, CANCEL }
internal data class SaveTouchEvidence(val presses:Int=0,val releases:Int=0,val cancels:Int=0)
class FormaViewModel(application:Application):AndroidViewModel(application){
 private val app=application as FormaApp
 val error=app.serviceError
 val notice=MutableStateFlow<String?>(null)
 // No names/IDs: identify whether the user Save touch reached the VM on API26.
 internal val profileSaveRequests=MutableStateFlow(0)
 internal val profileSaveTouchEvidence=MutableStateFlow(SaveTouchEvidence())
 internal fun noteProfileSaveTouch(stage:SaveTouchStage){
  profileSaveTouchEvidence.update{old->when(stage){
   SaveTouchStage.PRESS->old.copy(presses=old.presses+1)
   SaveTouchStage.RELEASE->old.copy(releases=old.releases+1)
   SaveTouchStage.CANCEL->old.copy(cancels=old.cancels+1)
  }}
 }
 // Non-identifying witness for an initial workout-start touch reaching this VM.
 internal val workoutStartRequests=MutableStateFlow(0)
 // A profile save is one durable operation: do not enqueue overlapping taps.
 internal val profileSaveInProgress=MutableStateFlow(false)
 val coachStatus=app.coachStatus
 internal val profileSnapshot=app.repository.profiles
  .map{ProfileSnapshot(LocalLoadStatus.READY,it)}
  .catch{error.value="Не удалось прочитать локальные данные";emit(ProfileSnapshot(LocalLoadStatus.FAILED))}
  .stateIn(viewModelScope,SharingStarted.Eagerly,ProfileSnapshot(LocalLoadStatus.LOADING))
 internal val settingsSnapshot=app.settings.settings
  .map{SettingsSnapshot(LocalLoadStatus.READY,it)}
  .catch{error.value="Не удалось прочитать настройки";emit(SettingsSnapshot(LocalLoadStatus.FAILED))}
  .stateIn(viewModelScope,SharingStarted.Eagerly,SettingsSnapshot(LocalLoadStatus.LOADING))
 // Retain the existing read-only observables for callers and instrumentation.
 val profiles=profileSnapshot.map{it.profiles}.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
 val prefs=settingsSnapshot.map{it.settings}.stateIn(viewModelScope,SharingStarted.Eagerly,Preferences())
 val current=combine(profileSnapshot,settingsSnapshot){ps,s->
  if(ps.status!=LocalLoadStatus.READY||s.status!=LocalLoadStatus.READY)null
  else ps.profiles.firstOrNull{it.id==s.settings.selected}?:ps.profiles.firstOrNull()
 }.stateIn(viewModelScope,SharingStarted.Eagerly,null)
 // Recovery applies only to sessions last ticked by an older process.
 // A late init coroutine must never re-pause a workout the Owner just resumed.
 init{action{if(!app.serviceRunning)app.repository.profiles.first().filter{it.active?.terminal==false}.forEach{profile->
  app.repository.mutateSession(profile.id,profile.active!!.id){recoverIfPreviousProcess(it,app.processStartedMs,app.workoutNow())}
 }}}
 private fun action(block:suspend()->Unit){viewModelScope.launch{try{block()}catch(e:CancellationException){throw e}catch(e:Exception){error.value=e.message?:"Ошибка сохранения"}}}
 fun select(id:String){action{require(app.repository.profiles.first().none{it.active!=null}){"Сначала завершите тренировку"};app.settings.select(id)}}
 fun saveProfile(p:Profile,onSaved:()->Unit={}){
  // One in-flight save per ViewModel; preserve retry after any Room/DataStore error.
  if(!profileSaveInProgress.compareAndSet(false,true))return
  // A synchronous dispatch witness: the Room coroutine can outlive this frame.
  profileSaveRequests.value++
  action{
   try{
    require(p.valid()){ "Проверьте имя, возраст (18–100), рост и вес" }
    if(app.repository.load(p.id)==null)app.repository.saveProfile(p)
    else app.repository.mutate(p.id){p.copy(history=it.history,measurements=it.measurements,active=it.active)}
    app.settings.select(p.id)
    onSaved()
   }finally{profileSaveInProgress.value=false}
  }
 }
 fun start(day:TrainingDay){workoutStartRequests.value++;val p=current.value?:return;action{val s=app.repository.start(p.id,day,app.workoutNow());app.audioFocusEpoch.invalidate();startServiceOrPause(p.id,s.id)}}
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
    app.mutateWorkoutSession(p.id,expected){
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
   app.mutateWorkoutSession(p.id,expected,if(stageBound)sessionStage(s) else null){
    // STOP/PAUSE can be tapped before the cold-start init coroutine completes.
    // Both recovery and the command must see the same Room transaction.
    op(recoverIfPreviousProcess(it,app.processStartedMs,app.workoutNow()))
   }
  }
 }
 fun finish(expected:String,onSaved:()->Unit={}){
  val p=current.value?:return
  if(p.active?.id!=expected)return
  action{
   app.repository.mutate(p.id){v->v.active?.takeIf{it.id==expected}?.let{finishSession(v,it,app.workoutNow())}?:v}
   // Navigate only after Room confirms this exact workout reached persisted history.
   if(app.repository.load(p.id)?.history?.any{it.id==expected}==true)onSaved()
  }
 }
 fun measurement(
  values:Map<String,Double>,id:String?=null,dateMs:Long=System.currentTimeMillis(),
  draftId:String?=null,onSaved:()->Unit={}
 ){
  val profile=current.value?.id?:return
  action{
   val m=Measurement(id=id?:draftId?:java.util.UUID.randomUUID().toString(),
    dateMs=dateMs,values=values)
   app.repository.mutate(profile){it.withMeasurement(m,editingId=id)}
   onSaved()
  }
 }
 // Passwords stay transient and are cleared after the IO operation completes.
 // No permanent encryption key, remote upload or password persistence.
 internal val encryptedBackupBusy=MutableStateFlow(false)
 fun exportEncryptedBackup(uri:Uri,password:CharArray,allProfiles:Boolean=false,expectedProfileId:String?=null){
  if(!encryptedBackupBusy.compareAndSet(false,true)){password.fill('\u0000');return}
  val selected=if(allProfiles)null else expectedProfileId
  if(!allProfiles&&(selected==null||current.value?.id!=selected)){
   password.fill('\u0000');encryptedBackupBusy.value=false
   error.value="Текущий профиль сменился. Повторите создание копии."
   return
  }
  action{
   try{
    withContext(Dispatchers.IO){
     val plaintext=app.repository.exportBackup(selected)
     val contents=encryptPortableBackup(plaintext,password)
     try{
      // A SAF provider may close the write successfully yet persist only part
      // of the ciphertext. Do not announce a usable copy without readback.
      try{
       writeVerifiedEncryptedBackup(contents,
        {app.contentResolver.openOutputStream(uri,"wt")},
        {app.contentResolver.openInputStream(uri)})
      }catch(e:Exception){
       throw java.io.IOException(
        "Защищённую копию не удалось проверить после записи. Не полагайтесь на этот файл; выберите другое хранилище.",e
       )
      }
     }finally{contents.fill(0)}
    }
    notice.value=if(allProfiles)
     "Защищённая копия всех профилей сохранена и сверена повторным чтением. Пароль храните отдельно."
    else "Защищённая копия профиля сохранена и сверена повторным чтением. Пароль храните отдельно."
   }finally{password.fill('\u0000');encryptedBackupBusy.value=false}
  }
 }
 fun importEncryptedBackup(uri:Uri,password:CharArray){
  if(!encryptedBackupBusy.compareAndSet(false,true)){password.fill('\u0000');return}
  action{
   try{
    val ids=withContext(Dispatchers.IO){
     val input=app.contentResolver.openInputStream(uri)?:error("Не удалось открыть файл")
     val bytes=input.use{stream->
      val out=java.io.ByteArrayOutputStream()
      val buffer=ByteArray(8192)
      while(true){
       val n=stream.read(buffer)
       if(n<0)break
       require(out.size()+n<=MAX_BACKUP_BYTES+128){"Файл защищённой копии слишком большой"}
       out.write(buffer,0,n)
      }
      out.toByteArray()
     }
     try{
      val plaintext=decryptPortableBackup(bytes,password)
      app.repository.importBackup(plaintext)
     }finally{bytes.fill(0)}
    }
    notice.value="Восстановлено профилей: " + ids.size + ". Существующие данные не заменены."
   }finally{password.fill('\u0000');encryptedBackupBusy.value=false}
  }
 }
 fun exportBackup(
  uri:Uri,allProfiles:Boolean=false,expectedProfileId:String?=null,
  expectedProfileIds:Set<String>?=null
 ){
  // A stale SAF result cannot silently select a different person's profile.
  if ((!allProfiles && (expectedProfileId.isNullOrBlank() ||
   current.value?.id!=expectedProfileId)) ||
   (allProfiles && expectedProfileIds.isNullOrEmpty())) {
   error.value="Выбранный профиль изменился. Повторите экспорт."
   return
  }
  action {
   withContext(Dispatchers.IO) {
    suspend fun verifyScope()=requireBackupExportScope(
     allProfiles,expectedProfileId,expectedProfileIds,current.value?.id,
     app.settings.settings.first().selected,
     app.repository.profiles.first().map{it.id}.toSet()
    )
    verifyScope()
    val text=app.repository.exportBackup(if(allProfiles)null else expectedProfileId)
    val bytes=text.toByteArray(Charsets.UTF_8)
    try {
     verifyScope()
     try {
      writeVerifiedBackupBytes(bytes,
       {app.contentResolver.openOutputStream(uri,"wt")},
       {app.contentResolver.openInputStream(uri)})
     }catch(e:Exception) {
      throw java.io.IOException(
       "Файл JSON не удалось проверить после записи. Не полагайтесь на эту копию; выберите другое хранилище.",e)
     }
    }finally{bytes.fill(0)}
   }
   notice.value=if(allProfiles)
    "JSON-копия всех профилей сохранена и сверена повторным чтением. Она НЕ зашифрована: храните её в защищённом месте."
   else "JSON-копия выбранного профиля сохранена и сверена повторным чтением. Она НЕ зашифрована: храните её в защищённом месте."
  }
 }
 fun importBackup(uri:Uri){action{val ids=withContext(Dispatchers.IO){val input=app.contentResolver.openInputStream(uri)?:error("Не удалось открыть файл");val bytes=input.use{stream->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=stream.read(buffer);if(n<0)break;require(out.size()+n<=MAX_BACKUP_BYTES){"Файл превышает 32 МБ"};out.write(buffer,0,n)};out.toByteArray()};app.repository.importBackup(bytes.toString(Charsets.UTF_8))};notice.value="Восстановлено профилей: ${ids.size}. Они добавлены отдельно; существующие данные сохранены."}}
 fun audio(v:Boolean,s:Boolean,m:Boolean){action{app.settings.audio(v,s,m)}}
 fun coach(volume:Int,bpm:Int,rate:Float){action{app.settings.coach(volume,bpm,rate)}}
 fun dark(v:Boolean?){action{app.settings.dark(v)}}
 fun trainingPlace(place:TrainingPlace){action{app.settings.trainingPlace(place)}}
}
