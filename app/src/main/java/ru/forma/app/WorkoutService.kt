package ru.forma.app
import android.app.*
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import ru.forma.domain.*
class WorkoutService:Service(),TextToSpeech.OnInitListener{
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);private var job:Job?=null
 private var tts:TextToSpeech?=null;private var ready=false;private var tone:ToneGenerator?=null
 private var wake:PowerManager.WakeLock?=null;private var profileId:String?=null;private var lastStage=""
 override fun onCreate(){super.onCreate();(application as FormaApp).serviceRunning=true
  (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("workout","Тренировка FORMA",NotificationManager.IMPORTANCE_LOW))
  tts=TextToSpeech(this,this);tone=runCatching{ToneGenerator(AudioManager.STREAM_MUSIC,65)}.getOrNull()
 }
 override fun onInit(status:Int){if(status==TextToSpeech.SUCCESS)ready=runCatching{val voice=tts?.voices?.firstOrNull{it.locale.language=="ru"&&!it.isNetworkConnectionRequired};if(voice!=null){tts?.voice=voice;true}else false}.getOrDefault(false)}
 override fun onBind(intent:Intent?):IBinder?=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  profileId=intent?.getStringExtra("profile")?:profileId;startForeground(1,notification("Подготовка тренировки"))
  val id=profileId?:run{stopSelf();return START_NOT_STICKY};val app=application as FormaApp
  if(intent?.action!=null)scope.launch{app.repository.mutate(id){p->p.copy(active=p.active?.let{s->when(intent.action){"STOP"->stopSession(s);"PAUSE"->pauseSession(s,System.currentTimeMillis());else->s}})}}
  if(job?.isActive!=true)job=scope.launch{
   wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"forma:workout").also{it.acquire(10800000)}
   try{while(isActive){
    val s=app.repository.load(id)?.active?:break
    if(s.terminal){cue("Занятие завершено. Сохраните результаты.",app.settings.settings.first());break}
    app.repository.mutate(id){p->p.copy(active=p.active?.let{advanceTimed(it,System.currentTimeMillis())})}
    val updated=app.repository.load(id)?.active?:break;val prefs=app.settings.settings.first()
    val stage="${updated.phase}:${updated.exerciseIndex}:${updated.setNumber}:${updated.cardioIndex}:${updated.paused}";val title=if(updated.paused)"Пауза"else stageLabel(updated)
    if(stage!=lastStage){lastStage=stage;if(!updated.paused)cue(title,prefs)}
    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1,notification(title))
    if(prefs.metronome&&prefs.sound&&!updated.paused&&updated.phase==Phase.WORK)tone?.startTone(ToneGenerator.TONE_PROP_BEEP,65)
    if(updated.paused&&wake?.isHeld==true)wake?.release();if(!updated.paused&&wake?.isHeld!=true)wake?.acquire(10800000)
    delay(1000)
   }}catch(e:Exception){if(e !is CancellationException)(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(2,notification("Сбой сопровождения. Откройте FORMA."))}finally{stopSelf()}
  };return START_NOT_STICKY
 }
 private fun cue(text:String,p:Preferences){if(p.sound)tone?.startTone(ToneGenerator.TONE_PROP_ACK,160);if(p.voice&&ready)tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"forma-stage")}
 private fun notification(text:String):Notification{
  val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  fun command(action:String,code:Int)=PendingIntent.getService(this,code,Intent(this,WorkoutService::class.java).setAction(action).putExtra("profile",profileId),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  return NotificationCompat.Builder(this,"workout").setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("FORMA · тренировка").setContentText(text).setContentIntent(open).setOngoing(true).addAction(0,"Пауза",command("PAUSE",1)).addAction(0,"Стоп",command("STOP",2)).build()
 }
 override fun onDestroy(){(application as FormaApp).serviceRunning=false;job?.cancel();scope.cancel();if(wake?.isHeld==true)wake?.release();tts?.stop();tts?.shutdown();tone?.release();super.onDestroy()}
}
fun stageLabel(s:Session)=when(s.phase){Phase.WARMUP->"Разминка";Phase.WORK->"${s.current?.exercise?.name} · подход ${s.setNumber}";Phase.REST->"Отдых";Phase.CARDIO->if(s.day.cardio==CardioKind.INTERVAL)if(s.cardioIndex%2==0)"Ходьба"else"Лёгкий бег"else s.day.cardio?.label.orEmpty();Phase.DONE->"Завершено";Phase.STOPPED->"Остановлено"}
