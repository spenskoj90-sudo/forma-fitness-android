package ru.forma.domain
import kotlinx.serialization.Serializable
import java.util.UUID
@Serializable enum class Phase{WARMUP,WORK,REST,CARDIO,DONE,STOPPED}
@Serializable data class Session(val id:String=UUID.randomUUID().toString(),val profileId:String,val day:TrainingDay,val startedMs:Long,val phase:Phase=Phase.WARMUP,val exerciseIndex:Int=0,val setNumber:Int=1,val deadlineMs:Long=0,val paused:Boolean=false,val pausedRemainingSec:Int=0,val sets:List<SetResult> = emptyList(),val cardioIndex:Int=0,val cardioElapsedSec:Int=0,val painReported:Boolean=false,val activeSeconds:Int=0,val lastTickMs:Long=startedMs){val current:Prescription? get()=day.exercises.getOrNull(exerciseIndex);val terminal:Boolean get()=phase==Phase.DONE||phase==Phase.STOPPED}
fun startSession(profileId:String,day:TrainingDay,now:Long):Session{require(day.exercises.isNotEmpty()||day.cardio!=null);require(day.durationSec in 60..7200&&day.intervals.size>=2&&day.intervals.all{it in 10..1800});return Session(profileId=profileId,day=day,startedMs=now,deadlineMs=now+300000)}
fun remainingSeconds(s:Session,now:Long)=if(s.paused)s.pausedRemainingSec else((s.deadlineMs-now+999)/1000).coerceIn(0,7200).toInt()
fun pauseSession(s:Session,now:Long)=if(s.paused||s.terminal)s else s.copy(paused=true,pausedRemainingSec=remainingSeconds(s,now),lastTickMs=now)
fun resumeSession(s:Session,now:Long)=if(!s.paused||s.terminal)s else s.copy(paused=false,deadlineMs=now+s.pausedRemainingSec*1000L,lastTickMs=now)
fun recoverSession(s:Session,now:Long):Session = if(s.terminal)s else pauseSession(s,s.lastTickMs).copy(lastTickMs=now)
fun stopSession(s:Session,pain:Boolean=false):Session = if(s.terminal)s else s.copy(phase=Phase.STOPPED,paused=false,deadlineMs=0,painReported=s.painReported||pain)
private fun completionPhase(s:Session)=if(s.day.cardio!=null || s.sets.size==s.day.exercises.sumOf{it.sets})Phase.DONE else Phase.STOPPED
private fun cardioSeconds(s:Session,index:Int)=if(s.day.cardio==CardioKind.INTERVAL)minOf(s.day.intervals[index%s.day.intervals.size],s.day.durationSec-s.cardioElapsedSec)else s.day.durationSec
private fun enterMain(s:Session,now:Long)=if(s.day.cardio!=null)s.copy(phase=Phase.CARDIO,deadlineMs=now+cardioSeconds(s,0)*1000L)else s.copy(phase=Phase.WORK,deadlineMs=0)
private fun nextSet(s:Session):Session{val current=s.current?:return s.copy(phase=Phase.DONE);return if(s.setNumber<current.sets)s.copy(phase=Phase.WORK,setNumber=s.setNumber+1,deadlineMs=0)else if(s.exerciseIndex<s.day.exercises.lastIndex)s.copy(phase=Phase.WORK,exerciseIndex=s.exerciseIndex+1,setNumber=1,deadlineMs=0)else s.copy(phase=completionPhase(s),deadlineMs=0)}
fun advanceTimed(s:Session,now:Long):Session{
 if(s.paused||s.terminal)return s;if(now<s.lastTickMs)return pauseSession(s,s.lastTickMs)
 var v=s.copy(activeSeconds=s.activeSeconds+((now-s.lastTickMs)/1000).coerceAtMost(86400).toInt(),lastTickMs=now)
 if(v.phase==Phase.WARMUP&&now>=v.deadlineMs)v=enterMain(v,now)
 if(v.phase==Phase.REST&&now>=v.deadlineMs)v=nextSet(v)
 while(v.phase==Phase.CARDIO&&now>=v.deadlineMs){val duration=cardioSeconds(v,v.cardioIndex);v=v.copy(cardioElapsedSec=v.cardioElapsedSec+duration,cardioIndex=v.cardioIndex+1);v=if(v.cardioElapsedSec>=v.day.durationSec)v.copy(phase=Phase.DONE,deadlineMs=0)else v.copy(deadlineMs=v.deadlineMs+cardioSeconds(v,v.cardioIndex)*1000L)}
 return v
}
fun logSet(s:Session,weight:Double,reps:Int,effort:Int,pain:Boolean,now:Long):Session{
 if(pain)return stopSession(s,true)
 if(s.phase!=Phase.WORK||s.paused||s.terminal)return s
 require(weight.isFinite()&&weight in 0.0..500.0&&reps in 1..100&&effort in 1..10)

 val current=s.current?:return s;val result=SetResult(current.exercise.id,reps,if(current.exercise.equipment==Equipment.BODYWEIGHT)0.0 else weight,effort,false,now);val v=s.copy(sets=s.sets+result)
 return if(s.exerciseIndex==s.day.exercises.lastIndex&&s.setNumber==current.sets)v.copy(phase=completionPhase(v),deadlineMs=0)else v.copy(phase=Phase.REST,deadlineMs=now+current.restSec*1000L)
}
fun skipStage(s:Session,now:Long)=when{ s.paused||s.terminal->s;s.phase==Phase.WARMUP->enterMain(s,now);s.phase==Phase.REST||s.phase==Phase.WORK->nextSet(s);s.phase==Phase.CARDIO->s.copy(phase=Phase.STOPPED,deadlineMs=0);else->s}
fun finishSession(p:Profile,s:Session,now:Long):Profile{
 if(s.profileId!=p.id||!s.terminal)return p
 val history=if(p.history.any{it.id==s.id})p.history else p.history+Workout(s.id,s.day,s.startedMs,now,s.sets,if(s.phase==Phase.DONE)completionPhase(s) else s.phase,s.activeSeconds)
 return p.copy(history=history,recovery=if(s.painReported)1 else p.recovery,active=if(p.active?.id==s.id)null else p.active)
}
