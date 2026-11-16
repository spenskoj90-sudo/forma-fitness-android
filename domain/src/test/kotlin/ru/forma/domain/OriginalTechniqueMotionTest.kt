package ru.forma.domain

import org.junit.Assert.*
import org.junit.Test

class OriginalTechniqueMotionTest {
 @Test fun all37CatalogEntriesHaveDistinctAndFiniteAnimatedJointPaths() {
  assertEquals(37, exerciseCatalog.size)
  assertEquals(exerciseCatalog.map { it.id }.toSet(), originalMotionCoverage())
  exerciseCatalog.forEach { exercise ->
   val spec = originalMotionFor(exercise)
   assertNotNull("No sketch for ${exercise.id}", spec)
   assertEquals(exercise.equipment, spec!!.equipment)
   val start = originalMotionAt(spec, 0f)
   val middle = originalMotionAt(spec, 0.5f)
   val end = originalMotionAt(spec, 1f)
   assertEquals(7, start.joints.size)
   assertNotEquals("${exercise.id} must move", start, end)
   for (pose in listOf(start, middle, end)) {
    assertTrue("${exercise.id} out of scene bounds",pose.joints.all {
     it.x.isFinite() && it.y.isFinite() && it.x in 0f..120f && it.y in 0f..120f
    })
   }
  }
 }
 @Test fun loadedSquatsUseChestHoldAndSmithBarNotBodyweightArmTrajectory() {
  val plain=originalMotionFor(exerciseCatalog.first { it.id=="squat" })!!
  val goblet=originalMotionFor(exerciseCatalog.first { it.id=="goblet" })!!
  val kettlebell=originalMotionFor(exerciseCatalog.first { it.id=="kettlebell_squat" })!!
  val smith=originalMotionFor(exerciseCatalog.first { it.id=="smith_squat" })!!
  assertNotEquals(plain.kind,goblet.kind)
  assertEquals(goblet.kind,kettlebell.kind)
  assertNotEquals(plain.kind,smith.kind)
  for(spec in listOf(goblet,kettlebell)) {
   val p=originalMotionAt(spec,1f)
   assertTrue("Chest hold must remain near shoulder",kotlin.math.abs(p.joints[3].x-p.joints[1].x)<12f)
   assertTrue(kotlin.math.abs(p.joints[3].y-p.joints[1].y)<12f)
  }
 }
 @Test fun smithBarAndGripRemainOnVerticalGuideAsSquatDescends() {
  val exercise=exerciseCatalog.first{it.id=="smith_squat"}
  val spec=originalMotionFor(exercise)!!
  val poses=listOf(0f,0.5f,1f).map{originalMotionAt(spec,it)}
  val guideX=poses.first().joints[1].x
  for(pose in poses) {
   assertEquals("Smith shoulder must stay on the guided X",guideX,pose.joints[1].x,0.001f)
   assertEquals("Smith wrist grip must stay on guide X",guideX,pose.joints[3].x,0.001f)
   assertEquals("Grip must meet the bar at shoulder height",
    pose.joints[1].y,pose.joints[3].y,0.001f)
  }
  assertTrue("Smith bar must descend rather than slide forward",
   poses.last().joints[1].y-poses.first().joints[1].y>10f)
 }
 @Test fun backToWallSlideSketchKeepsBothForearmsAtTheWallWhileRaisingHands() {
  val slide=originalMotionFor(exerciseCatalog.first{it.id=="wall_slide"})!!
  val poses=listOf(0f,0.5f,1f).map{originalMotionAt(slide,it)}
  for(pose in poses) {
   // X=49 is the hand/forearm wall plane in the authored side view.
   assertEquals(49f,pose.joints[2].x,0.001f)
   assertEquals(49f,pose.joints[3].x,0.001f)
   assertTrue("Back-facing wall must stay behind the torso",
    pose.joints[3].x<pose.joints[1].x)
   assertEquals("Torso must not drift toward the wall",53f,pose.joints[4].x,0.001f)
  }
  assertTrue("Hands must move upward along the same wall, not swing forward",
   poses.first().joints[3].y-poses.last().joints[3].y>20f)
  assertEquals(poses.first().joints[3].x,poses.last().joints[3].x,0.001f)
 }
 @Test fun rejectWrongEquipmentAndMissingCatalogIds() {
  val squat=exerciseCatalog.first { it.id=="squat" }
  assertNull(originalMotionFor(squat.copy(equipment=Equipment.BARBELL)))
  assertNull(originalMotionFor(squat.copy(id="unknown")))
 }
 @Test fun interpolationIsBoundedAndSafeForInvalidInput() {
  val exercise=exerciseCatalog.first { it.id=="band_row" }
  val spec=originalMotionFor(exercise)!!
  assertEquals(originalMotionAt(spec,0f),originalMotionAt(spec,-1f))
  assertEquals(originalMotionAt(spec,1f),originalMotionAt(spec,3f))
  assertEquals(originalMotionAt(spec,0.5f),originalMotionAt(spec,0.5f))
  try { originalMotionAt(spec,Float.NaN); fail("must reject NaN") }
  catch (_: IllegalArgumentException) {}
 }
}
