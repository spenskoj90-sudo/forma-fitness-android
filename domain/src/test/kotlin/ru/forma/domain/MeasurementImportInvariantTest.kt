package ru.forma.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MeasurementImportInvariantTest {
    @Test fun journalRejectsDatesThatBackupCannotRestore() {
        val profile = Profile(name = "Test")
        val invalid = Measurement(id = "draft", dateMs = -1L, values = mapOf("weight" to 75.0))
        assertThrows(IllegalArgumentException::class.java) { profile.withMeasurement(invalid) }
        val valid = invalid.copy(dateMs = 0L)
        val saved = profile.withMeasurement(valid)
        assertEquals(listOf(valid), saved.measurements)
        assertEquals(Backup(profiles = listOf(saved)), decodeBackup(encodeBackup(Backup(profiles = listOf(saved)))))
    }
}
