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
 @Test fun gobletHasOneSharedVerticalDumbbellButBentOverRowStillHasTwo() {
  val goblet=exerciseCatalog.first{it.id=="goblet"}
  val row=exerciseCatalog.first{it.id=="db_row"}
  for(t in listOf(0f,0.5f,1f)) {
   val scene=originalVolumeAt(goblet,t)!!
   val loads=scene.links.filter{it.tone==VolumeTone.EQUIPMENT}
   assertEquals("Goblet must show exactly one load at phase "+t,1,loads.size)
   val dumbbell=loads.single()
   assertTrue("Goblet load must be vertical",abs(dumbbell.a.y-dumbbell.b.y)>0.5f)
   assertTrue(abs(dumbbell.a.x-dumbbell.b.x)<0.0001f)
   assertTrue(abs(dumbbell.a.z-dumbbell.b.z)<0.0001f)
   assertEquals(2,scene.joints.count{it.tone==VolumeTone.EQUIPMENT})
   val center=(dumbbell.a+dumbbell.b)*0.5f
   assertTrue("Goblet weight should remain near chest",
    (center-scene.joints[1].position).length()<3f)
   val rowScene=originalVolumeAt(row,t)!!
   assertEquals("Catalog row uses two dumbbells, not a one-arm bench variation",
    2,rowScene.links.count{it.tone==VolumeTone.EQUIPMENT})
  }
 }
 @Test fun lateralRaiseAbductsBothArmsIn3DDepthWithTransverseDumbbells() {
  val raise=exerciseCatalog.first{it.id=="raise"}
  val down=originalVolumeAt(raise,0f)!!
  val up=originalVolumeAt(raise,1f)!!
  val leftDown=down.links[3].b
  val rightDown=down.links[5].b
  val leftUp=up.links[3].b
  val rightUp=up.links[5].b
  assertTrue("Left arm must move laterally, not only in 2D",leftUp.z-leftDown.z>1.3f)
  assertTrue("Right arm must move laterally, not only in 2D",rightDown.z-rightUp.z>1.3f)
  assertTrue("Bilateral abduction must remain mirrored",abs(leftUp.z+rightUp.z)<0.01f)
  assertTrue("Left raised wrist must remain near its shoulder level",
   abs(leftUp.y-up.links[2].a.y)<0.3f)
  assertTrue("Right raised wrist must remain near its shoulder level",
   abs(rightUp.y-up.links[4].a.y)<0.3f)
  val dumbbells=up.links.filter{it.tone==VolumeTone.EQUIPMENT}
  assertEquals(2,dumbbells.size)
  assertTrue("Both dumbbells must be transverse to lateral arms",
   dumbbells.all{abs(it.b.x-it.a.x)>0.7f && abs(it.b.z-it.a.z)<0.001f})
 }
 @Test fun latPulldownBarFollowsBothHandsThroughThePull() {
  val exercise=exerciseCatalog.first{it.id=="lat_pulldown"}
  val start=originalVolumeAt(exercise,0f)!!
  val end=originalVolumeAt(exercise,1f)!!
  for(scene in listOf(start,end)) {
   val bar=scene.links.filter{it.tone==VolumeTone.EQUIPMENT}.single()
   val leftHand=scene.links[3].b
   val rightHand=scene.links[5].b
   val center=(bar.a+bar.b)*0.5f
   val hands=(leftHand+rightHand)*0.5f
   assertTrue("Pulldown bar must remain held at hand height",abs(center.y-hands.y)<0.001f)
   assertTrue("Pulldown bar must remain at the hand position",abs(center.x-hands.x)<0.001f)
   assertTrue("Pulldown bar must span both hands",bar.b.z-bar.a.z>1.9f)
  }
  val startBar=start.links.first{it.tone==VolumeTone.EQUIPMENT}
  val endBar=end.links.first{it.tone==VolumeTone.EQUIPMENT}
  assertTrue("Pulldown bar must descend with the hands, not stay on the top rail",
   startBar.a.y-endBar.a.y>2f)
 }
 @Test fun authoredWallInclineAndPullupSupportsActuallyMeetTheHands() {
  for(id in listOf("scap_pull","wall_push","incline_push")) {
   val exercise=exerciseCatalog.first{it.id==id}
   var stationaryCrossbar:VolumeLink?=null
   for(t in listOf(0f,0.5f,1f)) {
    val scene=originalVolumeAt(exercise,t)!!
    val leftHand=scene.links[3].b
    val rightHand=scene.links[5].b
    val handMid=(leftHand+rightHand)*0.5f
    val supports=scene.links.filter{it.tone==VolumeTone.SUPPORT}
    assertEquals("Wrong supporting structure for "+id,
     if(id=="scap_pull") 1 else 3,supports.size)
    val crossbars=supports.filter{
     abs(it.a.y-it.b.y)<0.001f && abs(it.b.z-it.a.z)>2.5f
    }
    assertEquals("Missing wrist-height support for "+id,1,crossbars.size)
    val crossbar=crossbars.single()
    assertTrue("Support must meet hands in X for "+id,abs(crossbar.a.x-handMid.x)<0.001f)
    assertTrue("Support must meet hands in Y for "+id,abs(crossbar.a.y-handMid.y)<0.001f)
    assertTrue("Left grip outside support for "+id,
     leftHand.z in crossbar.a.z..crossbar.b.z)
    assertTrue("Right grip outside support for "+id,
     rightHand.z in crossbar.a.z..crossbar.b.z)
    if(stationaryCrossbar==null) stationaryCrossbar=crossbar
    else assertEquals("The physical support must remain fixed for "+id,
     stationaryCrossbar,crossbar)
   }
  }
 }
 @Test fun benchStepRaisedFeetStayAboveStationaryPlatformAtBothEndpoints() {
  val exercise=exerciseCatalog.first{it.id=="bench_step"}
  val scenes=listOf(0f,0.5f,1f).map{originalVolumeAt(exercise,it)!!}
  val firstSupport=scenes.first().links.filter{it.tone==VolumeTone.SUPPORT}
  assertEquals("Platform must have four top edges and four legs",8,firstSupport.size)
  for(scene in scenes) {
   val supports=scene.links.filter{it.tone==VolumeTone.SUPPORT}
   assertEquals("Platform cannot follow the moving feet",firstSupport,supports)
   val top=supports.filter{abs(it.a.y-2f)<0.001f && abs(it.b.y-2f)<0.001f}
   assertEquals("Platform needs a closed top perimeter",4,top.size)
   val corners=top.flatMap{listOf(it.a,it.b)}
   val minX=corners.minOf{it.x}
   val maxX=corners.maxOf{it.x}
   val minZ=corners.minOf{it.z}
   val maxZ=corners.maxOf{it.z}
   assertTrue("Platform must be a surface, not a zero-width rail",maxZ-minZ>2f)
   assertTrue("Platform must extend along the stepping direction",maxX-minX>5f)
   if(scene.phase==0f || scene.phase==1f) {
    val feet=listOf(scene.links[7].b,scene.links[9].b)
    val raised=feet.maxBy{it.y}
    assertTrue("Raised foot must be close to platform height",abs(raised.y-2f)<0.35f)
    assertTrue("Raised foot must remain within platform X bounds",raised.x in minX..maxX)
    assertTrue("Raised foot must remain within platform Z bounds",raised.z in minZ..maxZ)
   }
  }
 }
 @Test fun chairSquatSeatStaysFixedAndMeetsOnlyLoweredPelvis() {
  val exercise=exerciseCatalog.first{it.id=="chair_squat"}
  val scenes=listOf(0f,0.5f,1f).map{originalVolumeAt(exercise,it)!!}
  val fixed=scenes.first().links.filter{it.tone==VolumeTone.SUPPORT}
  assertEquals("Chair needs four legs, perimeter and a contact beam",9,fixed.size)
  for(scene in scenes) {
   val supports=scene.links.filter{it.tone==VolumeTone.SUPPORT}
   assertEquals("Chair must not track the athlete",fixed,supports)
   val top=supports.filter{abs(it.a.y-2.4f)<0.001f && abs(it.b.y-2.4f)<0.001f}
   assertEquals("Seat needs a complete perimeter and contact beam",5,top.size)
   val legs=supports.filter{abs(it.b.y-it.a.y)>2.3f}
   assertEquals("Seat must have four fixed legs",4,legs.size)
  }
  val beam=fixed.single{
   abs(it.a.x+1.45f)<0.001f && abs(it.b.x+1.45f)<0.001f &&
    abs(it.b.z-it.a.z)>2f && abs(it.a.y-it.b.y)<0.001f
  }
  val highHip=scenes.first().joints[2]
  val lowHip=scenes.last().joints[2]
  fun distanceToBeam(hip:VolumeJoint):Float {
   assertTrue("Pelvis must stay over the seat width",
    hip.position.z in beam.a.z..beam.b.z)
   return (hip.position-VolumePoint(beam.a.x,beam.a.y,hip.position.z)).length()
  }
  assertTrue("Schematic squat must descend",lowHip.position.y<highHip.position.y)
  assertTrue("Lowered pelvis must meet the physical seat beam",
   distanceToBeam(lowHip)<=lowHip.radius+beam.radius)
  assertTrue("The standing pelvis must clear the chair",
   distanceToBeam(highHip)>highHip.radius+beam.radius+0.3f)
 }
 @Test fun wallSlideHandsRemainAtFixedPosteriorWallAcrossEntireMotion() {
  val exercise=exerciseCatalog.first{it.id=="wall_slide"}
  val scenes=listOf(0f,0.5f,1f).map{originalVolumeAt(exercise,it)!!}
  val wall=scenes.first().links.filter{it.tone==VolumeTone.SUPPORT}
  assertEquals("Need two wall posts and two frame crossbars",4,wall.size)
  val wallX=(49f-60f)*0.092f
  for(scene in scenes) {
   assertEquals("Wall must not follow the arms",wall,
    scene.links.filter{it.tone==VolumeTone.SUPPORT})
   for(i in listOf(2,3,4,5)) {
    val contact=scene.links[i].b
    assertTrue("Elbow/wrist must stay in stationary wall plane",
     abs(contact.x-wallX)<0.001f)
    assertTrue("Hands must remain within wall width",
     contact.z in -1.6f..1.6f)
   }
   assertTrue("Wall must be behind torso with a small contact gap",
    scene.joints[1].position.x-wallX in 0f..0.5f)
  }
  assertTrue("Hands must rise on the wall instead of swinging forward",
   scenes.last().links[3].b.y-scenes.first().links[3].b.y>2f)
 }
 @Test fun birdDogAndDeadBugReachWithOppositeArmAndLegAndAlternateSides() {
  for(id in listOf("bird_dog","dead_bug")) {
   val exercise=exerciseCatalog.first{it.id==id}
   val armDeltas=mutableListOf<Float>()
   val legDeltas=mutableListOf<Float>()
   for(t in listOf(0f,1f)) {
    val scene=originalVolumeAt(exercise,t)!!
    val leftHand=scene.links[3].b
    val rightHand=scene.links[5].b
    val leftFoot=scene.links[7].b
    val rightFoot=scene.links[9].b
    // Bird-dog reach is upward; supine dead-bug arm reaches along X
    // while its opposite leg lowers, so use direction-specific measures.
    val armDelta=if(id=="bird_dog") leftHand.y-rightHand.y
                 else leftHand.x-rightHand.x
    val legDelta=if(id=="bird_dog") leftFoot.y-rightFoot.y
                 else rightFoot.y-leftFoot.y
    assertTrue("Arms must show distinguishable alternating reach for "+id,
     abs(armDelta)>2f)
    assertTrue("Legs must show distinguishable alternating reach for "+id,
     abs(legDelta)>2f)
    assertTrue("Extended arm and leg must be on opposite sides for "+id,
     armDelta*legDelta<0f)
    armDeltas+=armDelta
    legDeltas+=legDelta
   }
   assertTrue("The extended arm side must alternate for "+id,
    armDeltas[0]*armDeltas[1]<0f)
   assertTrue("The extended leg side must alternate for "+id,
    legDeltas[0]*legDeltas[1]<0f)
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
