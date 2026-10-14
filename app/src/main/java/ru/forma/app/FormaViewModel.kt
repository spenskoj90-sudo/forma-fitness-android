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
 val error=MutableStateFlow<String?>(null)
 val notice=MutableStateFlow<String?>(null)
 val coachStatus=app.coachStatus
 val profiles=app.repository.profiles.catch{error.value="Не удалось прочитать локальные данные"}.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
 val prefs=app.settings.settings.stateIn(viewModelScope,SharingStarted.Eagerly,Preferences())
 val current=combine(profiles,prefs){ps,s->ps.firstOrNull{it.id==s.selected}?:ps.firstOrNull()}.stateIn(viewModelScope,SharingStarted.Eagerly,null)
 init{action{if(!app.serviceRunning)app.repository.profiles.first().filter{it.active?.terminal==false}.forEach{profile->app.repository.mutateSession(profile.id,profile.active!!.id){recoverSession(it,app.workoutNow())}}}}
 private fun action(block:suspend()->Unit){viewModelScope.launch{try{block()}catch(e:CancellationException){throw e}catch(e:Exception){error.value=e.message?:"Ошибка сохранения"}}}
 fun select(id:String){action{require(app.repository.profiles.first().none{it.active!=null}){"Сначала завершите тренировку"};app.settings.select(id)}}
 fun saveProfile(p:Profile,onSaved:()->Unit={}){action{require(p.valid()){ "Проверьте имя, возраст (18–100), рост и вес" };if(app.repository.load(p.id)==null)app.repository.saveProfile(p)else app.repository.mutate(p.id){p.copy(history=it.history,measurements=it.measurements,active=it.active)};app.settings.select(p.id);onSaved()}}
 fun start(day:TrainingDay){val p=current.value?:return;action{val s=app.repository.start(p.id,day,app.workoutNow());startService(p.id,s.id)}}
 private fun startService(id:String,session:String){ContextCompat.startForegroundService(app,Intent(app,WorkoutService::class.java).putExtra("profile",id).putExtra("session",session))}
 fun resume(){val p=current.value?:return;val s=p.active?:return;action{app.repository.mutateSession(p.id,s.id,sessionStage(s)){resumeSession(it,app.workoutNow())};startService(p.id,s.id)}}
 // Pause/stop target the workout ID, not the displayed stage. In particular
 // a rapid Resume -> Stop must not be dropped because the paused flag changed
 // before the queued Room transaction. Stage binding remains for set/skip
 // actions to prevent old taps from advancing a new stage.
 fun pause(){mutateSession(stageBound=false){pauseSession(it,app.workoutNow())}}
 fun skip(){mutateSession{skipStage(it,app.workoutNow())}}
 fun done(w:Double,r:Int,e:Int,pain:Boolean){mutateSession{logSet(it,w,r,e,pain,app.workoutNow())}}
 fun stop(){mutateSession(stageBound=false){stopSession(it,false,app.workoutNow())}}
 fun painStop(){mutateSession(stageBound=false){stopSession(it,true,app.workoutNow())}}
 private fun mutateSession(stageBound:Boolean=true,op:(Session)->Session){val p=current.value?:return;val s=p.active?:return;action{app.repository.mutateSession(p.id,s.id,if(stageBound)sessionStage(s) else null,op)}}
 fun finish(){val p=current.value?:return;val expected=p.active?.id?:return;action{app.repository.mutate(p.id){v->v.active?.takeIf{it.id==expected}?.let{finishSession(v,it,app.workoutNow())}?:v}}}
 fun measurement(values:Map<String,Double>,id:String?=null,dateMs:Long=System.currentTimeMillis(),onSaved:()->Unit={}){val profile=current.value?.id?:return;action{val m=Measurement(id=id?:java.util.UUID.randomUUID().toString(),dateMs=dateMs,values=values);require(m.valid());app.repository.mutate(profile){p->require(id==null||p.measurements.any{it.id==id}){"Замер больше не существует"};val records=if(id==null)p.measurements+m else p.measurements.map{if(it.id==id)m else it};val latest=records.sortedBy{it.dateMs};p.copy(measurements=records,weightKg=latest.lastOrNull{it.values.containsKey("weight")}?.values?.get("weight")?:p.weightKg,heightCm=latest.lastOrNull{it.values.containsKey("height")}?.values?.get("height")?:p.heightCm)};onSaved()}}
 fun exportBackup(uri:Uri){action{withContext(Dispatchers.IO){val text=app.repository.exportBackup();val output=app.contentResolver.openOutputStream(uri,"wt")?:error("Не удалось открыть файл");output.use{it.write(text.toByteArray(Charsets.UTF_8))}};notice.value="Резервная копия сохранена. Храните её в личном месте: она содержит замеры и ограничения."}}
 fun importBackup(uri:Uri){action{val ids=withContext(Dispatchers.IO){val input=app.contentResolver.openInputStream(uri)?:error("Не удалось открыть файл");val bytes=input.use{stream->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=stream.read(buffer);if(n<0)break;require(out.size()+n<=MAX_BACKUP_BYTES){"Файл превышает 8 МБ"};out.write(buffer,0,n)};out.toByteArray()};app.repository.importBackup(bytes.toString(Charsets.UTF_8))};notice.value="Восстановлено профилей: ${ids.size}. Они добавлены отдельно; существующие данные сохранены."}}
 fun audio(v:Boolean,s:Boolean,m:Boolean){action{app.settings.audio(v,s,m)}}
 fun coach(volume:Int,bpm:Int,rate:Float){action{app.settings.coach(volume,bpm,rate)}}
 fun dark(v:Boolean?){action{app.settings.dark(v)}}
}
