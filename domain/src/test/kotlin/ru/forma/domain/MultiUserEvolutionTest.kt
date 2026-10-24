package ru.forma.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MultiUserEvolutionTest {
    @Test fun previousEquipmentNamesSurviveJsonRoundTrip() {
        val previous = Profile(name = "Existing", equipment =
            setOf(Equipment.BODYWEIGHT, Equipment.DUMBBELL, Equipment.BARBELL))
        val restored = Json.decodeFromString<Profile>(Json.encodeToString(previous))
        assertEquals(previous.equipment, restored.equipment)
    }

    @Test fun homeGymEquipmentMayBeMixedWithoutLosingProfileHistory() {
        val mixed = Profile(name = "Mixed", equipment = setOf(
            Equipment.BODYWEIGHT, Equipment.DUMBBELL,
            Equipment.LEG_PRESS_MACHINE, Equipment.SEATED_ROW_MACHINE
        ))
        val plan = generateProgram(mixed).flatMap { it.exercises }
        assertTrue(plan.isNotEmpty())
        assertTrue(plan.all { it.exercise.equipment in mixed.equipment })
        assertTrue(plan.any { it.exercise.equipment == Equipment.LEG_PRESS_MACHINE })
        assertTrue(plan.any { it.exercise.equipment == Equipment.SEATED_ROW_MACHINE })
        assertEquals(8, equipmentAt(EquipmentVenue.HOME).size)
        assertEquals(7, equipmentAt(EquipmentVenue.GYM).size)
        assertEquals(Equipment.entries.size,
            (equipmentAt(EquipmentVenue.HOME) + equipmentAt(EquipmentVenue.GYM)).distinct().size)
    }

    @Test fun firstProfileDoesNotAssumeOwnedBarbellAndGetsCompleteMuscleCoverage() {
        val profile = Profile(name = "Beginner")
        assertEquals(setOf(Equipment.BODYWEIGHT), profile.equipment)
        val exercises = generateProgram(profile).first().exercises
        assertTrue(exercises.all { it.exercise.equipment == Equipment.BODYWEIGHT })
        assertEquals(Muscle.entries.toSet(), exercises.map { it.exercise.muscle }.toSet())
    }

    @Test fun homeAndGymPlansNeverSelectUnavailableEquipment() {
        val p = Profile(name = "Mixed", equipment = setOf(
            Equipment.BODYWEIGHT, Equipment.DUMBBELL, Equipment.BARBELL,
            Equipment.LEG_PRESS_MACHINE, Equipment.SEATED_ROW_MACHINE
        ))
        val home = generateProgram(p, place = TrainingPlace.HOME)
        val gym = generateProgram(p, place = TrainingPlace.GYM)
        val mixed = generateProgram(p, place = TrainingPlace.COMBINED)
        val homeGear = availableEquipment(p, TrainingPlace.HOME)
        val gymGear = availableEquipment(p, TrainingPlace.GYM)
        assertFalse(Equipment.LEG_PRESS_MACHINE in homeGear)
        assertTrue(Equipment.LEG_PRESS_MACHINE in gymGear)
        assertTrue(home.flatMap { it.exercises }.all { it.exercise.equipment in homeGear })
        assertTrue(gym.flatMap { it.exercises }.all { it.exercise.equipment in gymGear })
        assertTrue(mixed.flatMap { it.exercises }.all { it.exercise.equipment in p.equipment })
        // Combined mode makes all chosen equipment eligible; it need not
        // prescribe every machine when free weights are also available.
        assertTrue(Equipment.LEG_PRESS_MACHINE in availableEquipment(p, TrainingPlace.COMBINED))
        val machineOnly = p.copy(equipment = setOf(
            Equipment.LEG_PRESS_MACHINE, Equipment.SEATED_ROW_MACHINE
        ))
        val gymMachinePlan = generateProgram(machineOnly, place = TrainingPlace.GYM)
        assertTrue(gymMachinePlan.flatMap { it.exercises }.any {
            it.exercise.equipment == Equipment.LEG_PRESS_MACHINE
        })
        assertTrue(gymMachinePlan.flatMap { it.exercises }.any {
            it.exercise.equipment == Equipment.SEATED_ROW_MACHINE
        })
        assertFalse(home.flatMap { it.exercises }.any {
            it.exercise.equipment == Equipment.LEG_PRESS_MACHINE
        })
    }

    @Test fun homeTrainingStillWorksForGymOnlyProfiles() {
        val p = Profile(name = "Gym", equipment = setOf(
            Equipment.LEG_PRESS_MACHINE, Equipment.LAT_PULLDOWN
        ))
        val home = generateProgram(p, place = TrainingPlace.HOME).flatMap { it.exercises }
        assertTrue(home.isNotEmpty())
        assertTrue(home.all { it.exercise.equipment == Equipment.BODYWEIGHT })
    }

    @Test fun addedTrainingPlacesDoNotChangeLegacySavedWorkoutEquipment() {
        val legacy = Profile(name = "Legacy", equipment = setOf(
            Equipment.BODYWEIGHT, Equipment.DUMBBELL, Equipment.BARBELL
        ))
        val migrated = Json.decodeFromString<Profile>(Json.encodeToString(legacy))
        assertEquals(legacy.equipment, migrated.equipment)
        assertEquals(TrainingPlace.HOME, TrainingPlace.valueOf("HOME"))
    }

    @Test fun mannequinUsesLatestMeasurementsAndClampsOutliers() {
        val base = Profile(name = "Demo", heightCm = 178.0, weightKg = 75.0)
        val original = bodyShape(base)
        assertFalse(original.hasMeasurements)
        val withMeasurements = base.copy(measurements = listOf(
            Measurement(id = "a", dateMs = 100, values = mapOf("waist" to 88.0)),
            Measurement(id = "b", dateMs = 200, values = mapOf("waist" to 110.0, "chest" to 150.0))
        ))
        val modified = bodyShape(withMeasurements)
        assertTrue(modified.hasMeasurements)
        assertTrue(modified.waistFactor > original.waistFactor)
        assertEquals(1.35f, modified.chestFactor, 0.01f)
    }

    @Test fun backupsSupportMoreThanTwoIndependentProfiles() {
        val profiles = (1..25).map { i -> Profile(id = "local-$i", name = "User $i") }
        val restored = decodeBackup(encodeBackup(Backup(profiles = profiles)))
        assertEquals(25, restored.profiles.size)
        assertEquals(25, restored.profiles.map { it.id }.distinct().size)
        assertEquals(100, MAX_BACKUP_PROFILES)
    }
}
