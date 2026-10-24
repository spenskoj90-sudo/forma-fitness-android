package ru.forma.domain

/** Preserves old Equipment enum names in saved profiles and JSON backups. */
enum class EquipmentVenue(val label: String) { HOME("Дом"), GYM("Тренажёрный зал") }

/** Selecting gear in either category does not remove gear from the other. */
fun equipmentAt(venue: EquipmentVenue): List<Equipment> = when (venue) {
    EquipmentVenue.HOME -> listOf(
        Equipment.BODYWEIGHT, Equipment.DUMBBELL, Equipment.BARBELL,
        Equipment.KETTLEBELL, Equipment.RESISTANCE_BAND, Equipment.PULL_UP_BAR,
        Equipment.MAT, Equipment.BENCH
    )
    EquipmentVenue.GYM -> listOf(
        Equipment.CABLE_MACHINE, Equipment.LAT_PULLDOWN,
        Equipment.LEG_PRESS_MACHINE, Equipment.CHEST_PRESS_MACHINE,
        Equipment.SEATED_ROW_MACHINE, Equipment.LEG_CURL_MACHINE,
        Equipment.SMITH_MACHINE
    )
}

/** Anatomy labels are teaching aids, not a clinical motion analysis. */
fun secondaryMuscles(exercise: Exercise): Set<Muscle> = when (exercise.id) {
    "squat", "goblet", "chair_squat", "leg_press", "smith_squat", "kettlebell_squat" -> setOf(Muscle.CORE)
    "floor_press", "chest_press", "wall_push", "incline_push" -> setOf(Muscle.ARMS, Muscle.SHOULDERS)
    "db_row", "bb_row", "machine_row", "band_row", "lat_pulldown", "pullup_assisted" -> setOf(Muscle.ARMS)
    "db_press", "raise", "wall_slide", "band_press" -> setOf(Muscle.ARMS)
    "bridge", "db_rdl", "bb_rdl", "kettlebell_deadlift" -> setOf(Muscle.CORE)
    else -> emptySet()
}

/** Schematic proportions. Actual body contour cannot be recovered from tape
 * measurements alone; clamp values so erroneous entries cannot distort the UI.
 * All proportions default to neutral if the corresponding measurement is absent.
 */
data class BodyShape(
    val shoulderFactor: Float,
    val chestFactor: Float,
    val waistFactor: Float,
    val hipFactor: Float,
    val hasMeasurements: Boolean
)

fun bodyShape(profile: Profile): BodyShape {
    val latest = profile.measurements.sortedBy { it.dateMs }
    fun measured(key: String): Double? = latest.lastOrNull { it.values.containsKey(key) }
        ?.values?.get(key)?.takeIf { it.isFinite() && it > 0.0 }
    fun factor(value: Double?, reference: Double): Float =
        ((value ?: reference) / reference).coerceIn(0.72, 1.35).toFloat()

    return BodyShape(
        shoulderFactor = factor(measured("shoulders"), 44.0),
        chestFactor = factor(measured("chest"), 100.0),
        waistFactor = factor(measured("waist"), 85.0),
        hipFactor = factor(measured("hips"), 100.0),
        hasMeasurements = listOf("shoulders", "chest", "waist", "hips").any { measured(it) != null }
    )
}
