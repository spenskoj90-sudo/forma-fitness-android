package ru.forma.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
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
internal fun selectedProfile(profiles:List<Profile>,id:String):Profile? =
 profiles.firstOrNull{it.active!=null}?:profiles.firstOrNull{it.id==id}?:profiles.firstOrNull()

internal fun canRenderProfileUi(
 profiles:ProfileSnapshot,settings:SettingsSnapshot,current:Profile?
):Boolean {
 if(profiles.status!=LocalLoadStatus.READY||settings.status!=LocalLoadStatus.READY)return false
 val expected=selectedProfile(profiles.profiles,settings.settings.selected)
 return current?.id==expected?.id
}
// Non-identifying in-memory gesture diagnostics for IME/Save regressions.
enum class SaveTouchStage { PRESS, RELEASE, CANCEL }
internal data class SaveTouchEvidence(val presses:Int=0,val releases:Int=0,val cancels:Int=0)
class FormaViewModel internal constructor(
 application:Application,
 private val workoutScope:AccountWorkoutScope?
):AndroidViewModel(application){
 constructor(application:Application):this(application,null)
 private val app=application as FormaApp
 private val profileRepository=workoutScope?.repository?:app.repository
 private val scopeId=workoutScope?.id
 private val mutableCloudRestoreUi=MutableStateFlow(CloudRestoreUi())
 internal val cloudRestoreUi=mutableCloudRestoreUi.asStateFlow()
 internal val cloudSyncAvailable get()=workoutScope!=null&&app.cloudSync!=null
 private fun cloudAction(block:suspend (CloudSyncRuntime,VerifiedSyncLease,()->Boolean)->Unit){
  val scope=workoutScope?:return
  val runtime=app.cloudSync?:return
  val old=mutableCloudRestoreUi.value
  if(old.busy||!mutableCloudRestoreUi.compareAndSet(old,old.copy(busy=true,message=null)))return
  viewModelScope.launch{
   val stillOpen={app.resolveWorkoutRepository(scope.id)===scope.repository}
   try{
    if(!stillOpen())throw LocalWorkoutAccessRevoked()
    block(runtime,scope.lease,stillOpen)
    val status=runtime.status(scope.lease,stillOpen)
    if(stillOpen())mutableCloudRestoreUi.update{it.copy(summary=status)}
   }catch(cancel:CancellationException){
    throw cancel
   }catch(_:LocalWorkoutAccessRevoked){
    mutableCloudRestoreUi.value=CloudRestoreUi()
   }catch(_:Exception){
    if(stillOpen())mutableCloudRestoreUi.update{it.copy(
     message="Не удалось выполнить облачную операцию. Проверьте сеть и повторите."
    )}
   }finally{
    mutableCloudRestoreUi.update{it.copy(busy=false)}
   }
  }
 }
 internal fun refreshCloudStatus(){cloudAction{_,_,_->}}
 internal fun scheduleCloudSync(){cloudAction{runtime,_,_->
  runtime.enqueue()
  mutableCloudRestoreUi.update{it.copy(message="Очередь поставлена на отправку.")}
 }}
 internal fun listCloudProfiles(){cloudAction{runtime,lease,stillOpen->
  mutableCloudRestoreUi.update{it.copy(profiles=null)}
  val profiles=runtime.list(lease,stillOpen)
  if(stillOpen())mutableCloudRestoreUi.update{it.copy(profiles=profiles)}
 }}
 internal fun restoreCloudProfile(id:String){
  if(mutableCloudRestoreUi.value.profiles?.none{it.id==id}!=false)return
  cloudAction{runtime,lease,stillOpen->
   val result=runtime.restore(lease,id,stillOpen)
   if(stillOpen())mutableCloudRestoreUi.update{it.copy(
    message="Восстановлено записей — ${result.restored}. Требуют разбора — ${result.conflicts}."
   )}
  }
 }
 private val accountSelection=MutableStateFlow("")
 private val accountPlace=MutableStateFlow(TrainingPlace.HOME)
 internal val accountWorkouts=app.accountWorkouts
 internal fun openAccountWorkouts(){action{app.openAccountWorkouts()}}
 internal fun closeAccountWorkouts(id:String){action{app.closeAccountWorkouts(id)}}
 internal val accountConsent=MutableStateFlow<AccountConsentDraft?>(null)
 internal fun requestAccountConsent(){
  val client=app.firebaseAccount?:return
  val profile=current.value?.takeIf{it.active==null}?:return
  val lease=client.identity.localLease()?:return
  val email=client.verifiedEmail(lease)?:return
  accountConsent.value=AccountConsentDraft(profile.id,profile.name,email,lease)
 }
 internal fun confirmAccountConsent(draft:AccountConsentDraft){
  if(!accountConsent.compareAndSet(draft,null)||mutableAccount.value.working)return
  mutableAccount.update{it.copy(working=true,message=null,isError=false)}
  action{
   try{
    app.bindGuestProfile(draft.profileId,draft.lease)
    notice.value="Профиль теперь доступен в выбранном аккаунте. Данные подготовлены к синхронизации."
   }finally{
    mutableAccount.update{it.copy(working=false)}
   }
  }
 }
 internal val accountAvailable get()=app.firebaseAccount!=null
 private val mutableAccount=MutableStateFlow(AccountScreenState(
  session=app.firebaseAccount?.currentSession?:AccountSession.SIGNED_OUT
 ))
 internal val account=mutableAccount.asStateFlow()
 init {
  app.firebaseAccount?.takeIf{workoutScope==null}?.let { client ->
   viewModelScope.launch {
    client.sessions.collect { session -> mutableAccount.update { it.copy(session=session) } }
   }
   viewModelScope.launch{
    client.localLeases.collect{
     accountConsent.update{draft->draft?.takeIf{it.lease==client.identity.localLease()}}
    }
   }
  }
 }

 internal fun accountAction(action:AccountAction,email:String="",password:String="") {
  val client=app.firebaseAccount?:return
  if(mutableAccount.value.working)return
  mutableAccount.update{it.copy(working=true,message=null,isError=false)}
  viewModelScope.launch {
   val resetNotice="Если адрес зарегистрирован, письмо для сброса отправлено."
   var message:String?=null
   var failed=false
   try {
    message=when(action){
     AccountAction.SIGN_IN -> {client.signIn(email,password);null}
     AccountAction.REGISTER -> {client.register(email,password);"Письмо с подтверждением отправлено."}
     AccountAction.REFRESH -> {
      client.refreshVerification()
      if(client.currentSession==AccountSession.EMAIL_UNVERIFIED)"Подтверждение почты пока не получено." else null
     }
     AccountAction.RESEND -> {client.resendVerification();"Письмо с подтверждением отправлено."}
     AccountAction.RESET_PASSWORD -> {client.sendPasswordReset(email);resetNotice}
     AccountAction.SIGN_OUT -> {
      client.signOut()
      app.accountWorkouts.value?.let{app.closeAccountWorkouts(it.id)}
      null
     }
    }
   }catch(e:CancellationException){
    throw e
   }catch(e:Exception){
    val hiddenResetResult=action==AccountAction.RESET_PASSWORD&&e is FirebaseAuthInvalidUserException
    failed=!hiddenResetResult
    message=when(e){
     is FirebaseNetworkException -> "Нет связи с сервером. Проверь интернет и повтори."
     is FirebaseTooManyRequestsException -> "Слишком много попыток. Повтори позже."
     is FirebaseAuthWeakPasswordException -> "Пароль не соответствует требованиям безопасности."
     else -> when(action){
      AccountAction.SIGN_IN -> "Не удалось войти. Проверь адрес и пароль."
      AccountAction.REGISTER -> "Не удалось завершить регистрацию. Проверь адрес или попробуй войти."
      AccountAction.RESET_PASSWORD -> if(hiddenResetResult)resetNotice else "Не удалось запустить сброс пароля. Проверь адрес и повтори."
      else -> "Не удалось выполнить действие. Попробуй ещё раз."
     }
    }
   }finally{
    mutableAccount.value=AccountScreenState(client.currentSession,false,message,failed)
   }
  }
 }
 val error=if(workoutScope==null)app.serviceError else MutableStateFlow<String?>(null)
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
 internal val profileSnapshot=profileRepository.profiles
  .onStart{if(workoutScope!=null)recoverWorkouts()}
  .map{ProfileSnapshot(LocalLoadStatus.READY,it)}
  .catch{if(it !is LocalWorkoutAccessRevoked)error.value="Не удалось прочитать локальные данные";emit(ProfileSnapshot(LocalLoadStatus.FAILED))}
  .stateIn(viewModelScope,SharingStarted.Eagerly,ProfileSnapshot(LocalLoadStatus.LOADING))
 internal val settingsSnapshot=(if(workoutScope==null)app.settings.settings else
  combine(app.settings.settings,accountSelection,accountPlace){settings,selected,place->
   settings.copy(selected=selected,trainingPlace=place)
  })
  .map{SettingsSnapshot(LocalLoadStatus.READY,it)}
  .catch{error.value="Не удалось прочитать настройки";emit(SettingsSnapshot(LocalLoadStatus.FAILED))}
  .stateIn(viewModelScope,SharingStarted.Eagerly,SettingsSnapshot(LocalLoadStatus.LOADING))
 // Retain the existing read-only observables for callers and instrumentation.
 val profiles=profileSnapshot.map{it.profiles}.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
 val prefs=settingsSnapshot.map{it.settings}.stateIn(viewModelScope,SharingStarted.Eagerly,Preferences())
 val current=combine(profileSnapshot,settingsSnapshot){ps,s->
  if(ps.status!=LocalLoadStatus.READY||s.status!=LocalLoadStatus.READY)null
  else selectedProfile(ps.profiles,s.settings.selected)
 }.stateIn(viewModelScope,SharingStarted.Eagerly,null)
 // Recovery applies only to sessions last ticked by an older process.
 // A late init coroutine must never re-pause a workout the Owner just resumed.
 init{if(workoutScope==null)action{if(!app.serviceRunning)recoverWorkouts()}}
 private suspend fun recoverWorkouts(){
  profileRepository.profiles.first().filter{it.active?.terminal==false}.forEach{profile->
   app.mutateWorkoutSession(profile.id,profile.active!!.id,scopeId=scopeId){it}
  }
 }
 private fun action(block:suspend()->Unit){viewModelScope.launch{
  try{block()}
  catch(e:CancellationException){throw e}
  catch(_:LocalWorkoutAccessRevoked){
   if(workoutScope==null)error.value="Доступ к тренировкам изменился. Откройте нужный профиль."
  }
  catch(e:Exception){error.value=e.message?:"Ошибка сохранения"}
 }}
 fun select(id:String){action{
  val local=profileRepository.profiles.first()
  require(local.any{it.id==id}&&local.none{it.active!=null}){"Выберите доступный профиль после завершения тренировки"}
  if(workoutScope==null)app.settings.select(id) else accountSelection.value=id
 }}
 fun saveProfile(p:Profile,onSaved:()->Unit={}){
  // One in-flight save per ViewModel; preserve retry after any Room/DataStore error.
  if(!profileSaveInProgress.compareAndSet(false,true))return
  // A synchronous dispatch witness: the Room coroutine can outlive this frame.
  profileSaveRequests.value++
  action{
   try{
    require(p.valid()){ "Проверьте имя, возраст (18–100), рост и вес" }
    if(profileRepository.load(p.id)==null)profileRepository.saveProfile(p)
    else profileRepository.mutate(p.id){p.copy(history=it.history,measurements=it.measurements,active=it.active)}
    if(workoutScope==null)app.settings.select(p.id) else accountSelection.value=p.id
    onSaved()
   }finally{profileSaveInProgress.value=false}
  }
 }
 fun start(day:TrainingDay){workoutStartRequests.value++;val p=current.value?:return;action{val s=app.startWorkout(p.id,day,scopeId);app.audioFocusEpoch.invalidate();startServiceOrPause(p.id,s.id)}}
 private fun startService(id:String,session:String){
  if(app.resolveWorkoutRepository(scopeId)==null)throw LocalWorkoutAccessRevoked()
  ContextCompat.startForegroundService(app,Intent(app,WorkoutService::class.java)
   .putExtra("profile",id).putExtra("session",session).putExtra("workoutScope",scopeId))
 }
 private suspend fun startServiceOrPause(id:String,session:String){
  val epoch=app.audioFocusEpoch.snapshot()
  requestWorkoutServiceOrPause(profileRepository,id,session,app::workoutNow,
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
    app.mutateWorkoutSession(p.id,expected,scopeId=scopeId){resumeSession(it,app.workoutNow())}
    val active=profileRepository.load(p.id)?.active
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
   app.mutateWorkoutSession(p.id,expected,if(stageBound)sessionStage(s) else null,scopeId,op)
  }
 }
 fun finish(expected:String,onSaved:()->Unit={}){
  val p=current.value?:return
  if(p.active?.id!=expected)return
  action{
   profileRepository.mutate(p.id){v->v.active?.takeIf{it.id==expected}?.let{finishSession(v,it,app.workoutNow())}?:v}
   // Navigate only after Room confirms this exact workout reached persisted history.
   if(profileRepository.load(p.id)?.history?.any{it.id==expected}==true)onSaved()
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
   profileRepository.mutate(profile){it.withMeasurement(m,editingId=id)}
   onSaved()
  }
 }
 // Passwords stay transient and are cleared after the IO operation completes.
 // No permanent encryption key, remote upload or password persistence.
 internal val encryptedBackupBusy=MutableStateFlow(false)
 fun exportEncryptedBackup(
  uri:Uri,password:CharArray,allProfiles:Boolean=false,expectedProfileId:String?=null,
  expectedProfileIds:Set<String>?=null
 ){
  if(!encryptedBackupBusy.compareAndSet(false,true)){password.fill('\u0000');return}
  val selected=if(allProfiles)null else expectedProfileId
  if ((allProfiles && workoutScope!=null) ||
   (!allProfiles && (selected.isNullOrBlank() || current.value?.id!=selected)) ||
   (allProfiles && expectedProfileIds.isNullOrEmpty())) {
   password.fill('\u0000');encryptedBackupBusy.value=false
   error.value="Состав или выбор профилей изменился. Повторите создание копии."
   return
  }
  action{
   try{
    withContext(Dispatchers.IO){
     suspend fun verifyScope()=requireBackupExportScope(
      allProfiles,expectedProfileId,expectedProfileIds,current.value?.id,
      if(workoutScope==null)app.settings.settings.first().selected else accountSelection.value,
      profileRepository.profiles.first().map{it.id}.toSet()
     )
     verifyScope()
     val plaintext=profileRepository.exportBackup(selected)
     val contents=encryptPortableBackup(plaintext,password)
     try{
      // SAF selection may outlive a profile switch or creation/deletion.
      // Reject stale scope before writing any encrypted personal records.
      verifyScope()
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
      verifyScope()
     }finally{contents.fill(0)}
    }
    notice.value=if(allProfiles)
     "Защищённая копия всех профилей сохранена и сверена повторным чтением. Пароль храните отдельно."
    else "Защищённая копия профиля сохранена и сверена повторным чтением. Пароль храните отдельно."
   }finally{password.fill('\u0000');encryptedBackupBusy.value=false}
  }
 }
 fun importEncryptedBackup(uri:Uri,password:CharArray){
  if(workoutScope!=null){password.fill('\u0000');return}
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
 fun toggleVoice(){action{app.settings.toggleVoice()}}
 fun toggleSound(){action{app.settings.toggleSound()}}
 fun toggleMetronome(){action{app.settings.toggleMetronome()}}
 fun coach(volume:Int,bpm:Int,rate:Float){action{app.settings.coach(volume,bpm,rate)}}
 fun dark(v:Boolean?){action{app.settings.dark(v)}}
 fun trainingPlace(place:TrainingPlace){
  if(workoutScope==null)action{app.settings.trainingPlace(place)}
  else accountPlace.value=place
 }
 fun toggleHighContrast(){action{app.settings.toggleHighContrast()}}
 fun toggleReducedMotion(){action{app.settings.toggleReducedMotion()}}
 fun toggleSimplifiedEffects(){action{app.settings.toggleSimplifiedEffects()}}
}
