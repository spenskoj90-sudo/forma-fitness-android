package ru.forma.domain

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class OriginalVolumeTrianglesTest {
 @Test fun all37VariantsCreateFiniteVisibleThreeDimensionalTriangleMeshes() {
  for(exercise in exerciseCatalog) {
   for(phase in listOf(0f,0.5f,1f)) {
    val scene=originalVolumeAt(exercise,phase)!!
    val mesh=originalVolumeTriangles(scene)
    assertTrue("Empty geometry: "+exercise.id,mesh.vertexCount>500)
    assertEquals(0,mesh.vertexCount%3)
    assertTrue("Excessive CPU mesh: "+exercise.id,mesh.vertexCount<30_000)
    val vertices=mesh.vertices
    assertTrue(vertices.all { it.isFinite() })
    val depths=vertices.indices.filter { it%9==2 }.map { vertices[it] }
    assertTrue(depths.max()!!-depths.min()!!>1f)
    val colors=vertices.indices.filter { it%9>=6 }.map { vertices[it] }
    assertTrue(colors.all { it in 0f..1f })
   }
  }
 }
 @Test fun tubeRadiusChangesGeometryInAllThreeAxes() {
  val p=VolumePoint(0f,0f,0f)
  val scene=OriginalVolumeScene("fixture",Equipment.BODYWEIGHT,
   listOf(VolumeLink(p,VolumePoint(0f,3f,0f),0.2f,VolumeTone.ACTIVE)),
   listOf(VolumeJoint(VolumePoint(0f,3f,0f),0.4f,VolumeTone.TORSO)),0f)
  val mesh=originalVolumeTriangles(scene)
  val xs=mesh.vertices.indices.filter{it%9==0}.map{mesh.vertices[it]}
  val ys=mesh.vertices.indices.filter{it%9==1}.map{mesh.vertices[it]}
  val zs=mesh.vertices.indices.filter{it%9==2}.map{mesh.vertices[it]}
  assertTrue(xs.min()!! < -0.1f && xs.max()!! > 0.1f)
  assertTrue(ys.max()!! > 2.9f)
  assertTrue(zs.min()!! < -0.1f && zs.max()!! > 0.1f)
 }
 @Test fun rejectMalformedNonfiniteRigInput() {
  try { VolumePoint(Float.NaN,0f,0f); fail("NaN coordinate") }
  catch(_:IllegalArgumentException) {}
  try { VolumeLink(VolumePoint(0f,0f,0f),VolumePoint(0f,0f,0f),0.1f,VolumeTone.TORSO); fail("Zero bone") }
  catch(_:IllegalArgumentException) {}
 }
}
