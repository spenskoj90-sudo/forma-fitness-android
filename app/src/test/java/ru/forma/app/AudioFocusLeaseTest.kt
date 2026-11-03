package ru.forma.app

import org.junit.Assert.*
import org.junit.Test

class AudioFocusLeaseTest {
 @Test fun userResumeInvalidatesQueuedLossAndGain(){
  val commands=AudioFocusEpoch()
  val requests=AudioFocusLeaseGate(commands)
  val beforeResume=requests.open()
  assertTrue(requests.isCurrent(beforeResume))
  commands.invalidate()
  assertFalse(requests.isCurrent(beforeResume))
  val afterResume=requests.open()
  assertTrue(requests.isCurrent(afterResume))
  assertFalse(requests.isCurrent(beforeResume))
 }
 @Test fun oldAudioRequestCannotAffectReplacementOrStoppedService(){
  val gate=AudioFocusLeaseGate(AudioFocusEpoch())
  val abandoned=gate.open()
  val replacement=gate.open()
  assertFalse(gate.isCurrent(abandoned))
  assertTrue(gate.isCurrent(replacement))
  gate.expire()
  assertFalse(gate.isCurrent(replacement))
 }
}
