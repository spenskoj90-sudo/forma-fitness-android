package ru.forma.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupReplayTest {
    @Test
    fun completedWorkoutRequiresPlannedSetCountForEachExercise() {
        val first = exerciseCatalog[0]
        val second = exerciseCatalog[1]
        val day = TrainingDay(1, "План", listOf(
            Prescription(first, 1, 10),
            Prescription(first, 2, 10),
            Prescription(second, 1, 10)
        ))
        val firstSet = SetResult(first.id, 10, 0.0, 5, false, 1500)
        val secondSet = firstSet.copy(exerciseId = second.id)
        val completed = Workout("workout", day, 1000, 2000,
            listOf(firstSet, firstSet, firstSet, secondSet), Phase.DONE)
        val profile = Profile(id = "test-profile", name = "Тест")
        val backup = Backup(profiles = listOf(profile.copy(history = listOf(completed))))
        assertEquals(backup, decodeBackup(encodeBackup(backup)))

        val equalPrescriptions = completed.copy(
            day = day.copy(exercises = listOf(Prescription(first, 1, 10), Prescription(first, 1, 10))),
            sets = listOf(firstSet, firstSet)
        )
        val repeated = Backup(profiles = listOf(profile.copy(history = listOf(equalPrescriptions))))
        assertEquals(repeated, decodeBackup(encodeBackup(repeated)))

        val wrongSets = completed.copy(sets = listOf(firstSet, firstSet, firstSet, firstSet))
        val malformed = Backup(profiles = listOf(profile.copy(history = listOf(wrongSets))))
        assertThrows(IllegalArgumentException::class.java) {
            decodeBackup(Json.encodeToString(malformed))
        }
        val stopped = malformed.copy(profiles = listOf(profile.copy(
            history = listOf(wrongSets.copy(status = Phase.STOPPED))
        )))
        assertEquals(stopped, decodeBackup(encodeBackup(stopped)))
    }
}
