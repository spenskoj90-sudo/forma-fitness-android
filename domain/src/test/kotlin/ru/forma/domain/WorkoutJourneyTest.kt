package ru.forma.domain

import org.junit.Assert.*
import org.junit.Test

class WorkoutJourneyTest {
    private val push = Exercise("push","Жим гантелей лёжа",Muscle.CHEST,Equipment.DUMBBELL,"Контроль движения")
    private val row = Exercise("row","Тяга в наклоне",Muscle.BACK,Equipment.DUMBBELL,"Прямая спина")
    private val day = TrainingDay(1,"Сила",exercises=listOf(
        Prescription(push,sets=2,reps=12,restSec=90),
        Prescription(row,sets=1,reps=10,restSec=60)
    ))

    @Test fun instructionsAndRestPointToTheCorrectNextSetAndExercise() {
        val warmup = startSession("a",day,1_000L)
        assertTrue(workoutJourneyOverview(warmup).nextStep.contains("Разминка"))
        val first = skipStage(warmup,1_000L)
        assertTrue(workoutJourneyOverview(first).nextStep.contains("Подход 1 из 2"))
        val rest1 = logSet(first,8.0,12,7,false,1_100L)
        assertEquals(1,workoutJourneyOverview(rest1).loggedSets)
        assertEquals(3,workoutJourneyOverview(rest1).plannedSets)
        assertTrue(workoutJourneyOverview(rest1).nextStep.contains("подход 2 из 2"))
        val second = skipStage(rest1,1_100L)
        val rest2 = logSet(second,8.0,12,7,false,1_200L)
        assertTrue(workoutJourneyOverview(rest2).nextStep.contains("Тяга в наклоне"))
        assertEquals(1,workoutJourneyOverview(rest2).exerciseNumber)
        val third = skipStage(rest2,1_200L)
        assertEquals(2,workoutJourneyOverview(third).exerciseNumber)
        val done = logSet(third,8.0,10,7,false,1_300L)
        assertEquals(Phase.DONE,done.phase)
        assertEquals(3,workoutJourneyOverview(done).loggedSets)
        assertTrue(workoutJourneyOverview(done).nextStep.contains("Сохраните"))
    }

    @Test fun pausedAndStoppedSessionsHaveHonestGuidanceAndNoPhantomResults() {
        val active = skipStage(startSession("a",day,1_000L),1_000L)
        val paused = pauseSession(active,1_100L)
        assertTrue(workoutJourneyOverview(paused).nextStep.contains("паузе"))
        assertEquals(0,workoutJourneyOverview(paused).loggedSets)
        val stopped = stopSession(paused,false,2_100L)
        assertEquals(0,workoutJourneyOverview(stopped).loggedSets)
        assertTrue(workoutJourneyOverview(stopped).nextStep.contains("остановлена"))
        assertEquals(0,workoutJourneyOverview(startSession("a",TrainingDay(1,"Кардио",cardio=CardioKind.WALK),1_000L)).exerciseCount)
    }
}
