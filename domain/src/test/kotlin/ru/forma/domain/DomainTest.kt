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
 @Test fun secondaryGoalAffectsCardio() {assertTrue(generateProgram(p.copy(secondary=Goal.ENDURANCE)).first{it.cardio!=null}.durationSec>generateProgram(p.copy(secondary=null)).first{it.cardio!=null}.durationSec)}
 @Test fun cardioIntervalEndsCorrectly() {val s=skipStage(startSession(p.id,TrainingDay(2,"Интервалы",cardio=CardioKind.INTERVAL,durationSec=300),0),0);assertEquals(Phase.CARDIO,advanceTimed(s,299000).phase);assertEquals(Phase.DONE,advanceTimed(s,300000).phase)}
}
