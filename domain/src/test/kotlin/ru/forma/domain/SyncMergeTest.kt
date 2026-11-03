package ru.forma.domain

import org.junit.Assert.*
import org.junit.Test

class SyncMergeTest {
    private val owner = SyncScope(accountId = "account-1", profileId = "profile-1")
    private fun workout(id: String, finished: Long) = Workout(
        id = id,
        day = TrainingDay(day = 1, name = "Кардио", cardio = CardioKind.WALK),
        startedMs = 10L,
        finishedMs = finished,
        sets = emptyList(),
        status = Phase.DONE
    )
    private fun measurement(id: String, weight: Double) = Measurement(
        id = id,
        dateMs = 1000L,
        values = mapOf("weight" to weight)
    )

    @Test fun identicalRetriesNeverDuplicateWorkoutsOrMeasurements() {
        val a = workout("workout-a", 100)
        val m = measurement("measurement-a", 75.0)
        val result = planProfileSync(owner, owner, listOf(a), listOf(a), listOf(m), listOf(m))
        assertEquals(listOf(a), result.workouts.records)
        assertEquals(listOf(m), result.measurements.records)
        assertFalse(result.hasConflicts)
    }

    @Test fun offlineRecordsOnTwoDevicesMergeWithoutLoss() {
        val left = workout("infinix", 100)
        val right = workout("poco", 200)
        val result = planProfileSync(
            owner, owner,
            listOf(left), listOf(right),
            listOf(measurement("m1", 75.0)), listOf(measurement("m2", 74.0))
        )
        assertEquals(setOf("infinix", "poco"), result.workouts.records.map { it.id }.toSet())
        assertEquals(setOf("m1", "m2"), result.measurements.records.map { it.id }.toSet())
        assertFalse(result.hasConflicts)
    }

    @Test fun conflictingEditsAreVisibleAndNeitherIsSilentlyApplied() {
        val local = measurement("shared", 75.0)
        val remote = measurement("shared", 70.0)
        val result = planProfileSync(owner, owner, emptyList(), emptyList(), listOf(local), listOf(remote))
        assertTrue(result.hasConflicts)
        assertEquals(listOf(local), result.measurements.records)
        assertEquals("shared", result.measurements.conflicts.single().id)
        assertEquals(local, result.measurements.conflicts.single().local)
        assertEquals(remote, result.measurements.conflicts.single().remote)
    }

    @Test fun changingWorkoutWithSameIdIsConflictNotOverwrite() {
        val local = workout("w", 100L)
        val remote = workout("w", 120L)
        val result = planProfileSync(owner, owner, listOf(local), listOf(remote), emptyList(), emptyList())
        assertEquals(listOf(local), result.workouts.records)
        assertEquals(1, result.workouts.conflicts.size)
    }

    @Test fun differentAccountsOrProfilesCannotMerge() {
        assertThrows(IllegalArgumentException::class.java) {
            planProfileSync(owner, SyncScope("other-account", "profile-1"), emptyList(), emptyList(), emptyList(), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            planProfileSync(owner, SyncScope("account-1", "other-profile"), emptyList(), emptyList(), emptyList(), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            planProfileSync(SyncScope("", "profile-1"), owner, emptyList(), emptyList(), emptyList(), emptyList())
        }
    }

    @Test fun malformedDuplicateSnapshotsFailClosed() {
        val a = workout("dup", 100)
        assertThrows(IllegalArgumentException::class.java) {
            planProfileSync(owner, owner, listOf(a, a), emptyList(), emptyList(), emptyList())
        }
    }
}
