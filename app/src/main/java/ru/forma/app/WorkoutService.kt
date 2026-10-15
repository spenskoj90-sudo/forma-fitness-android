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
 private var job:Job?=null
 // Android may dispatch a new START before the previous workout loop exits.
 private val commandMutex=Mutex()
 private var tts:TextToSpeech?=null
 @Volatile private var ready=false
 @Volatile private var focusGranted=false
 private var tone:ToneGenerator?=null
 private var toneVolume=-1
 private var wake:PowerManager.WakeLock?=null
 private var profileId:String?=null
 private var sessionId:String?=null
 // onStartCommand is serialized on the main thread. Keep the latest Android
 // start id, so a completed session can stop after PAUSE/STOP commands too.
 @Volatile private var lastRequestedSession:String?=null
 @Volatile private var lastStartId:Int=0
 private var lastStage=""
 private var nextBeat=0L
 private val app get()=application as FormaApp
 private val manager by lazy{getSystemService(AUDIO_SERVICE) as AudioManager}
 private val focus by lazy{AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
  .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
  .setOnAudioFocusChangeListener({change->
   if(change!=AudioManager.AUDIOFOCUS_GAIN){focusGranted=false;silence();val id=profileId;val session=sessionId;if(id!=null&&session!=null)scope.launch{app.repository.mutateSession(id,session){pauseSession(it,app.workoutNow())}}}
   else focusGranted=true
  },Handler(Looper.getMainLooper())).build()}
 override fun onCreate(){super.onCreate()
  (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("workout","Тренировка FORMA",NotificationManager.IMPORTANCE_LOW))
  tts=TextToSpeech(this,this)
 }
 override fun onInit(status:Int){
  if(status==TextToSpeech.SUCCESS)ready=runCatching{val voice=tts?.voices?.firstOrNull{it.locale.language=="ru"&&!it.isNetworkConnectionRequired};if(voice!=null){tts?.voice=voice;true}else false}.getOrDefault(false)
  app.coachStatus.value=if(ready)"Русский офлайн-голос готов"else"Русский офлайн-голос недоступен. Используются экранные подсказки и включённые сигналы."
 }
 override fun onBind(intent:Intent?):IBinder?=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  // Keep each Android startId: an old loop must never stop a newer START.
  startForeground(1,notification("Подготовка тренировки",null))
  val id=intent?.getStringExtra("profile")
  val expected=intent?.getStringExtra("session")
  lastRequestedSession=expected
  lastStartId=startId
  if(id==null||expected==null){
   if(job?.isActive!=true)stopSelf(startId)
   return START_NOT_STICKY
  }
  scope.launch{
   commandMutex.withLock{
    val active=app.repository.load(id)?.active
    if(active==null||active.id!=expected||active.terminal){
     // A stale PendingIntent cannot supersede the real running session.
     if(job?.isActive==true)lastRequestedSession=sessionId
     else stopSelf(startId)
     return@withLock
    }
    if(sessionId!=null&&sessionId!=expected){
     // Await resource cleanup (TTS, focus, wakelock) before starting a
     // replacement session. A stale notification cannot mutate this one.
     job?.cancelAndJoin()
     job=null
     lastStage=""
     nextBeat=0L
    }
    if(active.lastTickMs<app.processStartedMs)app.repository.mutateSession(id,expected){recoverIfPreviousProcess(it,app.processStartedMs,app.workoutNow())}
    profileId=id;sessionId=expected
    when(intent.action){
     "PAUSE"->{app.repository.mutateSession(id,expected){pauseSession(it,app.workoutNow())};silence()}
     "STOP"->{app.repository.mutateSession(id,expected){stopSession(it,false,app.workoutNow())};silence()}
    }
    if(intent.action=="STOP"){
     // A newer START can arrive while the cancelled loop is completing.
     job?.cancelAndJoin()
     job=null
     app.serviceRunning=false
     stopSelf(startId)
    }else if(job?.isActive!=true){
     app.serviceRunning=true
     job=scope.launch{runWorkout(id,expected)}
    }
   }
  }
  return START_NOT_STICKY
 }
 private suspend fun runWorkout(id:String,expected:String){
  wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"forma:workout")
  try{while(currentCoroutineContext().isActive){
   app.repository.mutateSession(id,expected){advanceTimed(it,app.workoutNow())}
   val s=app.repository.load(id)?.active?.takeIf{it.id==expected}?:break
   if(s.terminal){silence();break}
   val prefs=app.settings.settings.first()
   if(s.paused){silence();releaseFocus();if(wake?.isHeld==true)wake?.release()}
   else{
    if(wake?.isHeld!=true)wake?.acquire(10800000)
    if((prefs.sound||prefs.voice)&&!focusGranted)focusGranted=manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    if(toneVolume!=prefs.volume){tone?.release();tone=runCatching{ToneGenerator(AudioManager.STREAM_MUSIC,prefs.volume)}.getOrNull();toneVolume=prefs.volume}
   }
   val stage=sessionStage(s);val title=if(s.paused)"Пауза"else stageLabel(s)
   if(stage!=lastStage){lastStage=stage;nextBeat=0;if(!s.paused)cue(title,prefs)}
   if(!prefs.voice)tts?.stop()
   if(!prefs.sound)tone?.stopTone()
   (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1,notification(title,s))
   val now=SystemClock.elapsedRealtime()
   if(prefs.metronome&&prefs.sound&&focusGranted&&!s.paused&&s.phase==Phase.WORK&&now>=nextBeat){tone?.startTone(ToneGenerator.TONE_PROP_BEEP,65);nextBeat=now+60000L/prefs.bpm}
   delay(100)
  }}catch(e:CancellationException){throw e}catch(e:Exception){
   runCatching{app.repository.mutateSession(id,expected){recoverSession(it,app.workoutNow())}}
   (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(2,notification("Сбой сопровождения. Откройте FORMA.",null))
  }finally{
   silence();releaseFocus();if(wake?.isHeld==true)wake?.release()
   // Do not tear down a service whose newer start targets another workout.
   if(lastRequestedSession==expected)stopSelf(lastStartId)
  }
 }
 private fun silence(){tts?.stop();tone?.stopTone();nextBeat=0}
 private fun releaseFocus(){if(focusGranted){manager.abandonAudioFocusRequest(focus);focusGranted=false}}
 private fun cue(text:String,p:Preferences){if(!focusGranted)return;if(p.sound)tone?.startTone(ToneGenerator.TONE_PROP_ACK,160);if(p.voice&&ready){tts?.setSpeechRate(p.speechRate);tts?.speak(text,TextToSpeech.QUEUE_FLUSH,Bundle().apply{putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,p.volume/100f)},"forma-stage")}}
 private fun notification(text:String,s:Session?):Notification{
  val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val builder=NotificationCompat.Builder(this,"workout").setSmallIcon(R.drawable.ic_notification).setContentTitle("FORMA · тренировка").setContentText(text).setContentIntent(open).setOngoing(s!=null)
  if(s!=null){
   fun command(action:String,code:Int)=PendingIntent.getService(this,code,Intent(this,WorkoutService::class.java).setAction(action).setData(android.net.Uri.parse("forma://workout/${s.id}/$action")).putExtra("profile",s.profileId).putExtra("session",s.id),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
   if(!s.paused)builder.addAction(0,"Пауза",command("PAUSE",1))
   builder.addAction(0,"Стоп",command("STOP",2))
  }
  return builder.build()
 }
 override fun onDestroy(){app.serviceRunning=false;job?.cancel();scope.cancel();if(wake?.isHeld==true)wake?.release();silence();releaseFocus();tts?.shutdown();tone?.release();super.onDestroy()}
}
fun stageLabel(s:Session)=when(s.phase){Phase.WARMUP->"Разминка";Phase.WORK->"${s.current?.exercise?.name} · подход ${s.setNumber}";Phase.REST->"Отдых";Phase.CARDIO->if(s.day.cardio==CardioKind.INTERVAL)if(s.cardioIndex%2==0)"Ходьба"else"Лёгкий бег"else s.day.cardio?.label.orEmpty();Phase.DONE->"Завершено";Phase.STOPPED->"Остановлено"}
