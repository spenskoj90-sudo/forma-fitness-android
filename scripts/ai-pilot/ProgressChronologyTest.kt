package ru.forma.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression fixture executed by the local Qwen task verifier against an
 * exact archived source revision. Neither the workout records nor the
 * user's stored history may be modified by the progress counter.
 */
class ProgressChronologyTest {
    private val cardio = TrainingDay(1, "Кардио", cardio = CardioKind.WALK)
    private val plan = listOf(cardio)

    private fun done(id: String, started: Long, finished: Long) =
        Workout(id, cardio, started, finished, emptyList(), Phase.DONE)

    @Test fun impossibleLegacyFinishBeforeStartDoesNotEarnProgress() {
        val damaged = done("legacy-rollback", 200, 100)
        val before = listOf(damaged)
        assertEquals(0, countCompletedPlannedDays(before, plan, 0, 500))
        assertEquals(before, listOf(damaged))
    }

    @Test fun equalStartFinishAndChronologicalWorkoutsStayValid() {
        assertEquals(1, countCompletedPlannedDays(listOf(done("instant", 100, 100)), plan, 0, 500))
        assertEquals(1, countCompletedPlannedDays(listOf(done("normal", 100, 200)), plan, 0, 500))
    }

    @Test fun reversedLegacyRecordCannotOverrideValidSameDaySession() {
        val records = listOf(done("invalid", 400, 200), done("valid", 200, 300))
        assertEquals(1, countCompletedPlannedDays(records, plan, 0, 500))
    }
}
