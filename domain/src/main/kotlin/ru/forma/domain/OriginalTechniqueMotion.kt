package ru.forma.domain

/**
 * FORMA's own 2-D teaching sketches. No extracted video frames, motion
 * tracking, third-party characters or media clips. A sketch visualizes a
 * movement direction, not person-specific joint limits or qualified technique.
 */
data class MotionPoint(val x: Float, val y: Float)
data class MotionPose(val joints: List<MotionPoint>) {
 init { require(joints.size == 7) }
}
enum class MotionKind {
 SQUAT, GOBLET_SQUAT, SMITH_SQUAT, WALL_PUSH, BIRD_DOG, PRONE_W, CALF, HINGE_ROW, FLOOR_PRESS, LATERAL_RAISE, CURL, BRIDGE, INCLINE_PUSH, DEAD_BUG, HINGE, OVERHEAD_PRESS, WALL_SLIDE, SELF_CURL, BAND_ROW, BAND_PRESS, TRICEPS_EXTENSION, KETTLEBELL_HINGE, SCAP_PULL, PLANK, STEP_UP, LAT_PULL, LEG_PRESS, SEATED_PRESS, SEATED_ROW, LEG_CURL
}
data class OriginalMotionSpec(
 val exerciseId: String, val equipment: Equipment, val kind: MotionKind,
 val view: String = "Условный вид сбоку"
)
private fun pose(vararg p: Int): MotionPose {
 require(p.size == 14)
 return MotionPose(p.toList().chunked(2).map { MotionPoint(it[0].toFloat(), it[1].toFloat()) })
}
private val motionPairs: Map<MotionKind, Pair<MotionPose, MotionPose>> = mapOf(
 MotionKind.SQUAT to (pose(52, 22, 52, 38, 47, 57, 44, 68, 52, 69, 52, 86, 52, 105) to pose(65, 37, 60, 50, 75, 60, 77, 66, 45, 78, 75, 86, 54, 105)),
 MotionKind.GOBLET_SQUAT to (pose(52, 22, 52, 38, 58, 49, 58, 46, 52, 69, 52, 86, 52, 105) to pose(65, 37, 60, 50, 67, 57, 65, 54, 45, 78, 75, 86, 54, 105)),
 MotionKind.SMITH_SQUAT to (pose(52, 22, 52, 38, 46, 51, 46, 48, 52, 69, 52, 86, 52, 105) to pose(65, 37, 60, 50, 54, 60, 51, 54, 45, 78, 75, 86, 54, 105)),
 MotionKind.WALL_PUSH to (pose(71, 22, 66, 39, 84, 44, 99, 42, 54, 68, 46, 88, 38, 106) to pose(82, 30, 75, 47, 88, 49, 99, 42, 54, 70, 46, 88, 38, 106)),
 MotionKind.BIRD_DOG to (pose(76, 48, 59, 61, 59, 82, 60, 96, 36, 64, 35, 90, 22, 103) to pose(78, 44, 57, 59, 77, 51, 99, 47, 36, 64, 28, 75, 9, 66)),
 MotionKind.PRONE_W to (pose(24, 86, 42, 86, 52, 81, 59, 79, 65, 92, 78, 95, 94, 101) to pose(24, 82, 42, 80, 54, 71, 64, 72, 65, 88, 78, 95, 94, 101)),
 MotionKind.CALF to (pose(52, 22, 52, 38, 47, 57, 44, 68, 52, 69, 52, 86, 52, 105) to pose(52, 15, 52, 31, 47, 50, 44, 61, 52, 62, 52, 79, 52, 97)),
 MotionKind.HINGE_ROW to (pose(77, 40, 64, 48, 70, 65, 71, 87, 44, 67, 51, 86, 52, 105) to pose(77, 40, 64, 48, 62, 57, 59, 64, 44, 67, 51, 86, 52, 105)),
 MotionKind.FLOOR_PRESS to (pose(19, 88, 36, 87, 50, 72, 56, 51, 64, 90, 82, 79, 95, 105) to pose(19, 88, 36, 87, 52, 60, 57, 35, 64, 90, 82, 79, 95, 105)),
 MotionKind.LATERAL_RAISE to (pose(52, 22, 52, 38, 48, 54, 45, 70, 52, 69, 52, 86, 52, 105) to pose(52, 22, 52, 38, 72, 42, 93, 41, 52, 69, 52, 86, 52, 105)),
 MotionKind.CURL to (pose(52, 22, 52, 38, 47, 57, 44, 71, 52, 69, 52, 86, 52, 105) to pose(52, 22, 52, 38, 50, 56, 59, 46, 52, 69, 52, 86, 52, 105)),
 MotionKind.BRIDGE to (pose(22, 91, 39, 89, 48, 93, 55, 97, 62, 94, 81, 79, 95, 103) to pose(22, 91, 39, 89, 48, 87, 55, 89, 62, 69, 81, 79, 95, 103)),
 MotionKind.INCLINE_PUSH to (pose(64, 35, 57, 48, 79, 59, 99, 64, 49, 73, 42, 91, 35, 106) to pose(76, 39, 69, 53, 86, 63, 99, 64, 49, 73, 42, 91, 35, 106)),
 MotionKind.DEAD_BUG to (pose(18, 92, 36, 89, 38, 67, 43, 40, 64, 90, 68, 66, 82, 62) to pose(18, 92, 36, 89, 50, 72, 79, 45, 64, 90, 82, 79, 99, 105)),
 MotionKind.HINGE to (pose(52, 22, 52, 38, 48, 56, 46, 70, 52, 69, 52, 86, 52, 105) to pose(77, 38, 64, 46, 67, 65, 69, 86, 44, 68, 51, 87, 52, 105)),
 MotionKind.OVERHEAD_PRESS to (pose(52, 22, 52, 38, 64, 51, 66, 39, 52, 69, 52, 86, 52, 105) to pose(52, 22, 52, 38, 59, 23, 55, 9, 52, 69, 52, 86, 52, 105)),
 MotionKind.WALL_SLIDE to (pose(53, 22, 53, 38, 49, 51, 49, 44, 53, 69, 53, 86, 53, 105) to pose(53, 22, 53, 38, 49, 32, 49, 17, 53, 69, 53, 86, 53, 105)),
 MotionKind.SELF_CURL to (pose(52, 22, 52, 38, 46, 56, 47, 69, 52, 69, 52, 86, 52, 105) to pose(52, 22, 52, 38, 53, 51, 61, 44, 52, 69, 52, 86, 52, 105)),
 MotionKind.BAND_ROW to (pose(52, 22, 52, 38, 64, 52, 89, 58, 52, 69, 52, 86, 52, 105) to pose(52, 22, 52, 38, 55, 51, 60, 59, 52, 69, 52, 86, 52, 105)),
 MotionKind.BAND_PRESS to (pose(52, 22, 52, 38, 64, 53, 71, 56, 52, 69, 52, 86, 52, 105) to pose(52, 22, 52, 38, 77, 49, 101, 48, 52, 69, 52, 86, 52, 105)),
 MotionKind.TRICEPS_EXTENSION to (pose(52, 22, 52, 38, 56, 52, 66, 47, 52, 69, 52, 86, 52, 105) to pose(52, 22, 52, 38, 56, 53, 62, 75, 52, 69, 52, 86, 52, 105)),
 MotionKind.KETTLEBELL_HINGE to (pose(68, 35, 60, 45, 67, 65, 67, 80, 46, 68, 51, 86, 52, 105) to pose(52, 22, 52, 38, 47, 57, 49, 72, 52, 69, 52, 86, 52, 105)),
 MotionKind.SCAP_PULL to (pose(52, 30, 52, 44, 52, 28, 52, 13, 52, 69, 52, 86, 52, 106) to pose(52, 25, 52, 39, 52, 26, 52, 13, 52, 63, 52, 80, 52, 100)),
 MotionKind.PLANK to (pose(18, 53, 32, 61, 34, 80, 41, 94, 55, 64, 80, 78, 97, 102) to pose(18, 53, 32, 61, 34, 80, 41, 94, 55, 59, 80, 76, 97, 102)),
 MotionKind.STEP_UP to (pose(52, 22, 52, 38, 47, 57, 44, 68, 52, 69, 52, 86, 52, 105) to pose(52, 15, 52, 31, 47, 50, 44, 61, 52, 61, 75, 75, 88, 85)),
 MotionKind.LAT_PULL to (pose(52, 32, 52, 47, 65, 31, 77, 14, 52, 77, 52, 88, 52, 104) to pose(52, 32, 52, 47, 68, 49, 66, 57, 52, 77, 52, 88, 52, 104)),
 MotionKind.LEG_PRESS to (pose(20, 40, 35, 51, 43, 67, 44, 75, 50, 76, 75, 79, 96, 68) to pose(20, 40, 35, 51, 43, 67, 44, 75, 50, 76, 86, 67, 108, 52)),
 MotionKind.SEATED_PRESS to (pose(39, 29, 42, 44, 58, 56, 71, 55, 45, 76, 64, 83, 64, 104) to pose(39, 29, 42, 44, 78, 51, 102, 50, 45, 76, 64, 83, 64, 104)),
 MotionKind.SEATED_ROW to (pose(39, 29, 42, 44, 73, 55, 97, 58, 45, 76, 64, 83, 64, 104) to pose(39, 29, 42, 44, 55, 53, 63, 62, 45, 76, 64, 83, 64, 104)),
 MotionKind.LEG_CURL to (pose(39, 29, 42, 44, 48, 60, 52, 68, 45, 76, 77, 78, 103, 88) to pose(39, 29, 42, 44, 48, 60, 52, 68, 45, 76, 77, 79, 68, 101)),
)
private val motionById: Map<String, OriginalMotionSpec> = mapOf(
 "squat" to OriginalMotionSpec("squat", Equipment.BODYWEIGHT, MotionKind.SQUAT),
 "wall_push" to OriginalMotionSpec("wall_push", Equipment.BODYWEIGHT, MotionKind.WALL_PUSH),
 "bird_dog" to OriginalMotionSpec("bird_dog", Equipment.BODYWEIGHT, MotionKind.BIRD_DOG),
 "prone_w" to OriginalMotionSpec("prone_w", Equipment.BODYWEIGHT, MotionKind.PRONE_W),
 "calf" to OriginalMotionSpec("calf", Equipment.BODYWEIGHT, MotionKind.CALF),
 "goblet" to OriginalMotionSpec("goblet", Equipment.DUMBBELL, MotionKind.GOBLET_SQUAT),
 "db_row" to OriginalMotionSpec("db_row", Equipment.DUMBBELL, MotionKind.HINGE_ROW),
 "floor_press" to OriginalMotionSpec("floor_press", Equipment.DUMBBELL, MotionKind.FLOOR_PRESS),
 "raise" to OriginalMotionSpec("raise", Equipment.DUMBBELL, MotionKind.LATERAL_RAISE),
 "curl" to OriginalMotionSpec("curl", Equipment.DUMBBELL, MotionKind.CURL),
 "bb_row" to OriginalMotionSpec("bb_row", Equipment.BARBELL, MotionKind.HINGE_ROW),
 "chair_squat" to OriginalMotionSpec("chair_squat", Equipment.BODYWEIGHT, MotionKind.SQUAT),
 "bridge" to OriginalMotionSpec("bridge", Equipment.BODYWEIGHT, MotionKind.BRIDGE),
 "incline_push" to OriginalMotionSpec("incline_push", Equipment.BODYWEIGHT, MotionKind.INCLINE_PUSH),
 "dead_bug" to OriginalMotionSpec("dead_bug", Equipment.BODYWEIGHT, MotionKind.DEAD_BUG),
 "db_rdl" to OriginalMotionSpec("db_rdl", Equipment.DUMBBELL, MotionKind.HINGE),
 "hammer" to OriginalMotionSpec("hammer", Equipment.DUMBBELL, MotionKind.CURL),
 "db_press" to OriginalMotionSpec("db_press", Equipment.DUMBBELL, MotionKind.OVERHEAD_PRESS),
 "bb_rdl" to OriginalMotionSpec("bb_rdl", Equipment.BARBELL, MotionKind.HINGE),
 "bb_curl" to OriginalMotionSpec("bb_curl", Equipment.BARBELL, MotionKind.CURL),
 "wall_slide" to OriginalMotionSpec("wall_slide", Equipment.BODYWEIGHT, MotionKind.WALL_SLIDE),
 "self_curl" to OriginalMotionSpec("self_curl", Equipment.BODYWEIGHT, MotionKind.SELF_CURL),
 "band_row" to OriginalMotionSpec("band_row", Equipment.RESISTANCE_BAND, MotionKind.BAND_ROW),
 "band_press" to OriginalMotionSpec("band_press", Equipment.RESISTANCE_BAND, MotionKind.BAND_PRESS),
 "band_extension" to OriginalMotionSpec("band_extension", Equipment.RESISTANCE_BAND, MotionKind.TRICEPS_EXTENSION),
 "kettlebell_squat" to OriginalMotionSpec("kettlebell_squat", Equipment.KETTLEBELL, MotionKind.GOBLET_SQUAT),
 "kettlebell_deadlift" to OriginalMotionSpec("kettlebell_deadlift", Equipment.KETTLEBELL, MotionKind.KETTLEBELL_HINGE),
 "scap_pull" to OriginalMotionSpec("scap_pull", Equipment.PULL_UP_BAR, MotionKind.SCAP_PULL),
 "mat_plank" to OriginalMotionSpec("mat_plank", Equipment.MAT, MotionKind.PLANK),
 "bench_step" to OriginalMotionSpec("bench_step", Equipment.BENCH, MotionKind.STEP_UP),
 "cable_triceps" to OriginalMotionSpec("cable_triceps", Equipment.CABLE_MACHINE, MotionKind.TRICEPS_EXTENSION),
 "lat_pulldown" to OriginalMotionSpec("lat_pulldown", Equipment.LAT_PULLDOWN, MotionKind.LAT_PULL),
 "leg_press" to OriginalMotionSpec("leg_press", Equipment.LEG_PRESS_MACHINE, MotionKind.LEG_PRESS),
 "chest_press" to OriginalMotionSpec("chest_press", Equipment.CHEST_PRESS_MACHINE, MotionKind.SEATED_PRESS),
 "machine_row" to OriginalMotionSpec("machine_row", Equipment.SEATED_ROW_MACHINE, MotionKind.SEATED_ROW),
 "leg_curl" to OriginalMotionSpec("leg_curl", Equipment.LEG_CURL_MACHINE, MotionKind.LEG_CURL),
 "smith_squat" to OriginalMotionSpec("smith_squat", Equipment.SMITH_MACHINE, MotionKind.SMITH_SQUAT),
)
/** Exact exercise and equipment allowlist, never a substitute for reviewed media. */
fun originalMotionFor(exercise: Exercise): OriginalMotionSpec? =
 motionById[exercise.id]?.takeIf { spec ->
  spec.equipment == exercise.equipment &&
   exerciseCatalog.any { it.id == exercise.id && it.equipment == spec.equipment }
 }

/** Deterministic, bounded interpolation for scrubbing and paused previews. */
fun originalMotionAt(spec: OriginalMotionSpec, fraction: Float): MotionPose {
 require(fraction.isFinite()) { "Invalid motion time" }
 val pair = motionPairs.getValue(spec.kind)
 val t = fraction.coerceIn(0f, 1f)
 val coords = pair.first.joints.zip(pair.second.joints) { a, b ->
  MotionPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
 }
 return MotionPose(coords)
}

/** Inventory is tied to all exact catalog IDs and remains unreviewed until a coach signs off. */
fun originalMotionCoverage(): Set<String> = motionById.keys
