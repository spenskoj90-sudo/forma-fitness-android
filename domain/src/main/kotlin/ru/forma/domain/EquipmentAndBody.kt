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

/** Selected workout setting can differ from where equipment was purchased.
 * A gym may offer free weights as well as machines; combined is user-opted-in.
 */
enum class TrainingPlace(val label: String) {
    HOME("Дома"), GYM("В зале"), COMBINED("Комбинировать")
}

fun availableEquipment(profile: Profile, place: TrainingPlace): Set<Equipment> {
    val all = profile.equipment + Equipment.BODYWEIGHT
    val allowed = when (place) {
        TrainingPlace.HOME -> equipmentAt(EquipmentVenue.HOME).toSet()
        TrainingPlace.GYM -> equipmentAt(EquipmentVenue.GYM).toSet() +
            setOf(Equipment.BODYWEIGHT, Equipment.DUMBBELL, Equipment.BARBELL,
                Equipment.KETTLEBELL, Equipment.MAT, Equipment.BENCH,
                Equipment.PULL_UP_BAR)
        TrainingPlace.COMBINED -> Equipment.entries.toSet()
    }
    return all intersect allowed
}

/** Unit of the numeric weight the owner actually typed. No legacy value is converted. */
fun recordedLoadInputLabel(equipment:Equipment):String=when(equipment){
 Equipment.DUMBBELL->"Вес одной гантели, кг"
 Equipment.BARBELL->"Вес всей штанги, кг"
 Equipment.KETTLEBELL->"Вес одной гири, кг"
 Equipment.SMITH_MACHINE->"Общий вес грифа Смита, кг"
 Equipment.CABLE_MACHINE,Equipment.LAT_PULLDOWN,Equipment.LEG_PRESS_MACHINE,
 Equipment.CHEST_PRESS_MACHINE,Equipment.SEATED_ROW_MACHINE,Equipment.LEG_CURL_MACHINE->
  "Указанный вес тренажёра, кг"
 else->"Дополнительный вес, кг (если есть)"
}

/** Human-readable context for old and new recorded values, without guessing pairs. */
fun recordedLoadUnit(equipment:Equipment):String=when(equipment){
 Equipment.DUMBBELL->"кг на одну гантель"
 Equipment.BARBELL->"кг всего на штанге"
 Equipment.KETTLEBELL->"кг на одну гирю"
 Equipment.SMITH_MACHINE->"кг на грифе Смита"
 Equipment.CABLE_MACHINE,Equipment.LAT_PULLDOWN,Equipment.LEG_PRESS_MACHINE,
 Equipment.CHEST_PRESS_MACHINE,Equipment.SEATED_ROW_MACHINE,Equipment.LEG_CURL_MACHINE->
  "кг по шкале тренажёра"
 else->"кг дополнительного веса"
}

/** Numeric kg×reps is only comparable within the recorded equipment category.
 * The historical TrainingDay, not today's catalog, determines the equipment.
 * In particular, a single-dumbbell value is NOT silently doubled.
 * Body weight and elastic-band resistance are not estimated as kilograms.
 */
fun recordedLoadVolumeByEquipment(workouts:List<Workout>):Map<Equipment,Double>{
 val volumes=linkedMapOf<Equipment,Double>()
 workouts.forEach{workout->
  val equipmentById=workout.day.exercises.associate{it.exercise.id to it.exercise.equipment}
  workout.sets.forEach{set->
   val equipment=equipmentById[set.exerciseId]
   if(equipment!=null && equipment !in setOf(Equipment.BODYWEIGHT, Equipment.RESISTANCE_BAND, Equipment.PULL_UP_BAR, Equipment.MAT, Equipment.BENCH) && set.weight.isFinite() && set.weight>0 && set.reps>0){
    volumes[equipment]=(volumes[equipment]?:0.0)+set.weight*set.reps
   }
  }
 }
 return volumes
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
    // Height/weight alone do not determine an actual body shape. Until tape
    // measurements exist, only a modest neutral BMI-based visual adjustment is
    // used; the interface clearly labels the figure as schematic.
    val meters = (profile.heightCm / 100.0).takeIf { it.isFinite() && it > 0 } ?: 1.7
    val bmi = (profile.weightKg / (meters * meters)).takeIf { it.isFinite() } ?: 23.0
    val neutralFactor = (bmi / 23.0).coerceIn(0.85, 1.18)
    fun factor(value: Double?, reference: Double): Float =
        ((value ?: reference * neutralFactor) / reference).coerceIn(0.72, 1.35).toFloat()

    return BodyShape(
        shoulderFactor = factor(measured("shoulders"), 44.0),
        chestFactor = factor(measured("chest"), 100.0),
        waistFactor = factor(measured("waist"), 85.0),
        hipFactor = factor(measured("hips"), 100.0),
        hasMeasurements = listOf("shoulders", "chest", "waist", "hips").any { measured(it) != null }
    )
}
