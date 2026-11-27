package ru.forma.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.forma.domain.Profile
import ru.forma.domain.generateProgram
import ru.forma.domain.logSet
import ru.forma.domain.skipStage
import ru.forma.domain.startSession

class FirestoreSyncPayloadTest {
    private val json = Json { encodeDefaults = true }

    @Test fun roomWorkoutSurvivesNestedFirestoreFieldReordering() {
        val profile = Profile(id = "synthetic", name = "Example")
        val day = generateProgram(profile).first()
        val work = skipStage(startSession(profile.id, day, 1_000L), 1_000L)
        val set = logSet(work, 0.0, 10, 6, false, 2_000L).sets.single()
        val workout = CloudWorkoutPayload(day, 1_000L, 3_000L, listOf(set), "STOPPED", 2, false)
        val snapshot = json.encodeToString(workout)
        val wire = FirestoreSyncPayload.document("workout", snapshot)
        val reordered = wire.toSortedMap(reverseOrder()).apply {
            this["day"] = (getValue("day") as Map<*, *>).entries.reversed()
                .associate { (key, value) -> key to value }
        }
        assertEquals(snapshot, FirestoreSyncPayload.snapshot("workout", reordered))
        assertEquals(1_000L, wire["startedMs"])
        assertEquals(false, wire["painReported"])
    }

    @Test fun roomMeasurementRetainsEpochZeroAndFractionalValues() {
        val snapshot = json.encodeToString(CloudMeasurementPayload(0L, sortedMapOf("waist" to 0.005, "weight" to 75.0)))
        val wire = mapOf("values" to mapOf("weight" to 75L, "waist" to 0.005), "dateMs" to 0L)
        assertEquals(snapshot, FirestoreSyncPayload.snapshot("measurement", wire))
    }

    @Test fun missingAndUnexpectedRemoteFieldsCannotBecomeAnAcknowledgement() {
        val profile = Profile(id = "synthetic", name = "Example")
        val day = generateProgram(profile).first()
        val snapshot = json.encodeToString(CloudWorkoutPayload(day, 1L, 2L, emptyList(), "STOPPED", 0, false))
        val wire = FirestoreSyncPayload.document("workout", snapshot)
        val incomplete = (wire["day"] as Map<*, *>).toMutableMap().apply { remove("explanation") }
        assertThrows(IllegalArgumentException::class.java) {
            FirestoreSyncPayload.snapshot("workout", wire + ("day" to incomplete))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FirestoreSyncPayload.snapshot("workout", wire + ("otherAccount" to "synthetic"))
        }
    }

    @Test fun nullableProfileFieldStaysPresentAndCoercedNumbersAreRejected() {
        val profile = CloudProfilePayload("Example", 30, 178.0, 75.0, "BEGINNER", listOf("MAT"),
            "", "FITNESS", null, emptyList(), listOf(1, 3), 4)
        val snapshot = json.encodeToString(profile)
        val wire = FirestoreSyncPayload.document("profile", snapshot)
        assertTrue(wire.containsKey("secondary"))
        assertEquals(null, wire["secondary"])
        assertEquals(snapshot, FirestoreSyncPayload.snapshot("profile", wire))
        assertThrows(IllegalArgumentException::class.java) {
            FirestoreSyncPayload.snapshot("profile", wire + ("age" to "30"))
        }
    }
}
