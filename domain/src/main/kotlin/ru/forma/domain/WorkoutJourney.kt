package ru.forma.domain

/** Read-only guidance derived from the persisted workout, never a second timer or state store. */
data class WorkoutJourneyOverview(
    val loggedSets: Int,
    val plannedSets: Int,
    val exerciseNumber: Int,
    val exerciseCount: Int,
    val nextStep: String
)

fun workoutJourneyOverview(session: Session): WorkoutJourneyOverview {
    val exercises = session.day.exercises
    val current = session.current
    val next = exercises.getOrNull(session.exerciseIndex + 1)
    val nextStep = when {
        session.phase == Phase.DONE -> "Все этапы выполнены. Сохраните тренировку в историю."
        session.phase == Phase.STOPPED -> "Тренировка остановлена. Выполненные подходы можно сохранить."
        session.paused -> "Тренировка на паузе. Продолжите, когда будете готовы."
        session.phase == Phase.WARMUP -> "Разминка перед основной частью тренировки."
        session.phase == Phase.REST && current != null && session.setNumber < current.sets ->
            "Далее: подход ${session.setNumber + 1} из ${current.sets} — ${current.exercise.name}."
        session.phase == Phase.REST && next != null ->
            "Далее: ${next.exercise.name}, подход 1 из ${next.sets}."
        session.phase == Phase.WORK && current != null ->
            "Подход ${session.setNumber} из ${current.sets} — ${current.exercise.name}."
        session.phase == Phase.CARDIO -> "Кардио: ${session.day.cardio?.label.orEmpty()}."
        else -> "Следуйте текущему этапу тренировки."
    }
    return WorkoutJourneyOverview(
        loggedSets = session.sets.size,
        plannedSets = exercises.sumOf { it.sets },
        exerciseNumber = if (exercises.isEmpty()) 0 else (session.exerciseIndex + 1).coerceIn(1, exercises.size),
        exerciseCount = exercises.size,
        nextStep = nextStep
    )
}

/** The active set index is known; prior indices may have been skipped.
 * Never label a particular slot DONE without persisted per-slot evidence.
 */
enum class WorkoutSetSlotStatus { PASSED, CURRENT, UPCOMING }

fun workoutSetSlotStatus(session: Session, number: Int): WorkoutSetSlotStatus {
    val planned = session.current?.sets ?: 0
    require(number in 1..planned) { "Set number outside current exercise" }
    return when {
        number < session.setNumber -> WorkoutSetSlotStatus.PASSED
        number == session.setNumber -> WorkoutSetSlotStatus.CURRENT
        else -> WorkoutSetSlotStatus.UPCOMING
    }
}

/** Preserve every historic record; limit only the number of cards initially composed. */
fun recentWorkoutHistory(history: List<Workout>, limit: Int): List<Workout> =
    history.sortedWith(compareByDescending<Workout> { it.finishedMs }
        .thenByDescending { it.startedMs }.thenBy { it.id }).take(limit.coerceAtLeast(0))

/** Zero for bodyweight is a storage convention, not a claim of zero exercise load. */
fun recordedSetLoadDisplay(set: SetResult, equipment: Equipment?): String =
    if (equipment == Equipment.BODYWEIGHT) "собственный вес"
    else "${set.weight} ${equipment?.let(::recordedLoadUnit) ?: "кг (единица не определена)"}"
