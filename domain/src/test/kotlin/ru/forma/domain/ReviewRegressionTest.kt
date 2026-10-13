package ru.forma.domain
import org.junit.Test
import org.junit.Assert.*
class ReviewRegressionTest {
 private val p=Profile(id="a",name="A")
 @Test fun killedTimeIsNotCardioCredit(){val day=TrainingDay(2,"Кардио",cardio=CardioKind.WALK,durationSec=1200);val started=skipStage(startSession(p.id,day,0),0);val last=advanceTimed(started,60000);val recovered=recoverSession(last,1860000);assertTrue(recovered.paused);assertEquals(1140,remainingSeconds(recovered,1860000));assertEquals(Phase.CARDIO,advanceTimed(resumeSession(recovered,1860000),1860000).phase)}
 @Test fun painStopsEveryPhaseEvenPaused(){val day=generateProgram(p).first();val warm=startSession(p.id,day,0);val work=skipStage(warm,0);val rest=logSet(work,0.0,12,7,false,0);val cardio=skipStage(startSession(p.id,TrainingDay(2,"Кардио",cardio=CardioKind.WALK),0),0);for(s in listOf(warm,work,rest,cardio,pauseSession(work,0))){val stopped=stopSession(s,true);assertEquals(Phase.STOPPED,stopped.phase);assertEquals(1,finishSession(p,stopped,1000).recovery)}}
 @Test fun terminalResultCannotBeOverwritten(){val s=startSession(p.id,generateProgram(p).first(),0).copy(phase=Phase.DONE);assertEquals(s,stopSession(s,false));assertEquals(s,stopSession(s,true))}
 @Test fun oneSetThenSkipsIsNotCompleted(){var s=skipStage(startSession(p.id,generateProgram(p).first(),0),0);s=logSet(s,0.0,12,7,false,0);while(!s.terminal)s=skipStage(s,0);val saved=finishSession(p,s,1000);assertEquals(1,saved.history.single().sets.size);assertEquals(Phase.STOPPED,saved.history.single().status)}
 @Test fun measurementBoundsRespectProfile(){assertFalse(Measurement(dateMs=0,values=mapOf("weight" to 10.0)).valid());assertFalse(Measurement(dateMs=0,values=mapOf("height" to 250.0)).valid())}
}
