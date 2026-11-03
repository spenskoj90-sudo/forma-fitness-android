package ru.forma.app

import java.util.concurrent.atomic.AtomicLong

/** In-memory command epoch shared by the Activity and foreground service. */
internal class AudioFocusEpoch {
 private val value=AtomicLong(0L)
 fun snapshot():Long=value.get()
 fun invalidate():Long=value.incrementAndGet()
 fun isCurrent(epoch:Long):Boolean=value.get()==epoch
}

internal data class AudioFocusLease(val requestId:Long,val commandEpoch:Long)

/** Both a request replacement and a user command invalidate old callbacks. */
internal class AudioFocusLeaseGate(private val commands:AudioFocusEpoch){
 private val requests=AtomicLong(0L)
 fun open():AudioFocusLease=AudioFocusLease(requests.incrementAndGet(),commands.snapshot())
 fun expire(){requests.incrementAndGet()}
 fun isCurrent(lease:AudioFocusLease):Boolean=
  requests.get()==lease.requestId&&commands.isCurrent(lease.commandEpoch)
}
