package ru.forma.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutDateTest {
    private val profile = Profile(id = "workout-date-fixture", name = "Fixture")
    private val day = generateProgram(profile).first()

    @Test fun aClockRollbackCannotFinishBeforeWorkoutStart() {
        val session = stopSession(startSession(profile.id, day, 10_000))
        val saved = finishSession(profile, session, 9_000)
        assertEquals(10_000L, saved.history.single().finishedMs)
        assertBackupRoundTrip(saved)
    }

    @Test fun aClockRollbackCannotFinishBeforeALoggedSet() {
        val session = logSet(
            skipStage(startSession(profile.id, day, 10_000), 10_000),
            0.0, 10, 5, false, 12_000
        )
        val stopped = stopSession(session)
        val saved = finishSession(profile, stopped, 11_000)
        assertEquals(12_000L, saved.history.single().finishedMs)
        assertBackupRoundTrip(saved)
        assertEquals(13_000L, finishSession(profile, stopped, 13_000).history.single().finishedMs)
        val multiple = stopped.copy(sets = stopped.sets + stopped.sets.single().copy(timeMs = 12_500))
        assertEquals(12_500L, finishSession(profile, multiple, 11_000).history.single().finishedMs)
    }

    private fun assertBackupRoundTrip(saved: Profile) {
        val backup = Backup(profiles = listOf(saved))
        assertEquals(backup, decodeBackup(encodeBackup(backup)))
    }
}
