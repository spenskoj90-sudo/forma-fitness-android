package ru.forma.domain

import org.junit.Assert.*
import org.junit.Test

class OriginalVolumeEvidenceTest {
 @Test fun researchLeadInventoryBindsExactly37ExerciseVariantsWithoutAdmission() {
  val catalog=exerciseCatalog
  assertEquals(37,catalog.size)
  assertEquals(catalog.map{it.id}.toSet(),originalVolumeEvidenceCoverage())
  val hashes=mutableSetOf<String>()
  for(exercise in catalog) {
   val packet=originalVolumeReviewPacket(exercise)
   assertNotNull("Missing evidence packet: "+exercise.id,packet)
   assertEquals(exercise.id,packet!!.exerciseId)
   assertEquals(exercise.equipment,packet.equipment)
   assertEquals(VolumeEvidenceStage.SOURCE_ONLY,packet.stage)
   assertEquals("Unexpected unversioned source change for " + exercise.id,
    if(exercise.id in setOf("goblet","raise","lat_pulldown",
     "scap_pull","wall_push","incline_push")) 2 else 1,packet.revision)
   assertEquals(64,packet.sha256.length)
   assertTrue(hashes.add(packet.sha256))
   assertTrue(packet.researchLeads.isNotEmpty())
   assertTrue(packet.researchLeads.all{
    it.url.startsWith("https://") && it.limitations.isNotBlank()
   })
   assertEquals(packet.sha256,originalVolumeReviewPacket(exercise)!!.sha256)
  }
 }
 @Test fun exactEquipmentMismatchCannotGetProvenanceOrIntegrityDigest() {
  val squat=exerciseCatalog.first{it.id=="squat"}
  assertNull(originalVolumeReviewPacket(squat.copy(equipment=Equipment.DUMBBELL)))
  assertNull(originalVolumeReviewPacket(squat.copy(id="unknown")))
 }
 @Test fun indirectClinicalSourcesCannotBeMisreadAsVerifiedTechnique() {
  val step=originalVolumeReviewPacket(exerciseCatalog.first{it.id=="bench_step"})!!
  assertTrue(step.researchLeads.any{it.scope==VolumeLeadScope.REHAB_POPULATION})
  assertEquals(VolumeEvidenceStage.SOURCE_ONLY,step.stage)
  val press=originalVolumeReviewPacket(exerciseCatalog.first{it.id=="db_press"})!!
  assertTrue(press.researchLeads.any{it.limitations.contains("standing")})
  assertEquals(VolumeEvidenceStage.SOURCE_ONLY,press.stage)
  assertTrue(exerciseCatalog.none{originalVolumeReviewPacket(it)?.stage==VolumeEvidenceStage.ACCEPTED})
 }
}
