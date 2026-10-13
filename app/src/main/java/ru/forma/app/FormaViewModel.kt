package ru.forma.app
import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ru.forma.domain.*
class FormaViewModel(application:Application):AndroidViewModel(application){
 private val app=application as FormaApp
 val error=MutableStateFlow<String?>(null)
 val profiles=app.repository.profiles.catch{error.value="Ошибка данных: ${it.message}"}.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
 val prefs=app.settings.settings.stateIn(viewModelScope,SharingStarted.Eagerly,Preferences())
 val current=combine(profiles,prefs){ps,s->ps.firstOrNull{it.id==s.selected}?:ps.firstOrNull()}.stateIn(viewModelScope,SharingStarted.Eagerly,null)
 init{action{if(!app.serviceRunning)app.repository.profiles.first().filter{it.active?.terminal==false}.forEach{profile->app.repository.mutate(profile.id){p->p.copy(active=p.active?.let{recoverSession(it,System.currentTimeMillis())})}}}}
 private fun action(block:suspend()->Unit){viewModelScope.launch{try{block()}catch(e:Exception){error.value=e.message?:"Ошибка сохранения"}}}
 fun select(id:String){if(profiles.value.any{it.active!=null}){error.value="Сначала завершите тренировку";return};action{app.settings.select(id)}}
 fun saveProfile(p:Profile){action{require(p.valid()){ "Проверьте имя, возраст (18–100), рост и вес" };if(app.repository.load(p.id)==null)app.repository.saveProfile(p)else app.repository.mutate(p.id){p.copy(history=it.history,measurements=it.measurements,active=it.active)};app.settings.select(p.id)}}
 fun start(day:TrainingDay){val p=current.value?:return;action{require(profiles.value.none{it.active!=null}){"Есть незавершённое занятие"};require(p.limitations.isBlank()&&p.recovery>1);app.repository.start(p.id,day,System.currentTimeMillis());startService(p.id)}}
 private fun startService(id:String){ContextCompat.startForegroundService(app,Intent(app,WorkoutService::class.java).putExtra("profile",id))}
 fun resume(){val id=current.value?.id?:return;action{app.repository.mutate(id){p->p.copy(active=p.active?.let{resumeSession(it,System.currentTimeMillis())})};startService(id)}}
 fun pause(){mutateSession{pauseSession(it,System.currentTimeMillis())}}
 fun skip(){mutateSession{skipStage(it,System.currentTimeMillis())}}
 fun done(w:Double,r:Int,e:Int,pain:Boolean){mutateSession{logSet(it,w,r,e,pain,System.currentTimeMillis())}}
 fun stop(){mutateSession{stopSession(it)}}
 fun painStop(){mutateSession{stopSession(it,true)}}
 private fun mutateSession(op:(Session)->Session){val id=current.value?.id?:return;action{app.repository.mutate(id){p->p.copy(active=p.active?.let(op))}}}
 fun finish(){val id=current.value?.id?:return;action{app.repository.mutate(id){p->p.active?.let{finishSession(p,it,System.currentTimeMillis())}?:p};app.stopService(Intent(app,WorkoutService::class.java))}}
 fun measurement(values:Map<String,Double>){val id=current.value?.id?:return;action{val m=Measurement(dateMs=System.currentTimeMillis(),values=values);require(m.valid());app.repository.mutate(id){it.copy(measurements=it.measurements+m,weightKg=values["weight"]?:it.weightKg,heightCm=values["height"]?:it.heightCm)}}}
 fun audio(v:Boolean,s:Boolean,m:Boolean){action{app.settings.audio(v,s,m)}}
 fun dark(v:Boolean?){action{app.settings.dark(v)}}
}
