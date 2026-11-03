package ru.forma.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WeeklyPlanProgressTest {
    @Test fun distinctScheduledDaysExcludeExtraCardioAndRepeatedSessions() {
        val profile = Profile(name = "Test", days = setOf(1, 2, 3, 5, 6))
        val plan = generateProgram(profile)
        val mondayStrength = plan.first { it.day == 1 }
        val tuesdayCardio = plan.first { it.day == 2 }
        val wednesdayStrength = plan.first { it.day == 3 }
        val fridayStrength = plan.first { it.day == 5 }
        val voluntaryCardio = TrainingDay(3, "Кардио по желанию", cardio = CardioKind.JOG)

        fun workout(id: String, day: TrainingDay, finished: Long, status: Phase = Phase.DONE) =
            Workout(id, day, finished - 10, finished, emptyList(), status)

        val history = listOf(
            workout("strength", mondayStrength, 200),
            workout("repeat", mondayStrength, 250),
            workout("off-plan-cardio", voluntaryCardio, 300),
            workout("stopped", fridayStrength, 400, Phase.STOPPED),
            // Changing the mode of scheduled cardio must not erase completion.
            workout("scheduled-cardio", tuesdayCardio.copy(cardio = CardioKind.INTERVAL), 500),
            workout("previous-week", wednesdayStrength, 50),
            workout("future", fridayStrength, 1100)
        )

        assertEquals(2, countCompletedPlannedDays(history, plan, 100, 1000))
        assertEquals(0, countCompletedPlannedDays(history, emptyList(), 100, 1000))
        assertEquals(0, countCompletedPlannedDays(history, plan, 1000, 100))
    }

    @Test fun optionalCardioOnStrengthDayDoesNotSatisfyStrengthPlan() {
        val plan = generateProgram(Profile(name = "Test"))
        val optionalCardio = TrainingDay(1, "Кардио по желанию", cardio = CardioKind.WALK)
        val history = listOf(Workout("cardio", optionalCardio, 150, 200, emptyList(), Phase.DONE))
        assertEquals(0, countCompletedPlannedDays(history, plan, 100, 500))
    }
}
