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

    @Test fun skippedFirstSetNeverClaimsRecordedResultsForThatSlot() {
        val first = skipStage(startSession("a",day,1_000L),1_000L)
        val skipped = skipStage(first,1_000L)
        assertEquals(0,skipped.sets.size)
        assertEquals(2,skipped.setNumber)
        assertEquals(WorkoutSetSlotStatus.PASSED,workoutSetSlotStatus(skipped,1))
        assertEquals(WorkoutSetSlotStatus.CURRENT,workoutSetSlotStatus(skipped,2))
        val recorded = logSet(skipped,8.0,12,7,false,1_100L)
        assertEquals(1,workoutJourneyOverview(recorded).loggedSets)
        assertEquals(WorkoutSetSlotStatus.PASSED,workoutSetSlotStatus(recorded,1))
        assertEquals(WorkoutSetSlotStatus.CURRENT,workoutSetSlotStatus(recorded,2))
    }

    @Test fun bodyweightLoadLabelUsesRecordedEquipmentWithoutEstimatingBodyMass() {
        val set = SetResult("push", 12, 0.0, 7, false, 1_000L)
        assertEquals("собственный вес", recordedSetLoadDisplay(set, Equipment.BODYWEIGHT))
        assertEquals("0.0 кг на одну гантель", recordedSetLoadDisplay(set, Equipment.DUMBBELL))
        assertEquals("0.0 кг (единица не определена)", recordedSetLoadDisplay(set, null))
    }

    @Test fun historyPaginationIsNewestFirstStableAndNeverMutatesStorage() {
        val records = (1..23).map { i -> Workout("w$i", day, i.toLong(), i.toLong(), emptyList(), Phase.DONE) }.reversed()
        val before = records.toList()
        assertEquals(listOf("w23", "w22", "w21"), recentWorkoutHistory(records, 3).map { it.id })
        assertEquals(23, recentWorkoutHistory(records, 100).size)
        assertTrue(recentWorkoutHistory(records, -1).isEmpty())
        assertEquals(before, records)
        val tied = listOf(Workout("z", day, 4, 9, emptyList(), Phase.DONE), Workout("a", day, 4, 9, emptyList(), Phase.DONE))
        assertEquals(listOf("a", "z"), recentWorkoutHistory(tied, 2).map { it.id })
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
