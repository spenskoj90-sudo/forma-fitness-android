package ru.forma.app

import android.app.*
import android.content.Intent
import android.media.*
import android.os.*
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.forma.domain.*

class WorkoutService:Service(),TextToSpeech.OnInitListener{
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 private val audioFocusGate by lazy{AudioFocusLeaseGate(app.audioFocusEpoch)}
 @Volatile private var job:Job?=null
 // Android may dispatch a new START before the previous workout loop exits.
 private val commandMutex=Mutex()
 // Only the main thread opens, uses or closes native output handles.
 private val outputHandler=Handler(Looper.getMainLooper())
 private var outputsClosed=false
 private var tts:TextToSpeech?=null
 private var ready=false
 private var focusGranted=false
 @Volatile private var focusLossPending=false
 @Volatile private var focusRequest:AudioFocusRequest?=null
 @Volatile private var focusLease:AudioFocusLease?=null
 private var tone:ToneGenerator?=null
 private var toneVolume=-1
 private var wake:PowerManager.WakeLock?=null
 private var profileId:String?=null
 private var sessionId:String?=null
 @Volatile private var workoutScopeId:String?=null
 // onStartCommand is serialized on the main thread. Keep the latest Android
 // start id, so a completed session can stop after PAUSE/STOP commands too.
 @Volatile private var lastRequestedSession:String?=null
 @Volatile private var lastRequestedScope:String?=null
 @Volatile private var lastStartId:Int=0
 @Volatile private var acceptedCommandEpoch:Long=-1L
 private var lastStage=""
 private var nextBeat=0L
 private val app get()=application as FormaApp
 private val manager by lazy{getSystemService(AUDIO_SERVICE) as AudioManager}
 private fun requestFocus(){
  val lease=audioFocusGate.open()
  val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
   .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
   .setOnAudioFocusChangeListener(AudioManager.OnAudioFocusChangeListener{change->onFocusChanged(lease,change)},outputHandler)
   .build()
  focusLease=lease
  focusRequest=request
  focusGranted=manager.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED&&audioFocusGate.isCurrent(lease)
 }
 private fun onFocusChanged(lease:AudioFocusLease,change:Int){
  // Android may deliver an abandoned request's callback after the Owner resumes.
  if(outputsClosed||!audioFocusGate.isCurrent(lease))return
  if(change==AudioManager.AUDIOFOCUS_GAIN){focusGranted=true;return}
  if(!focusGranted)return
  focusGranted=false
  silence()
  val id=profileId;val session=sessionId;val access=workoutScopeId
  if(id!=null&&session!=null){
   focusLossPending=true
   scope.launch{
    try{
     app.resolveWorkoutRepository(access)?.mutateSession(id,session){active->
      // Check while holding the Room transaction: a later Resume or new
      // request invalidates queued losses before they can re-pause the session.
      if(audioFocusGate.isCurrent(lease))pauseSession(active,app.workoutNow()) else active
     }
    }catch(e:CancellationException){throw e}
    catch(_:LocalWorkoutAccessRevoked){Unit}
    catch(e:Exception){app.coachStatus.value="Не удалось сохранить паузу после потери звука. Проверьте тренировку."}
    finally{focusLossPending=false}
   }
  }
 }
 override fun onCreate(){super.onCreate()
  (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("workout","Тренировка FORMA",NotificationManager.IMPORTANCE_LOW))
  tts=TextToSpeech(this,this)
 }
 override fun onInit(status:Int){
  outputHandler.post {
   if(outputsClosed)return@post
   if(status==TextToSpeech.SUCCESS)ready=runCatching{val voice=tts?.voices?.firstOrNull{it.locale.language=="ru"&&!it.isNetworkConnectionRequired};if(voice!=null){tts?.voice=voice;true}else false}.getOrDefault(false)
   app.coachStatus.value=if(ready)"Русский офлайн-голос готов"else"Русский офлайн-голос недоступен. Используются экранные подсказки и включённые сигналы."
  }
 }
 override fun onBind(intent:Intent?):IBinder?=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  val id=intent?.getStringExtra("profile")
  val expected=intent?.getStringExtra("session")
  val access=intent?.getStringExtra("workoutScope")
  val repository=app.resolveWorkoutRepository(access)
  if(repository==null){
   if(job?.isActive==true){
    lastStartId=startId
   }else{
    // A queued foreground launch may outlive account revocation.
    runCatching{startForeground(1,notification("Подготовка тренировки",null))}
    stopSelf(startId)
   }
   return START_NOT_STICKY
  }
  // Keep each Android startId: an old loop must never stop a newer START.
  lastRequestedSession=expected
  lastRequestedScope=access
  lastStartId=startId
  acceptedCommandEpoch=app.audioFocusEpoch.snapshot()
  try{startForeground(1,notification("Подготовка тренировки",null))}
  catch(e:RuntimeException){
   // Android 12+ may refuse background starts; Android 14+ can reject an
   // invalid foreground-service promotion. Never leave Room timing active.
   val failureEpoch=app.audioFocusEpoch.invalidate()
   scope.launch{
    try{
     if(id!=null&&expected!=null){
      if(intent?.action=="STOP"){
       repository.mutateSession(id,expected){s->
        val now=app.workoutNow()
        stopSession(recoverSession(s,now),false,now)
       }
      }else{
       repository.pauseWhenServiceUnavailable(id,expected,app.workoutNow()){
        lastStartId==startId&&lastRequestedSession==expected&&
         app.audioFocusEpoch.isCurrent(failureEpoch)
       }
      }
     }
     app.serviceError.value="Не удалось включить фоновое сопровождение. Тренировка сохранена; проверьте её состояние."
    }catch(cancel:CancellationException){throw cancel}
    catch(_:LocalWorkoutAccessRevoked){Unit}
    catch(error:Exception){
     app.serviceError.value="Не удалось включить фоновое сопровождение и подтвердить сохранение паузы. Проверьте тренировку."
    }finally{
     // A rejected service must not leave an older workout loop ticking.
     // Preserve a newer Android START and its foreground lifecycle.
     if(lastStartId==startId){
      lastRequestedSession=null
      job?.cancelAndJoin()
      job=null
      app.serviceRunning=false
      stopSelf(startId)
     }
    }
   }
   return START_NOT_STICKY
  }
  if(id==null||expected==null){
   if(job?.isActive!=true)stopSelf(startId)
   return START_NOT_STICKY
  }
  scope.launch{
   try{
   commandMutex.withLock{
    val active=repository.load(id)?.active
    if(active==null||active.id!=expected||active.terminal){
     // A stale PendingIntent cannot supersede the real running session.
     if(job?.isActive==true){lastRequestedSession=sessionId;lastRequestedScope=workoutScopeId}
     else stopSelf(startId)
     return@withLock
    }
    if(sessionId!=null&&(sessionId!=expected||workoutScopeId!=access)){
     // Await resource cleanup (TTS, focus, wakelock) before starting a
     // replacement session. A stale notification cannot mutate this one.
     job?.cancelAndJoin()
     job=null
     withContext(Dispatchers.Main.immediate){resetCue()}
    }
    profileId=id;sessionId=expected;workoutScopeId=access
    if(intent.action=="PAUSE"||intent.action=="STOP")app.audioFocusEpoch.invalidate()
    app.mutateWorkoutSession(id,expected,scopeId=access){session->
     when(intent.action){
      "PAUSE"->pauseSession(session,app.workoutNow())
      "STOP"->stopSession(session,false,app.workoutNow())
      else->session
     }
    }
    if(intent.action=="PAUSE"||intent.action=="STOP")withContext(Dispatchers.Main.immediate){silence()}
    if(intent.action=="STOP"){
     // A newer START can arrive while the cancelled loop is completing.
     job?.cancelAndJoin()
     job=null
     app.serviceRunning=false
     stopSelf(startId)
    }else if(job?.isActive!=true){
     app.serviceRunning=true
     job=scope.launch{runWorkout(id,expected,access,repository)}
    }
   }
   }catch(_:LocalWorkoutAccessRevoked){
    if(lastStartId==startId)stopSelf(startId)
   }
  }
  return START_NOT_STICKY
 }
 private suspend fun runWorkout(id:String,expected:String,access:String?,repository:ProfileRepository){
  try{while(currentCoroutineContext().isActive){
   app.mutateWorkoutSession(id,expected,scopeId=access){advanceTimed(it,app.workoutNow())}
   val s=repository.load(id)?.active?.takeIf{it.id==expected}?:break
   if(s.terminal)break
   val prefs=app.settings.settings.first()
   val title=if(s.paused)"Пауза"else stageLabel(s)
   val posted=withContext(Dispatchers.Main.immediate){
    if(outputsClosed||app.resolveWorkoutRepository(access)!==repository)return@withContext false
    if(s.paused)pauseOutputs() else resumeOutputs(prefs)
    updateCue(s,title,prefs)
    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1,notification(title,s,access))
    true
   }
   if(!posted)break
   delay(100)
  }}catch(e:CancellationException){throw e}catch(_:LocalWorkoutAccessRevoked){Unit}catch(e:Exception){
   runCatching{repository.mutateSession(id,expected){recoverSession(it,app.workoutNow())}}
   (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(2,notification("Сбой сопровождения. Откройте FORMA.",null))
  }finally{
   withContext(NonCancellable+Dispatchers.Main.immediate){
    if(outputsClosed)return@withContext
    pauseOutputs()
    // Do not tear down a service whose newer start targets another workout.
    if(lastRequestedSession==expected&&lastRequestedScope==access)stopSelf(lastStartId)
   }
  }
 }
 private fun resumeOutputs(prefs:Preferences){
  if(outputsClosed)return
  val lock=wake?:run {
   val power=getSystemService(POWER_SERVICE) as PowerManager
   power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"forma:workout").also{wake=it}
  }
  if(!lock.isHeld)lock.acquire(10800000)
  // Do not replace a pending focus-loss pause with an immediate re-request.
  val existing=focusLease
  if(existing!=null&&!audioFocusGate.isCurrent(existing))releaseFocus()
  if((prefs.sound||prefs.voice)&&!focusGranted&&!focusLossPending){releaseFocus();requestFocus()}
  if(toneVolume!=prefs.volume){
   tone?.release()
   tone=runCatching{ToneGenerator(AudioManager.STREAM_MUSIC,prefs.volume)}.getOrNull()
   toneVolume=prefs.volume
  }
 }
 private fun resetCue(){
  lastStage=""
  nextBeat=0L
 }
 private fun updateCue(session:Session,title:String,prefs:Preferences){
  if(outputsClosed)return
  val stage=sessionStage(session)
  if(stage!=lastStage){
   lastStage=stage
   nextBeat=0L
   if(!session.paused)cue(title,prefs)
  }
  if(!prefs.voice)tts?.stop()
  if(!prefs.sound)tone?.stopTone()
  val now=SystemClock.elapsedRealtime()
  if(prefs.metronome&&prefs.sound&&focusGranted&&!session.paused&&session.phase==Phase.WORK&&now>=nextBeat){
   tone?.startTone(ToneGenerator.TONE_PROP_BEEP,65)
   nextBeat=now+60000L/prefs.bpm
  }
 }
 private fun silence(){
  if(outputsClosed)return
  tts?.stop()
  tone?.stopTone()
  nextBeat=0L
 }
 private fun pauseOutputs(){
  if(outputsClosed)return
  silence()
  releaseFocus()
  wake?.takeIf{it.isHeld}?.release()
 }
 private fun closeOutputs(){
  if(outputsClosed)return
  pauseOutputs()
  outputsClosed=true
  tts?.shutdown()
  tts=null
  tone?.release()
  tone=null
  wake=null
 }
 private fun releaseFocus(){
  val request=focusRequest?:return
  audioFocusGate.expire()
  focusRequest=null
  focusLease=null
  focusGranted=false
  manager.abandonAudioFocusRequest(request)
 }
 private fun cue(text:String,p:Preferences){if(!focusGranted)return;if(p.sound)tone?.startTone(ToneGenerator.TONE_PROP_ACK,160);if(p.voice&&ready){tts?.setSpeechRate(p.speechRate);tts?.speak(text,TextToSpeech.QUEUE_FLUSH,Bundle().apply{putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,p.volume/100f)},"forma-stage")}}
 private fun notification(text:String,s:Session?,access:String?=null):Notification{
  val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val builder=NotificationCompat.Builder(this,"workout").setSmallIcon(R.drawable.ic_notification).setContentTitle("FORMA · тренировка").setContentText(if(access==null)text else if(s?.paused==true)"Пауза" else "Сопровождение активно").setContentIntent(open).setOngoing(s!=null)
  if(s!=null){
   fun command(action:String,code:Int)=PendingIntent.getService(this,code,Intent(this,WorkoutService::class.java).setAction(action).setData(android.net.Uri.parse("forma://workout/${s.id}/$action/${access.orEmpty()}")).putExtra("profile",s.profileId).putExtra("session",s.id).putExtra("workoutScope",access),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
   if(!s.paused)builder.addAction(0,"Пауза",command("PAUSE",1))
   builder.addAction(0,"Стоп",command("STOP",2))
  }
  return builder.build()
 }
 override fun onDestroy(){
  val id=profileId
  val expected=sessionId
  val access=workoutScopeId
  // This callback is not delivered after a full process kill. Process-death
  // recovery handles that case; this covers same-process service teardown.
  // Ignore an old instance if a newer user command invalidated its epoch.
  if(id!=null&&expected!=null&&app.audioFocusEpoch.isCurrent(acceptedCommandEpoch)){
   app.serviceInterruptions.record(id,expected)
   // Service scope is about to be cancelled. The application owns this write.
   // The in-memory mark also makes an immediate Resume/STOP/next tick recover
   // before it can credit any time after service supervision was lost.
   app.applicationScope.launch{
    try{app.mutateWorkoutSession(id,expected,scopeId=access){it}}
    catch(e:CancellationException){throw e}
    catch(_:LocalWorkoutAccessRevoked){Unit}
    catch(e:Exception){app.serviceError.value="Сопровождение завершилось. Не удалось подтвердить паузу — проверьте тренировку."}
   }
  }
  app.serviceRunning=false
  job?.cancel()
  scope.cancel()
  closeOutputs()
  super.onDestroy()
 }
}
fun stageLabel(s:Session)=when(s.phase){Phase.WARMUP->"Разминка";Phase.WORK->"${s.current?.exercise?.name} · подход ${s.setNumber}";Phase.REST->"Отдых";Phase.CARDIO->if(s.day.cardio==CardioKind.INTERVAL)if(s.cardioIndex%2==0)"Ходьба"else"Лёгкий бег"else s.day.cardio?.label.orEmpty();Phase.DONE->"Завершено";Phase.STOPPED->"Остановлено"}
