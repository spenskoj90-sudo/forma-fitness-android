package ru.forma.domain
import org.junit.Test
import org.junit.Assert.*
class TimingRegressionTest {
 private fun cardio()=skipStage(startSession("a",TrainingDay(2,"Кардио",cardio=CardioKind.WALK,durationSec=60),0),0)
 @Test fun subsecondTicksDoNotLoseElapsedTime(){var s=cardio();for(t in 500L..60000L step 500)s=advanceTimed(s,t);assertEquals(Phase.DONE,s.phase);assertEquals(60,s.activeSeconds)}
 @Test fun pauseCreditsOnlyLiveTime(){val s=pauseSession(cardio(),5500);assertEquals(5,s.activeSeconds);assertEquals(55,remainingSeconds(s,999000));val resumed=resumeSession(s,1000000);assertEquals(60,advanceTimed(resumed,1055000).activeSeconds)}
 @Test fun lateTickDoesNotCreditPastCardioCompletion(){assertEquals(60,advanceTimed(cardio(),600000).activeSeconds)}
 @Test fun stoppedSessionRemainsImmutable(){val s=stopSession(cardio());assertEquals(s,advanceTimed(s,600000));assertEquals(s,resumeSession(s,600000))}
}
