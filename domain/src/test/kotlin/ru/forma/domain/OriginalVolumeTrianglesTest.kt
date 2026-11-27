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
 @Test fun authoredTorsoHasSmoothOutwardNormalsAndRealChestWaistDepth() {
  // Uses only the already-authored joint path; the mesh adds no pose claim.
  val squat=exerciseCatalog.first{it.id=="squat"}
  for(phase in listOf(0f,0.5f,1f)) {
   val scene=originalVolumeAt(squat,phase)!!
   val shoulder=scene.links[1].a
   val axis=(scene.links[1].b-shoulder).unit()
   val vertices=originalVolumeTriangles(scene).vertices
   var loftVertices=0
   var minZ=Float.POSITIVE_INFINITY
   var maxZ=Float.NEGATIVE_INFINITY
   for(i in vertices.indices step 9) {
    if(abs(vertices[i+6]-0.57f)>0.0001f)continue
    loftVertices++
    val p=VolumePoint(vertices[i],vertices[i+1],vertices[i+2])
    val n=VolumePoint(vertices[i+3],vertices[i+4],vertices[i+5])
    val rel=p-shoulder
    val along=rel.x*axis.x+rel.y*axis.y+rel.z*axis.z
    val radial=rel-axis*along
    assertTrue("Torso must not have inward surface normals",
     radial.x*n.x+radial.y*n.y+radial.z*n.z>0.15f)
    assertTrue("Torso normals must be near unit length",abs(n.length()-1f)<0.01f)
    minZ=minOf(minZ,p.z)
    maxZ=maxOf(maxZ,p.z)
   }
   assertTrue("Missing authored multi-section torso volume",loftVertices>=380)
   assertTrue("Chest must be volumetric across bilateral Z",maxZ-minZ>1.35f)
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

 @Test fun authoredTubeAndSphericalJointNormalsFaceOutward() {
  val origin=VolumePoint(0f,0f,0f)
  val jointCenter=VolumePoint(0f,3f,0f)
  val scene=OriginalVolumeScene("outward-fixture",Equipment.BODYWEIGHT,
   listOf(VolumeLink(origin,jointCenter,0.2f,VolumeTone.ACTIVE)),
   listOf(VolumeJoint(jointCenter,0.4f,VolumeTone.TORSO)),0f)
  val mesh=originalVolumeTriangles(scene).vertices
  var tubeSideVertices=0
  var jointVertices=0
  for(i in mesh.indices step 9) {
   val x=mesh[i];val y=mesh[i+1];val z=mesh[i+2]
   val nx=mesh[i+3];val ny=mesh[i+4];val nz=mesh[i+5]
   val red=mesh[i+6]
   if(abs(red-0.20f)<0.001f && abs(ny)<0.1f) {
    // Side surface: outward means positive radial dot(normal,position-axis).
    assertTrue("Tube normal faces inward",x*nx+z*nz>0.01f)
    tubeSideVertices++
   } else if(abs(red-0.55f)<0.001f) {
    assertTrue("Spherical joint normal faces inward",
     x*nx+(y-jointCenter.y)*ny+z*nz>0.001f)
    jointVertices++
   }
  }
  assertTrue("No cylinder side normals inspected",tubeSideVertices>=70)
  assertTrue("No joint normals inspected",jointVertices>=200)
 }
}
