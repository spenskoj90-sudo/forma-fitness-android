package ru.forma.app

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.forma.domain.Session
import ru.forma.domain.recoverSession

/** An unexpected service teardown is visible immediately, before Room IO. */
internal data class InterruptedWorkout(val profileId:String,val sessionId:String,val generation:Long)

internal class ServiceInterruptionTracker(
 // Injectable boundary is used only by deterministic Room race regressions.
 private val afterCommitBeforeClear:()->Unit={}
) {
 // Serialize Room commit AND marker retirement. A delayed onDestroy writer
 // must never read a consumed marker after Owner Resume has committed.
 private val mutations=Mutex()
 suspend fun <T> serializeMutation(block:suspend()->T):T=mutations.withLock{block()}
 fun onCommitted(){afterCommitBeforeClear()}
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
 tracker.serializeMutation {
  var applied:InterruptedWorkout?=null
  repository.mutateSession(profileId,sessionId,stage){current->
   val interruption=tracker.pending(current.profileId,current.id)
   applied=interruption
   op(if(interruption==null)current else recoverSession(current,now()))
  }
  // Room returned successfully: retire only the generation applied by this
  // transaction. The lock prevents a delayed teardown write from racing
  // between commit and clear; CAS protects a genuinely newer interruption.
  // No suspension is permitted between successful commit and marker clear.
  applied?.let{expected->
   try{tracker.onCommitted()}finally{tracker.clear(expected)}
  }
 }
}
