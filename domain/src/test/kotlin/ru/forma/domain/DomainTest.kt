package ru.forma.domain
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
class DomainTest {
 private val p=Profile(id="a",name="A")
 @Test fun equipmentFilter() { assertTrue(generateProgram(p.copy(equipment=setOf(Equipment.BODYWEIGHT))).flatMap{it.exercises}.all{it.exercise.equipment==Equipment.BODYWEIGHT}) }
 @Test fun noDaysNoPlan() { assertTrue(generateProgram(p.copy(days=emptySet())).isEmpty()) }
 @Test fun restrictionsBlockPlan() { assertTrue(generateProgram(p.copy(limitations="Боль")).isEmpty()) }
 @Test fun poorRecoveryBlocksPlan() { assertTrue(generateProgram(p.copy(recovery=1)).isEmpty()) }
 @Test fun badNumbersRejected() { assertFalse(p.copy(weightKg=Double.NaN).valid());assertFalse(p.copy(age=0).valid()) }
 @Test fun painNeverIncreasesWeight() { assertEquals(10.0,suggestWeight(10.0,12,12,7,true,5,false),0.0) }
 @Test fun recoveryNeverIncreasesWeight() { assertEquals(10.0,suggestWeight(10.0,12,12,7,false,1,false),0.0) }
 @Test fun returnReducesWeight() { assertEquals(7.0,suggestWeight(10.0,12,12,7,false,5,true),0.0) }
 @Test fun progression() { assertEquals(10.5,suggestWeight(10.0,12,12,7,false,5,false),0.0) }
 @Test fun highEffortBlocksProgression() { assertEquals(10.0,suggestWeight(10.0,12,12,9,false,5,false),0.0) }
 @Test fun pausedTimer() { val s=logSet(skipStage(startSession(p.id,generateProgram(p).first(),1000),1000),0.0,12,7,false,1000);val paused=pauseSession(s,11000); assertEquals(80,remainingSeconds(paused,999000));assertEquals(80,remainingSeconds(resumeSession(paused,1000000),1000000)) }
 @Test fun skipsNoFakeResults() { assertTrue(skipStage(startSession(p.id,generateProgram(p).first(),0),0).sets.isEmpty()) }
 @Test fun completionIdempotent() {val s=startSession(p.id,generateProgram(p).first(),0).copy(phase=Phase.DONE);val saved=finishSession(p,s,1000);assertEquals(1,finishSession(saved,s,2000).history.size)}
 @Test fun profilesDoNotMix() {val s=startSession(p.id,generateProgram(p).first(),0).copy(phase=Phase.DONE);assertTrue(finishSession(p.copy(id="b"),s,1000).history.isEmpty())}
 @Test fun painStopsAndPersistsGate() {val s=logSet(skipStage(startSession(p.id,generateProgram(p).first(),0),0),10.0,12,7,true,0);assertEquals(Phase.STOPPED,s.phase);val saved=finishSession(p,s,1000);assertEquals(1,saved.recovery);assertTrue(generateProgram(saved).isEmpty())}
 @Test fun roundTrip() {val v=p.copy(active=startSession(p.id,generateProgram(p).first(),0));assertEquals(v,Json.decodeFromString<Profile>(Json.encodeToString(v)))}
 @Test fun goalChangeKeepsHistory() {val s=startSession(p.id,generateProgram(p).first(),0).copy(phase=Phase.DONE);val saved=finishSession(p,s,1000);assertEquals(saved.history,saved.copy(primary=Goal.STRENGTH).history)}
 @Test fun skippedWorkoutNotCompleted() {var s=skipStage(startSession(p.id,generateProgram(p).first(),0),0);while(!s.terminal)s=skipStage(s,0);assertEquals(Phase.STOPPED,finishSession(p,s,1000).history.single().status)}
 @Test fun defaultStartsWithThreeSeparatedStrengthDays(){
  assertEquals(setOf(1,3,5),p.days)
  val program=generateProgram(p)
  assertEquals(listOf(1,3,5),program.map{it.day})
  assertEquals(3,program.count{it.exercises.isNotEmpty()})
  assertTrue(program.none{it.cardio!=null})
 }
 @Test fun extraSelectedDaysBecomeOptionalScheduledCardio(){
  val scheduled=p.copy(days=setOf(1,2,3,5,6))
  val program=generateProgram(scheduled)
  assertEquals(5,program.size)
  assertEquals(setOf(1,3,5),program.filter{it.cardio==null}.map{it.day}.toSet())
  assertEquals(setOf(2,6),program.filter{it.cardio!=null}.map{it.day}.toSet())
 }
 @Test fun secondaryGoalAffectsCardio() {
  val scheduled=p.copy(days=setOf(1,2,3,5,6))
  assertTrue(generateProgram(scheduled.copy(secondary=Goal.ENDURANCE)).first{it.cardio!=null}.durationSec>
             generateProgram(scheduled.copy(secondary=null)).first{it.cardio!=null}.durationSec)
 }
 @Test fun musclePlanIncludesUpperBodyAndLegs(){
  val groups=generateProgram(p).first().exercises.map{it.exercise.muscle}.toSet()
  assertTrue(groups.containsAll(setOf(Muscle.BACK,Muscle.CHEST,Muscle.SHOULDERS,Muscle.ARMS,Muscle.CORE,Muscle.LEGS)))
 }
 @Test fun delayedStartupRecoveryDoesNotUndoUserResume(){
  val profile=Profile(id="resume",name="User")
  val old=skipStage(startSession(profile.id,generateProgram(profile).first(),1000),1000)
  val paused=pauseSession(old,1500)
  val newProcessStarted=5000L
  val freshlyResumed=resumeSession(paused,5100)
  assertFalse(freshlyResumed.paused)
  assertEquals(freshlyResumed,recoverIfPreviousProcess(freshlyResumed,newProcessStarted,5200))
  assertTrue(recoverIfPreviousProcess(paused,newProcessStarted,5200).paused)
 }
 @Test fun immediateColdStartActionsCannotCountTimeWhileProcessWasAbsent(){
  val before=startSession(p.id,generateProgram(p).first(),1000L)
  val processStarted=100_000L
  val tapAt=110_000L
  // UI may still show the old unpaused snapshot while async recovery starts.
  val resumed=resumeSession(recoverIfPreviousProcess(before,processStarted,tapAt),tapAt)
  assertFalse(resumed.paused)
  assertEquals(before.activeSeconds,resumed.activeSeconds)
  assertEquals(300_000L,resumed.deadlineMs-tapAt)
  assertEquals(tapAt,resumed.lastTickMs)
  val stopped=stopSession(recoverIfPreviousProcess(before,processStarted,tapAt),false,tapAt)
  assertEquals(Phase.STOPPED,stopped.phase)
  assertEquals(before.activeSeconds,stopped.activeSeconds)
  val paused=pauseSession(recoverIfPreviousProcess(before,processStarted,tapAt),tapAt)
  assertTrue(paused.paused)
  assertEquals(before.activeSeconds,paused.activeSeconds)
 }
 @Test fun cardioIntervalEndsCorrectly() {val s=skipStage(startSession(p.id,TrainingDay(2,"Интервалы",cardio=CardioKind.INTERVAL,durationSec=300),0),0);assertEquals(Phase.CARDIO,advanceTimed(s,299000).phase);assertEquals(Phase.DONE,advanceTimed(s,300000).phase)}
}
