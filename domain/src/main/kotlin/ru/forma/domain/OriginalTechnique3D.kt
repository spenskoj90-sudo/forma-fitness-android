package ru.forma.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
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
 val contralateral = spec.kind == MotionKind.BIRD_DOG || spec.kind == MotionKind.DEAD_BUG
 // Finish the first reach before starting the opposite reach. The central
 // frame is the supported starting pose, with both sides returned.
 val primary = originalMotionAt(spec, if(contralateral) (2f*eased-1f).coerceAtLeast(0f) else eased).joints
 val alternate = if(contralateral) originalMotionAt(spec,(1f-2f*eased).coerceAtLeast(0f)).joints else primary
 val trunk = if(contralateral) originalMotionAt(spec,0f).joints else primary
 fun p(j: MotionPoint, depth: Float): VolumePoint =
  VolumePoint((j.x - 60f) * 0.092f, (109f - j.y) * 0.092f, depth)
 fun link(a: VolumePoint, b: VolumePoint, r: Float, tone: VolumeTone = VolumeTone.TORSO) =
  VolumeLink(a, b, r, tone)
 val head = p(trunk[0], 0f)
 val shoulder = p(trunk[1], 0f)
 val hip = p(trunk[4], 0f)
 val leftShoulder = p(trunk[1], 0.48f)
 val rightShoulder = p(trunk[1], -0.48f)
 // The exact lateral-raise variant must abduct outwards in actual Z depth.
 // A side-view-only motion would merely swing both arms forward, so use
 // a hand-authored bilateral depth path while keeping the catalog torso.
 fun lateralPoint(shoulder:VolumePoint,side:Float,wrist:Boolean):VolumePoint {
  val rest=if(wrist)0.92f else 0.72f
  val extended=if(wrist)2.60f else 1.55f
  val drop=if(wrist)2.35f else 1.18f
  val forward=if(wrist)0.14f else 0.08f
  val elbowBend=if(wrist)0.12f else 0.08f
  return VolumePoint(shoulder.x+forward,
   shoulder.y-drop*(1f-eased)-elbowBend*eased,
   side*(rest+(extended-rest)*eased))
 }
 // In the wall-slide rig the upper arms start out to the sides and the
 // forearms start upright. Rotate fixed-length segments in the wall plane.
 fun wallSlideElbow(shoulder:VolumePoint,side:Float):VolumePoint {
  val rotation=Math.toRadians(70.0*eased)
  return VolumePoint(p(primary[2],0f).x,
   shoulder.y+1.25f*sin(rotation).toFloat(),shoulder.z+side*1.25f*cos(rotation).toFloat())
 }
 fun wallSlideWrist(elbow:VolumePoint,side:Float):VolumePoint {
  val rotation=Math.toRadians(20.0*eased)
  return VolumePoint(elbow.x,
   elbow.y+1.19f*cos(rotation).toFloat(),elbow.z+side*1.19f*sin(rotation).toFloat())
 }
 val armDepth = when(spec.kind) {
  MotionKind.FLOOR_PRESS -> 1.47f-0.8f*eased
  MotionKind.PRONE_W -> 1.45f
  else -> 0.67f
 }
 val gripDepth = when {
  spec.kind == MotionKind.GOBLET_SQUAT -> 0.16f
  spec.kind == MotionKind.PRONE_W -> 1.10f
  exercise.equipment == Equipment.KETTLEBELL -> 0.24f
  else -> armDepth
 }
 val leftElbow = when(spec.kind) {
  MotionKind.LATERAL_RAISE -> lateralPoint(leftShoulder,1f,false)
  MotionKind.WALL_SLIDE -> wallSlideElbow(leftShoulder,1f)
  else -> p(primary[2],armDepth)
 }
 val rightElbow = when(spec.kind) {
  MotionKind.LATERAL_RAISE -> lateralPoint(rightShoulder,-1f,false)
  MotionKind.WALL_SLIDE -> wallSlideElbow(rightShoulder,-1f)
  MotionKind.SELF_CURL -> p(MotionPoint(59f,56f),-0.40f)
  else -> p(alternate[2],-armDepth)
 }
 val leftWrist = when(spec.kind) {
  MotionKind.LATERAL_RAISE -> lateralPoint(leftShoulder,1f,true)
  MotionKind.WALL_SLIDE -> wallSlideWrist(leftElbow,1f)
  else -> p(primary[3],gripDepth)
 }
 val rightWrist = when(spec.kind) {
  MotionKind.LATERAL_RAISE -> lateralPoint(rightShoulder,-1f,true)
  MotionKind.WALL_SLIDE -> wallSlideWrist(rightElbow,-1f)
  // The assisting hand follows the working forearm, close to its wrist.
  MotionKind.SELF_CURL -> leftElbow+(leftWrist-leftElbow)*0.85f+VolumePoint(0f,0.13f,0f)
  else -> p(alternate[3],-gripDepth)
 }
 // Bird-dog and dead-bug require contralateral reach, not same-side
 // arm+leg extension. Only these exact-ID leg phase sources are crossed.
 val leftLegPose = if(contralateral) alternate else primary
 val rightLegPose = if(contralateral) primary else alternate
 val leftHip = p(leftLegPose[4], 0.31f)
 val rightHip = p(rightLegPose[4], -0.31f)
 val leftKnee = p(leftLegPose[5], 0.39f)
 val rightKnee = if(spec.kind == MotionKind.STEP_UP)
  p(MotionPoint(52f+28f*eased,91f-17f*eased),-0.39f) else p(rightLegPose[5],-0.39f)
 val leftAnkle = p(leftLegPose[6], 0.39f)
 val rightAnkle = if(spec.kind == MotionKind.STEP_UP) {
  // Lift the trailing foot above the leading edge before advancing over it.
  val lift=(eased/0.56f).coerceAtMost(1f)
  val forward=((eased-0.56f)/0.44f).coerceAtLeast(0f)
  p(MotionPoint(48f+34f*forward,107f-20f*lift),-0.39f)
 } else p(rightLegPose[6],-0.39f)
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
 fun seatedSupport() {
  // The pelvis rests on a fixed seat. The back rail follows the nominal seat angle.
  for(z in listOf(-0.8f,0.8f)) {
   val rear=hip+x(-0.5f,-0.47f,z)
   support(rear,hip+x(0.95f,-0.47f,z),0.13f)
   support(rear,shoulder+x(-0.47f,0.35f,z),0.13f)
   support(x(rear.x,0f,z),rear)
  }
  val seat=hip+x(0.15f,-0.47f,0f)
  support(seat+x(0f,0f,-0.8f),seat+x(0f,0f,0.8f),0.13f)
 }
 fun transverseGrip(halfWidth:Float) {
  gear(handMid+x(0f,0f,-halfWidth),handMid+x(0f,0f,halfWidth))
 }
 when(exercise.equipment) {
  Equipment.DUMBBELL -> if(exercise.id=="goblet") {
   // One vertical goblet dumbbell is shared by both hands at chest height.
   // Other exact-ID dumbbell exercises retain independent left/right weights.
   val bottom=handMid+x(0f,-0.3f,0f)
   val top=handMid+x(0f,0.3f,0f)
   gear(bottom,top,0.13f)
   joints += VolumeJoint(bottom,0.22f,VolumeTone.EQUIPMENT)
   joints += VolumeJoint(top,0.22f,VolumeTone.EQUIPMENT)
  } else listOf(leftWrist, rightWrist).forEach { wrist ->
   // Side-raised arms extend along Z; the dumbbell shaft crosses them in X.
   val halfBar=if(exercise.id=="raise") x(0.36f,0f,0f) else x(0f,0f,0.36f)
   gear(wrist-halfBar, wrist+halfBar, 0.13f)
   joints += VolumeJoint(wrist-halfBar, 0.22f, VolumeTone.EQUIPMENT)
   joints += VolumeJoint(wrist+halfBar, 0.22f, VolumeTone.EQUIPMENT)
  }
  Equipment.BARBELL -> {
   gear(handMid + x(0f,0f,-1.75f),handMid + x(0f,0f,1.75f),0.095f)
   listOf(-1.45f,1.45f).forEach { z -> joints += VolumeJoint(handMid + x(0f,0f,z),0.24f,VolumeTone.EQUIPMENT) }
  }
  Equipment.KETTLEBELL -> {
   joints += VolumeJoint(handMid+x(0f,-0.3f,0f),0.35f,VolumeTone.EQUIPMENT)
   gear(handMid,handMid+x(0f,-0.32f,0f),0.10f)
   transverseGrip(0.28f)
   if(spec.kind == MotionKind.KETTLEBELL_HINGE) {
    val startGrip=p(originalMotionAt(spec,0f).joints[3],0f)
    val topY=startGrip.y-0.77f
    for(z in listOf(-0.57f,0.57f)) {
     support(x(startGrip.x-0.62f,topY,z),x(startGrip.x+0.62f,topY,z))
     support(x(startGrip.x-0.62f,0f,z),x(startGrip.x-0.62f,topY,z))
     support(x(startGrip.x+0.62f,0f,z),x(startGrip.x+0.62f,topY,z))
    }
    support(x(startGrip.x,topY,-0.57f),x(startGrip.x,topY,0.57f))
   }
  }
  Equipment.SMITH_MACHINE -> {
   // This authored scene depicts a VERTICAL Smith guide, not every angled
   // machine variant. Its frame and bar X must never follow the athlete.
   val railX=p(originalMotionAt(spec,0f).joints[1],0f).x
   gear(x(railX,shoulder.y,-1.7f),x(railX,shoulder.y,1.7f),0.12f)
   for(z in listOf(-1.7f,1.7f))
    support(x(railX,0f,z),x(railX,11f,z),0.13f)
  }
  Equipment.RESISTANCE_BAND -> {
   val anchorX = if(spec.kind == MotionKind.BAND_PRESS) -4.1f else 4.1f
   val anchor=x(anchorX,if(spec.kind == MotionKind.TRICEPS_EXTENSION) 9.2f else 5f,0f)
   gear(leftWrist,anchor,0.035f)
   gear(rightWrist,anchor,0.035f)
   support(x(anchorX,0f,0f),anchor)
  }
  Equipment.PULL_UP_BAR -> {
   // The catalog's scapular pull keeps the hands on a stationary bar.
   // Align the crossbar with both wrist contacts, not above/behind them.
   support(x(handMid.x,handMid.y,-1.8f),x(handMid.x,handMid.y,1.8f),0.17f)
   val footX=leftAnkle.x
   for(z in listOf(-0.81f,0.81f)) support(x(footX-0.5f,0.15f,z),x(footX+0.5f,0.15f,z))
   for(px in listOf(footX-0.5f,footX+0.5f)) support(x(px,0.15f,-0.81f),x(px,0.15f,0.81f))
  }
  Equipment.BENCH -> {
   // Low stationary platform: both alternating raised feet in the authored
   // bench_step path must land within its top surface, not past a thin rail.
   // A wireframe perimeter makes the full depth and footing legible.
   val front=0.83f
   val back=3.2f
   val halfWidth=1.15f
   val height=2.0f
   for(z in listOf(-halfWidth,halfWidth)) {
    support(x(front,height,z),x(back,height,z),0.14f)
    support(x(front,0f,z),x(front,height,z))
    support(x(back,0f,z),x(back,height,z))
   }
   for(px in listOf(front,back))
    support(x(px,height,-halfWidth),x(px,height,halfWidth),0.14f)
  }
  Equipment.MAT -> {
   support(x(-4.6f,0f,-1.8f),x(4.6f,0f,-1.8f),0.06f)
   support(x(-4.6f,0f,1.8f),x(4.6f,0f,1.8f),0.06f)
   for(ankle in listOf(leftAnkle,rightAnkle))
    body += link(ankle,x(ankle.x+0.25f,0.15f,ankle.z),0.14f)
  }
  Equipment.CABLE_MACHINE, Equipment.LAT_PULLDOWN, Equipment.SEATED_ROW_MACHINE -> {
   val pulley=when(exercise.equipment) {
    Equipment.LAT_PULLDOWN -> x(1.15f,10.1f,0f)
    Equipment.SEATED_ROW_MACHINE -> x(4.4f,2.2f,0f)
    else -> x(4.4f,9.8f,0f)
   }
   support(x(pulley.x,0f,-1.5f),pulley+x(0f,0f,-1.5f))
   support(x(pulley.x,0f,1.5f),pulley+x(0f,0f,1.5f))
   support(pulley+x(0f,0f,-1.5f),pulley+x(0f,0f,1.5f))
   if(exercise.equipment != Equipment.CABLE_MACHINE) seatedSupport()
   if(exercise.equipment == Equipment.LAT_PULLDOWN)
    support(hip+x(0.8f,0.38f,-0.82f),hip+x(0.8f,0.38f,0.82f),0.2f)
   transverseGrip(if(exercise.equipment == Equipment.LAT_PULLDOWN) 1.05f else 0.75f)
   gear(handMid,pulley,0.045f)
  }
  Equipment.LEG_PRESS_MACHINE -> {
   seatedSupport()
   val start=p(originalMotionAt(spec,0f).joints[6],0f)
   val end=p(originalMotionAt(spec,1f).joints[6],0f)
   val foot=(leftAnkle+rightAnkle)*0.5f
   for(z in listOf(-0.95f,0.95f)) {
    val lower=start+x(0f,-0.55f,z)
    val upper=end+x(0f,-0.55f,z)
    support(lower,upper)
    support(x(lower.x,0f,z),lower)
    support(x(upper.x,0f,z),upper)
    gear(foot+x(0f,-0.55f,z),foot+x(0.1f,0.6f,z),0.16f)
   }
   for(y in listOf(-0.22f,0.36f)) gear(foot+x(0.1f,y,-0.95f),foot+x(0.1f,y,0.95f),0.15f)
  }
  Equipment.CHEST_PRESS_MACHINE -> {
   seatedSupport()
   val start=p(originalMotionAt(spec,0f).joints[3],0f)
   val end=p(originalMotionAt(spec,1f).joints[3],0f)
   for(wrist in listOf(leftWrist,rightWrist)) {
    val railZ=if(wrist.z>0) 1.35f else -1.35f
    support(x(start.x,start.y,railZ),x(end.x,end.y,railZ))
    support(x(start.x,0f,railZ),x(start.x,start.y,railZ))
    gear(wrist,x(wrist.x,wrist.y,railZ))
    gear(wrist+x(0f,-0.21f,0f),wrist+x(0f,0.21f,0f))
   }
  }
  Equipment.LEG_CURL_MACHINE -> {
   seatedSupport()
   val ankles=(leftAnkle+rightAnkle)*0.5f
   val knee=(leftKnee+rightKnee)*0.5f
   support(knee+x(-0.2f,0.26f,-0.93f),knee+x(-0.2f,0.26f,0.93f),0.16f)
   val roller=knee+(ankles-knee)*0.94f
   gear(roller+x(0f,0f,-0.93f),roller+x(0f,0f,0.93f),0.21f)
   for(z in listOf(-0.93f,0.93f)) gear(knee+x(0f,0f,z),roller+x(0f,0f,z))
  }
  Equipment.BODYWEIGHT -> when(exercise.id) {
   "calf" -> {
    support(x(handMid.x,0f,-1.08f),x(handMid.x,handMid.y,-1.08f))
    support(x(handMid.x,0f,1.08f),x(handMid.x,handMid.y,1.08f))
    support(x(handMid.x,handMid.y,-1.08f),x(handMid.x,handMid.y,1.08f))
    val toeX=p(originalMotionAt(spec,0f).joints[6],0f).x+0.56f
    for(ankle in listOf(leftAnkle,rightAnkle)) body+=link(ankle,x(toeX,0.13f,ankle.z),0.13f)
   }
   "wall_slide" -> {
    // Both elbows and wrists stay in this stationary plane.
    val wallX=p(primary[2],0f).x
    val halfWidth=2.15f
    for(z in listOf(-halfWidth,halfWidth))
     support(x(wallX,0f,z),x(wallX,10.7f,z))
    for(y in listOf(0f,10.7f))
     support(x(wallX,y,-halfWidth),x(wallX,y,halfWidth))
   }
   "wall_push" -> {
    // An authored wireframe wall: its plane passes through the two fixed
    // hands rather than leaving a visible gap to the wrists.
    for(z in listOf(-1.8f,1.8f))
     support(x(handMid.x,0f,z),x(handMid.x,10.7f,z))
    support(x(handMid.x,handMid.y,-1.8f),x(handMid.x,handMid.y,1.8f))
   }
   "incline_push" -> {
    // A fixed-height hand support at the original incline-push wrist
    // coordinates. The posts make the schematic surface spatially legible.
    support(x(handMid.x,handMid.y,-1.35f),x(handMid.x,handMid.y,1.35f))
    for(z in listOf(-1.35f,1.35f))
     support(x(handMid.x,0f,z),x(handMid.x,handMid.y,z))
   }
   "chair_squat" -> {
    // This exact catalog variant ends with a gentle seat touch. The old
    // single rear rail was too far from the lowered hip to show contact.
    // Keep the chair stationary across all motion phases and show a full
    // seat outline with a center beam, plus four supporting legs.
    val rear=-2.6f
    val front=-0.85f
    val halfWidth=1.05f
    val seatHeight=2.4f
    for(z in listOf(-halfWidth,halfWidth)) {
     support(x(rear,seatHeight,z),x(front,seatHeight,z),0.13f)
     support(x(rear,0f,z),x(rear,seatHeight,z))
     support(x(front,0f,z),x(front,seatHeight,z))
    }
    for(px in listOf(rear,front))
     support(x(px,seatHeight,-halfWidth),x(px,seatHeight,halfWidth),0.13f)
    // A visible load-bearing rail meets the lowered pelvis, not the
    // standing pelvis; the schematic does not prescribe squat depth.
    support(x(-1.45f,seatHeight,-halfWidth),
     x(-1.45f,seatHeight,halfWidth),0.13f)
   }
   else -> Unit
  }
 }
 // A rig is a clearly marked draft until variant-specific independent review.
 return OriginalVolumeScene(exercise.id,exercise.equipment,body,joints,t)
}

fun originalVolumeCoverage(): Set<String> = originalMotionCoverage()
