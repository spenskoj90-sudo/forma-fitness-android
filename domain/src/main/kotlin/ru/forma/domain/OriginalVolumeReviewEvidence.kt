package ru.forma.domain

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Research leads are NOT exercise prescriptions or qualified technique review.
 * Each exact variant remains SOURCE_ONLY until an independent human reviewer
 * verifies joint trajectory, equipment geometry, ROM, breathing and errors.
 */
enum class VolumeEvidenceStage { SOURCE_ONLY, REVIEW_PENDING, ACCEPTED }
enum class VolumeLeadScope { GENERAL, RELATED_VARIANT, REHAB_POPULATION }
data class VolumeResearchLead(val url:String,val scope:VolumeLeadScope,val limitations:String) {
 init { require(url.startsWith("https://") && limitations.isNotBlank()) }
}
data class VolumeReviewPacket(
 val exerciseId:String,
 val equipment:Equipment,
 val revision:Int,
 val sha256:String,
 val authorship:String,
 val researchLeads:List<VolumeResearchLead>,
 val stage:VolumeEvidenceStage
) {
 init { require(revision>0 && Regex("[0-9a-f]{64}").matches(sha256) && researchLeads.isNotEmpty()) }
}

private fun lead(id:String,scope:VolumeLeadScope,note:String)=
 VolumeResearchLead("https://pubmed.ncbi.nlm.nih.gov/"+id+"/",scope,note)

private val squat=lead("38576836",VolumeLeadScope.RELATED_VARIANT,
 "Squat biomechanics clinical commentary; not individual safe ROM, loaded variant or machine certification.")
private val band=lead("42795858",VolumeLeadScope.GENERAL,
 "Elastic resistance narrative review; attachment, force and direction vary by band setup.")
private val shoulder=lead("40165544",VolumeLeadScope.REHAB_POPULATION,
 "Shoulder pathology guideline; clinical population and exact form applicability need assessment.")
private val row=lead("19197209",VolumeLeadScope.RELATED_VARIANT,
 "Row trunk loading differs by equipment, support and stance; not exact model validation.")
private val raise=lead("33312291",VolumeLeadScope.RELATED_VARIANT,
 "Lateral raise/shoulder press EMG; no universal clinically safe angle or trajectory.")
private val press=lead("36560129",VolumeLeadScope.RELATED_VARIANT,
 "Seated dumbbell overhead press study; FORMA uses standing dumbbells.")
private val calf=lead("38156065",VolumeLeadScope.RELATED_VARIANT,
 "Calf adaptation study; technique and individual ankle range not determined.")
private val cable=lead("26694502",VolumeLeadScope.RELATED_VARIANT,
 "Cable triceps handle study; measured EMG does not establish safe joint-angle limits.")
private val legCurl=lead("38006325",VolumeLeadScope.RELATED_VARIANT,
 "Hamstring exercise comparison; exact seated/prone machine geometry requires assessment.")
private val general=VolumeResearchLead(
 "https://dxpprod.nsca.com/education/articles/ptq/teaching-resistance-training-movement-patterns/",
 VolumeLeadScope.GENERAL,
 "NSCA general resistance movement patterns; NOT direct evidence for this exact exercise/equipment.")
private val rehabStep=lead("36744516",VolumeLeadScope.REHAB_POPULATION,
 "Step-up biomechanics includes post-stroke participants; cannot prescribe healthy-user technique.")

private val leadsById:Map<String,List<VolumeResearchLead>> = buildMap {
 fun assign(ids:String,lead:VolumeResearchLead){for(id in ids.split(" "))require(put(id,listOf(lead))==null)}
 assign("squat goblet chair_squat kettlebell_squat smith_squat leg_press",squat)
 assign("band_row band_press band_extension",band)
 assign("prone_w wall_slide scap_pull",shoulder)
 assign("db_row bb_row machine_row lat_pulldown",row)
 assign("raise",raise)
 assign("db_press",press)
 assign("calf",calf)
 assign("cable_triceps",cable)
 assign("leg_curl",legCurl)
 assign("wall_push bird_dog floor_press curl bridge incline_push dead_bug db_rdl hammer bb_rdl bb_curl self_curl kettlebell_deadlift mat_plank bench_step chest_press",general)
 put("bench_step",listOf(general,rehabStep))
}
fun originalVolumeEvidenceCoverage():Set<String> = leadsById.keys

// Revision each exact exercise when its authored rig/equipment trajectory changes.
// Digests bind source keyposes, not final rendered images or expert acceptance.
private val originalPoseRevisions=mapOf("goblet" to 2,"raise" to 2,"lat_pulldown" to 2,
 "scap_pull" to 2,"wall_push" to 2,"incline_push" to 2,"bench_step" to 2,
 "chair_squat" to 2)

/** Reproducible digest of five source-authored keyposes and exact equipment. */
private fun digestVolume(exercise:Exercise,revision:Int):String {
 val digest=MessageDigest.getInstance("SHA-256")
 digest.update(("forma-3d-v"+revision+":"+exercise.id+":"+exercise.equipment.name).toByteArray(Charsets.UTF_8))
 val raw=ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
 fun put(n:Float){raw.clear();raw.putInt(n.toRawBits());digest.update(raw.array())}
 fun point(p:VolumePoint){put(p.x);put(p.y);put(p.z)}
 for(t in listOf(0f,0.25f,0.5f,0.75f,1f)) {
  val scene=originalVolumeAt(exercise,t) ?: error("Missing exact scene")
  put(t)
  for(link in scene.links) {
   point(link.a);point(link.b);put(link.radius);put(link.tone.ordinal.toFloat())
  }
  for(joint in scene.joints) {
   point(joint.position);put(joint.radius);put(joint.tone.ordinal.toFloat())
  }
 }
 return digest.digest().joinToString(""){"%02x".format(it)}
}

/** Never admit a generated rig merely because URL/hash fields are populated. */
fun originalVolumeReviewPacket(exercise:Exercise):VolumeReviewPacket? {
 if(originalMotionFor(exercise)==null)return null
 val leads=leadsById[exercise.id] ?: return null
 val revision=originalPoseRevisions[exercise.id] ?: 1
 return VolumeReviewPacket(
  exerciseId=exercise.id,equipment=exercise.equipment,revision=revision,
  sha256=digestVolume(exercise,revision),
  authorship="Original FORMA offline Kotlin kinematics, no imported footage or 3D assets",
  researchLeads=leads,
  stage=VolumeEvidenceStage.SOURCE_ONLY
 )
}
