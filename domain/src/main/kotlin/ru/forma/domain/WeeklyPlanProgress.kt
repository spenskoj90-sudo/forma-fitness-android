package ru.forma.domain

/**
 * Counts distinct days from the *current scheduled plan* completed during the
 * requested time window. Extra cardio and repeat sessions remain in the activity
 * totals, but never inflate the scheduled-plan progress indicator.
 *
 * Match cardio by category (not exact CardioKind), because users may change
 * the mode of a scheduled cardio session in the UI.
 */
fun countCompletedPlannedDays(
    history: List<Workout>,
    plan: List<TrainingDay>,
    fromInclusiveMs: Long,
    toInclusiveMs: Long
): Int {
    if (plan.isEmpty() || toInclusiveMs < fromInclusiveMs) return 0
    val plannedCardioByDay = plan.associate { it.day to (it.cardio != null) }
    return history.asSequence()
        .filter { it.status == Phase.DONE && it.finishedMs in fromInclusiveMs..toInclusiveMs }
        .mapNotNull { workout ->
            val isPlannedCardio = plannedCardioByDay[workout.day.day] ?: return@mapNotNull null
            workout.day.day.takeIf { (workout.day.cardio != null) == isPlannedCardio }
        }
        .distinct()
        .count()
}
