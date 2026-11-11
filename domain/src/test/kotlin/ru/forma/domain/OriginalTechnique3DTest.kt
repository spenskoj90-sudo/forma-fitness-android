package ru.forma.domain

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class OriginalTechnique3DTest {
 @Test fun all37ExactExerciseEquipmentPairsProduceVolumetricMovingOfflineRigs() {
  assertEquals(37, exerciseCatalog.size)
  assertEquals(exerciseCatalog.map { it.id }.toSet(), originalVolumeCoverage())
  for(exercise in exerciseCatalog) {
   val start = originalVolumeAt(exercise,0f)
   val mid = originalVolumeAt(exercise,0.5f)
   val end = originalVolumeAt(exercise,1f)
   assertNotNull("No 3D source rig for " + exercise.id,start)
   assertNotNull(mid)
   assertNotNull(end)
   for(scene in listOf(start!!,mid!!,end!!)) {
    assertEquals(exercise.id,scene.exerciseId)
    assertEquals(exercise.equipment,scene.equipment)
    assertTrue(scene.links.size >= 10)
    assertTrue(scene.joints.size >= 7)
    assertTrue("Not volumetric: " + exercise.id,
      scene.links.flatMap { listOf(it.a.z,it.b.z) }.toSet().size >= 4)
    assertTrue(scene.links.all { it.a.x.isFinite() && it.b.y.isFinite() && it.radius > 0f })
    assertTrue(scene.joints.all { it.position.z.isFinite() && it.radius > 0f })
    assertTrue(scene.links.all {
      it.a.x in -15f..15f && it.b.x in -15f..15f &&
      it.a.y in -3f..15f && it.b.y in -3f..15f
    })
   }
   assertNotEquals("Static rig for " + exercise.id,
     start.links.take(10),end!!.links.take(10))
  }
 }
 @Test fun equipmentMeshesCannotBeSubstitutedByBodyweightVariant() {
  for(exercise in exerciseCatalog) {
   val scene=originalVolumeAt(exercise,0f)!!
   if(exercise.equipment != Equipment.BODYWEIGHT) {
    assertTrue("Missing gear or support for " + exercise.id,
      scene.links.any { it.tone==VolumeTone.EQUIPMENT || it.tone==VolumeTone.SUPPORT } ||
       scene.joints.any { it.tone==VolumeTone.EQUIPMENT })
   }
   if(exercise.id=="smith_squat") assertTrue(scene.links.count { it.tone==VolumeTone.SUPPORT }>=2)
   if(exercise.id=="leg_press") assertTrue(scene.links.count { it.tone==VolumeTone.SUPPORT }>=5)
  }
 }
 @Test fun rejectImpostorEquipmentAndNonfinitePhaseButClampFiniteScrubbing() {
  val squat=exerciseCatalog.first { it.id=="squat" }
  assertNull(originalVolumeAt(squat.copy(equipment=Equipment.BARBELL),0.5f))
  assertNull(originalVolumeAt(squat.copy(id="not_in_catalog"),0f))
  assertEquals(originalVolumeAt(squat,0f),originalVolumeAt(squat,-9f))
  assertEquals(originalVolumeAt(squat,1f),originalVolumeAt(squat,9f))
  try { originalVolumeAt(squat,Float.NaN);fail("NaN must be rejected") }
  catch(_:IllegalArgumentException) {}
  try { originalVolumeAt(squat,Float.POSITIVE_INFINITY);fail("infinite fraction must be rejected") }
  catch(_:IllegalArgumentException) {}
 }
 @Test fun leftAndRightLimbsHaveIndependentDepth() {
  val squat=originalVolumeAt(exerciseCatalog.first { it.id=="squat" },0f)!!
  val leftArm=squat.links[2]
  val rightArm=squat.links[4]
  assertTrue(leftArm.a.z>0f)
  assertTrue(rightArm.a.z<0f)
  assertTrue(abs(leftArm.a.z-rightArm.a.z)>0.5f)
  assertEquals(VolumeTone.ACTIVE,leftArm.tone)
  assertEquals(VolumeTone.ACTIVE,rightArm.tone)
 }
}
