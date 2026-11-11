package ru.forma.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Original, platform-neutral 3-D triangle geometry; no third-party meshes or
 * images. Interleaved 9 floats/vertex: position XYZ, surface normal XYZ,
 * unlit color RGB. The Android renderer may change lighting, not joint paths.
 */
data class VolumeTriangles(val vertices: FloatArray) {
 val vertexCount: Int get() = vertices.size / 9
 init { require(vertices.isNotEmpty() && vertices.size % 27 == 0 && vertices.all { it.isFinite() }) }
}

fun originalVolumeTriangles(scene: OriginalVolumeScene): VolumeTriangles {
 var data=FloatArray(16_384)
 var used=0
 fun put(value: Float) {
  if(used == data.size) data=data.copyOf(data.size*2)
  data[used++]=value
 }
 fun color(tone: VolumeTone): FloatArray = when(tone) {
  VolumeTone.TORSO -> floatArrayOf(0.55f,0.64f,0.67f)
  VolumeTone.ACTIVE -> floatArrayOf(0.20f,0.95f,0.44f)
  VolumeTone.EQUIPMENT -> floatArrayOf(0.80f,0.89f,0.91f)
  VolumeTone.SUPPORT -> floatArrayOf(0.35f,0.49f,0.53f)
 }
 fun triangle(a:VolumePoint,b:VolumePoint,c:VolumePoint,colors:FloatArray) {
  val normal=(b-a).cross(c-a)
  if(normal.length()<0.00001f)return
  val unit=normal.unit()
  listOf(a,b,c).forEach { v ->
   put(v.x);put(v.y);put(v.z)
   put(unit.x);put(unit.y);put(unit.z)
   put(colors[0]);put(colors[1]);put(colors[2])
  }
 }
 // Tubular limbs/gear with actual cylindrical 3-D cross-sections and end caps.
 for(link in scene.links) {
  val axis=(link.b-link.a).unit()
  val helper=if(abs(axis.y)<0.8f) VolumePoint(0f,1f,0f) else VolumePoint(1f,0f,0f)
  val side=axis.cross(helper).unit()
  val other=axis.cross(side).unit()
  fun circle(center: VolumePoint,theta:Double):VolumePoint {
   val c=cos(theta).toFloat()
   val s=sin(theta).toFloat()
   return center + (side*c+other*s)*link.radius
  }
  val rgb=color(link.tone)
  for(i in 0 until 12) {
   val a=2.0*PI*i/12.0
   val b=2.0*PI*(i+1)/12.0
   val a0=circle(link.a,a)
   val b0=circle(link.a,b)
   val a1=circle(link.b,a)
   val b1=circle(link.b,b)
   // The outward face normal must point away from the cylinder axis.
   // Reversed winding would shade every tube as if its skin faced inward.
   triangle(a0,b0,a1,rgb)
   triangle(b0,b1,a1,rgb)
   triangle(link.a,b0,a0,rgb)
   triangle(link.b,a1,b1,rgb)
  }
 }
 // Spherical nodes give joints and head a genuinely volumetric silhouette.
 for(joint in scene.joints) {
  val rgb=color(joint.tone)
  fun surface(lat: Int,lon: Int):VolumePoint {
   val theta=PI*lat/8.0
   val phi=2.0*PI*lon/12.0
   return joint.position+VolumePoint(
    (sin(theta)*cos(phi)).toFloat()*joint.radius,
    cos(theta).toFloat()*joint.radius,
    (sin(theta)*sin(phi)).toFloat()*joint.radius
   )
  }
  for(lat in 0 until 8)for(lon in 0 until 12) {
   val a=surface(lat,lon)
   val b=surface(lat+1,lon)
   val c=surface(lat,lon+1)
   val d=surface(lat+1,lon+1)
   // Latitude/longitude order otherwise yields inward sphere normals.
   triangle(a,c,b,rgb)
   triangle(c,d,b,rgb)
  }
 }
 return VolumeTriangles(data.copyOf(used))
}
