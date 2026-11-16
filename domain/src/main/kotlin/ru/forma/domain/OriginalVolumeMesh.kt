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
 // Authored volume-reconstruction layer, separate from the exercise's
 // motion rig. Five elliptical cross-sections make a chest/waist/pelvis
 // silhouette instead of a uniform stick. The original joints, depth and
 // exact exercise/equipment trajectory remain untouched and unreviewed.
 if(scene.links.size>=10 && scene.joints.size>=3 &&
    scene.links[1].tone==VolumeTone.TORSO) {
  val shoulder=scene.links[1].a
  val hip=scene.links[1].b
  val direction=(hip-shoulder).unit()
  // All authored body rigs currently have sagittal XY poses and bilateral Z.
  val side=VolumePoint(0f,0f,1f)
  val depth=direction.cross(side).unit()
  val bodyColor=floatArrayOf(0.57f,0.68f,0.72f)
  // Fraction along shoulder->hip, lateral half-width and sagittal half-depth.
  // This is a stylized neutral body, NOT a measured body scan.
  val sections=listOf(
   Triple(0f,0.57f,0.39f),
   Triple(0.18f,0.72f,0.53f),
   Triple(0.52f,0.58f,0.41f),
   Triple(0.78f,0.53f,0.37f),
   Triple(1f,0.61f,0.43f)
  )
  val sides=16
  fun pos(section:Triple<Float,Float,Float>,i:Int):VolumePoint {
   val theta=2.0*PI*i/sides
   return shoulder+(hip-shoulder)*section.first+
    side*(cos(theta).toFloat()*section.second)+
    depth*(sin(theta).toFloat()*section.third)
  }
  fun normal(section:Triple<Float,Float,Float>,i:Int):VolumePoint {
   val theta=2.0*PI*i/sides
   return (side*(cos(theta).toFloat()/section.second)+
    depth*(sin(theta).toFloat()/section.third)).unit()
  }
  fun emit(p:VolumePoint,n:VolumePoint) {
   put(p.x);put(p.y);put(p.z)
   put(n.x);put(n.y);put(n.z)
   put(bodyColor[0]);put(bodyColor[1]);put(bodyColor[2])
  }
  fun face(a:VolumePoint,an:VolumePoint,b:VolumePoint,bn:VolumePoint,
           c:VolumePoint,cn:VolumePoint) {
   val cross=(b-a).cross(c-a)
   val normalSum=an+bn+cn
   val outward=cross.x*normalSum.x+cross.y*normalSum.y+cross.z*normalSum.z
   if(outward>=0f) {
    emit(a,an);emit(b,bn);emit(c,cn)
   } else {
    emit(a,an);emit(c,cn);emit(b,bn)
   }
  }
  for(level in 0 until sections.size-1)for(i in 0 until sides) {
   val lower=sections[level]
   val upper=sections[level+1]
   val a=pos(lower,i)
   val b=pos(upper,i)
   val c=pos(lower,i+1)
   val d=pos(upper,i+1)
   val an=normal(lower,i)
   val bn=normal(upper,i)
   val cn=normal(lower,i+1)
   val dn=normal(upper,i+1)
   face(a,an,b,bn,c,cn)
   face(c,cn,b,bn,d,dn)
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
