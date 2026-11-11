package ru.forma.domain

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Original offline volumetric rig data for FORMA. These are authored, simplified
 * motion prototypes, never a clinical motion capture or a reviewed technique.
 * 2-D authored timing is mapped into a bilateral 3-D rig; depth is real, not
 * a painted shadow. A qualified reviewer must approve each exact gear variant.
 */
data class VolumePoint(val x: Float, val y: Float, val z: Float) {
 init { require(x.isFinite() && y.isFinite() && z.isFinite()) }
 operator fun plus(v: VolumePoint) = VolumePoint(x + v.x, y + v.y, z + v.z)
 operator fun minus(v: VolumePoint) = VolumePoint(x - v.x, y - v.y, z - v.z)
 operator fun times(t: Float) = VolumePoint(x * t, y * t, z * t)
 fun length(): Float = sqrt(x * x + y * y + z * z)
 fun cross(v: VolumePoint) = VolumePoint(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x)
 fun unit(): VolumePoint = this * (1f / length().coerceAtLeast(0.000001f))
}

enum class VolumeTone { TORSO, ACTIVE, EQUIPMENT, SUPPORT }
data class VolumeLink(val a: VolumePoint, val b: VolumePoint, val radius: Float, val tone: VolumeTone) {
 init { require(radius.isFinite() && radius > 0.01f && radius < 2f && (b - a).length() > 0.01f) }
}
data class VolumeJoint(val position: VolumePoint, val radius: Float, val tone: VolumeTone) {
 init { require(radius.isFinite() && radius > 0f && radius < 2f) }
}
data class OriginalVolumeScene(
 val exerciseId: String,
 val equipment: Equipment,
 val links: List<VolumeLink>,
 val joints: List<VolumeJoint>,
 val phase: Float
) {
 init { require(phase.isFinite() && phase in 0f..1f && links.isNotEmpty() && joints.isNotEmpty()) }
}

/**
 * Camera-independent body segments, both shoulders/arms/legs and gear.
 * Uses exact-ID/equipment allowlisting from the existing domain catalog.
 * Never use this visualization to infer an individual's safe joint range.
 */
