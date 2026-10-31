package ru.forma.app

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import ru.forma.domain.Session
import ru.forma.domain.recoverSession

/** An unexpected service teardown is visible immediately, before Room IO. */
internal data class InterruptedWorkout(val profileId:String,val sessionId:String,val generation:Long)

internal class ServiceInterruptionTracker {
 private val generation=AtomicLong(0)
 private val interrupted=AtomicReference<InterruptedWorkout?>(null)

 fun record(profileId:String,sessionId:String):InterruptedWorkout =
  InterruptedWorkout(profileId,sessionId,generation.incrementAndGet()).also{interrupted.set(it)}

 fun pending(profileId:String,sessionId:String):InterruptedWorkout? =
  interrupted.get()?.takeIf{it.profileId==profileId&&it.sessionId==sessionId}

 fun clear(expected:InterruptedWorkout){interrupted.compareAndSet(expected,null)}
}

/** The interruption is consumed only after the Room transaction commits. */
internal suspend fun mutateWithServiceInterruption(
 repository:ProfileRepository,
 tracker:ServiceInterruptionTracker,
 profileId:String,sessionId:String,stage:String?=null,
 now:()->Long,
 op:(Session)->Session
){
 var applied:InterruptedWorkout?=null
 repository.mutateSession(profileId,sessionId,stage){current->
  val interruption=tracker.pending(current.profileId,current.id)
  applied=interruption
  op(if(interruption==null)current else recoverSession(current,now()))
 }
 applied?.let(tracker::clear)
}