fun originalVolumeAt(exercise: Exercise, fraction: Float): OriginalVolumeScene? {
 require(fraction.isFinite())
 val spec = originalMotionFor(exercise) ?: return null
 val t = fraction.coerceIn(0f, 1f)
 val eased = t * t * (3f - 2f * t)
 val primary = originalMotionAt(spec, eased).joints
 val alternate = if(exercise.id in setOf("bird_dog", "dead_bug", "bench_step", "self_curl"))
  originalMotionAt(spec, 1f - eased).joints else primary
 fun p(j: MotionPoint, depth: Float): VolumePoint =
  VolumePoint((j.x - 60f) * 0.092f, (109f - j.y) * 0.092f, depth)
 fun link(a: VolumePoint, b: VolumePoint, r: Float, tone: VolumeTone = VolumeTone.TORSO) =
  VolumeLink(a, b, r, tone)
 val head = p(primary[0], 0f)
 val shoulder = p(primary[1], 0f)
 val hip = p(primary[4], 0f)
 val leftShoulder = p(primary[1], 0.48f)
 val rightShoulder = p(alternate[1], -0.48f)
 val leftElbow = p(primary[2], 0.67f)
 val rightElbow = p(alternate[2], -0.67f)
 val leftWrist = p(primary[3], 0.67f)
 val rightWrist = p(alternate[3], -0.67f)
 val leftHip = p(primary[4], 0.31f)
 val rightHip = p(alternate[4], -0.31f)
 val leftKnee = p(primary[5], 0.39f)
 val rightKnee = p(alternate[5], -0.39f)
 val leftAnkle = p(primary[6], 0.39f)
 val rightAnkle = p(alternate[6], -0.39f)
 val body = mutableListOf(
  link(head, shoulder, 0.18f),
  link(shoulder, hip, 0.39f),
  link(leftShoulder, leftElbow, 0.20f, VolumeTone.ACTIVE),
  link(leftElbow, leftWrist, 0.15f, VolumeTone.ACTIVE),
  link(rightShoulder, rightElbow, 0.20f, VolumeTone.ACTIVE),
  link(rightElbow, rightWrist, 0.15f, VolumeTone.ACTIVE),
  link(leftHip, leftKnee, 0.27f),
  link(leftKnee, leftAnkle, 0.20f),
  link(rightHip, rightKnee, 0.27f),
  link(rightKnee, rightAnkle, 0.20f)
 )
 val joints = mutableListOf(
  VolumeJoint(head, 0.50f, VolumeTone.TORSO),
  VolumeJoint(shoulder, 0.43f, VolumeTone.TORSO),
  VolumeJoint(hip, 0.44f, VolumeTone.TORSO),
  VolumeJoint(leftElbow, 0.22f, VolumeTone.ACTIVE),
  VolumeJoint(rightElbow, 0.22f, VolumeTone.ACTIVE),
  VolumeJoint(leftKnee, 0.28f, VolumeTone.TORSO),
  VolumeJoint(rightKnee, 0.28f, VolumeTone.TORSO)
 )
 fun gear(a: VolumePoint, b: VolumePoint, r: Float = 0.10f) { body += link(a, b, r, VolumeTone.EQUIPMENT) }
 fun support(a: VolumePoint, b: VolumePoint, r: Float = 0.12f) { body += link(a, b, r, VolumeTone.SUPPORT) }
 fun x(x: Float, y: Float, z: Float) = VolumePoint(x, y, z)
 val handMid = (leftWrist + rightWrist) * 0.5f
 when(exercise.equipment) {
  Equipment.DUMBBELL -> listOf(leftWrist, rightWrist).forEach { wrist ->
   gear(wrist + x(0f, 0f, -0.36f), wrist + x(0f, 0f, 0.36f), 0.13f)
   joints += VolumeJoint(wrist + x(0f, 0f, -0.36f), 0.22f, VolumeTone.EQUIPMENT)
   joints += VolumeJoint(wrist + x(0f, 0f, 0.36f), 0.22f, VolumeTone.EQUIPMENT)
  }
  Equipment.BARBELL -> {
   gear(handMid + x(0f,0f,-1.75f),handMid + x(0f,0f,1.75f),0.095f)
   listOf(-1.45f,1.45f).forEach { z -> joints += VolumeJoint(handMid + x(0f,0f,z),0.24f,VolumeTone.EQUIPMENT) }
  }
  Equipment.KETTLEBELL -> {
   joints += VolumeJoint(handMid+x(0f,-0.3f,0f),0.35f,VolumeTone.EQUIPMENT)
   gear(handMid,handMid+x(0f,-0.32f,0f),0.10f)
  }
  Equipment.SMITH_MACHINE -> {
   gear(shoulder+x(0f,0f,-1.7f), shoulder+x(0f,0f,1.7f),0.12f)
   for(z in listOf(-1.7f,1.7f))support(x(shoulder.x,0f,z),x(shoulder.x,11f,z),0.13f)
  }
  Equipment.RESISTANCE_BAND -> {
   val anchorX = if(spec.kind == MotionKind.BAND_ROW) 4.1f else -4.1f
   val anchor=x(anchorX,5f,0f)
   gear(leftWrist,anchor,0.035f)
   gear(rightWrist,anchor,0.035f)
  }
  Equipment.PULL_UP_BAR -> support(x(-2f,10.4f,-1.8f),x(-2f,10.4f,1.8f),0.17f)
  Equipment.BENCH -> {
   support(x(-2.3f,2.0f,0f),x(2.3f,2.0f,0f),0.3f)
   support(x(-2.0f,0f,0f),x(-2.0f,2f,0f))
   support(x(2.0f,0f,0f),x(2.0f,2f,0f))
  }
  Equipment.MAT -> {
   support(x(-4.6f,0f,-1.8f),x(4.6f,0f,-1.8f),0.06f)
   support(x(-4.6f,0f,1.8f),x(4.6f,0f,1.8f),0.06f)
  }
  Equipment.CABLE_MACHINE, Equipment.LAT_PULLDOWN, Equipment.LEG_PRESS_MACHINE,
  Equipment.LEG_CURL_MACHINE, Equipment.SEATED_ROW_MACHINE, Equipment.CHEST_PRESS_MACHINE -> {
   support(x(4.4f,0f,-1.5f),x(4.4f,10.7f,-1.5f))
   support(x(4.4f,0f,1.5f),x(4.4f,10.7f,1.5f))
   support(x(4.4f,9.8f,-1.5f),x(4.4f,9.8f,1.5f))
   if(exercise.equipment == Equipment.LAT_PULLDOWN)
    gear(x(-0.6f,9.7f,-1.1f),x(-0.6f,9.7f,1.1f))
   if(exercise.equipment == Equipment.LEG_PRESS_MACHINE) {
    val foot = (leftAnkle + rightAnkle) * 0.5f
    support(foot + x(0.55f,-0.7f,-1.0f),foot + x(0.55f,0.7f,-1.0f),0.23f)
    support(foot + x(0.55f,-0.7f,1.0f),foot + x(0.55f,0.7f,1.0f),0.23f)
   }
   if(exercise.equipment == Equipment.CHEST_PRESS_MACHINE || exercise.equipment == Equipment.SEATED_ROW_MACHINE || exercise.equipment == Equipment.CABLE_MACHINE)
    gear(handMid, x(4.4f,handMid.y,0f),0.08f)
   if(exercise.equipment == Equipment.LEG_CURL_MACHINE)
    gear(leftAnkle+x(0f,-0.1f,-0.2f),rightAnkle+x(0f,-0.1f,0.2f),0.25f)
  }
  Equipment.BODYWEIGHT -> when(exercise.id) {
   "wall_push" -> support(x(4.2f,0f,-2f),x(4.2f,10.7f,-2f))
   "incline_push" -> {
    support(x(2.8f,5f,-1f),x(2.8f,5f,1f))
    support(x(2.8f,0f,-1f),x(2.8f,5f,-1f))
   }
   "chair_squat" -> {
    support(x(-2.1f,2.4f,-1f),x(-2.1f,2.4f,1f))
    support(x(-2.1f,0f,-1f),x(-2.1f,2.4f,-1f))
   }
   else -> Unit
  }
 }
 // A rig is a clearly marked draft until variant-specific independent review.
 return OriginalVolumeScene(exercise.id,exercise.equipment,body,joints,t)
}

fun originalVolumeCoverage(): Set<String> = originalMotionCoverage()
