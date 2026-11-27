package ru.forma.domain

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ExerciseMediaTest {
 private val exercise=exerciseCatalog.first()
 private val bytes="synthetic test video bytes, not accepted exercise media".toByteArray()
 private fun evidence()=TechniqueMediaEvidence(
  exerciseId=exercise.id,equipment=exercise.equipment,revision=1,
  offlinePath="technique/${exercise.id}/v1.mp4",
  sha256=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)},
  license="fixture license",provenance="fixture source",reviewer="fixture reviewer",
  reviewReference="fixture review of exact asset revision",
  caption=TechniqueCaption("setup","start","motion","finish","range","breathing","errors"))
 private fun resolve(item:TechniqueMediaEvidence)=resolveTechniqueMedia(exercise.id,listOf(item)){bytes}

 @Test fun exactCatalogIdentityAndActualBytesResolve() {
  val item=evidence();var opened:String?=null
  assertEquals(item,resolveTechniqueMedia(exercise.id,listOf(item)){opened=it;bytes})
  assertEquals(item.offlinePath,opened)
 }
 @Test fun emptyTrustedManifestHasNoDemonstrationsForAnyRealExercise() {
  assertTrue(techniqueMediaCatalog.isEmpty())
  exerciseCatalog.forEach{e->
   assertNull(resolveTechniqueMedia(e.id,techniqueMediaCatalog){error("No asset should be read")})
  }
 }
 @Test fun wrongIdOrEquipmentCannotOpenAnyFile() {
  val item=evidence();var reads=0
  fun rejected(id:String,entry:TechniqueMediaEvidence) {
   assertNull(resolveTechniqueMedia(id,listOf(entry)){reads++;bytes})
   assertEquals(0,reads)
  }
  rejected("unknown-exercise",item)
  rejected(exercise.id,item.copy(exerciseId="unknown-exercise"))
  rejected(exercise.id,item.copy(equipment=Equipment.entries.first{it!=item.equipment}))
 }
 @Test fun duplicateIdsAndReusedAssetHashesAreAmbiguous() {
  val item=evidence();val other=exerciseCatalog.first{it.id!=item.exerciseId}
  var reads=0
  fun rejected(entries:List<TechniqueMediaEvidence>) {
   assertNull(resolveTechniqueMedia(exercise.id,entries){reads++;bytes})
   assertEquals(0,reads)
  }
  rejected(listOf(item,item))
  val reused=item.copy(exerciseId=other.id,equipment=other.equipment,
   offlinePath="technique/${other.id}/v1.mp4")
  rejected(listOf(item,reused))
  // Duplicates elsewhere in the manifest must not be ignored either.
  val firstOther=reused.copy(sha256="a".repeat(64))
  val secondOther=firstOther.copy(revision=2,offlinePath="technique/${other.id}/v2.mp4",sha256="b".repeat(64))
  rejected(listOf(item,firstOther,secondOther))
 }
 @Test fun incompleteProvenanceAndReviewAreUnavailable() {
  val item=evidence()
  listOf(item.copy(license=" "),item.copy(provenance=""),item.copy(reviewer=""),
   item.copy(reviewReference="")).forEach{assertNull(resolve(it))}
 }
 @Test fun everyCaptionStageIsRequired() {
  val item=evidence();val c=item.caption
  listOf(c.copy(setup=""),c.copy(start=""),c.copy(motion=""),c.copy(finish=""),
   c.copy(rangeOfMotion=""),c.copy(breathing=""),c.copy(errors="")).forEach{
   assertNull(resolve(item.copy(caption=it)))
  }
 }
 @Test fun malformedHashesAndNonpositiveRevisionsAreUnavailable() {
  val item=evidence()
  listOf("","g".repeat(64),"a".repeat(63),item.sha256.uppercase()).forEach{
   assertNull(resolve(item.copy(sha256=it)))
  }
  listOf(0,-1).forEach{assertNull(resolve(item.copy(revision=it,
   offlinePath="technique/${exercise.id}/v$it.mp4")))}
 }
 @Test fun onlyExactLocalRevisionPathsAreAllowed() {
  val item=evidence()
  listOf("https://example.test/video.mp4","../video.mp4","/video.mp4",
   "technique/${exercise.id}/../v1.mp4","technique/${exercise.id}/v2.mp4",
   "technique/${exercise.id}/v1.svg","technique/${exercise.id}/v1.mp4?url=remote")
   .forEach{assertNull(resolve(item.copy(offlinePath=it)))}
  assertNotNull(resolve(item.copy(offlinePath="technique/${exercise.id}/v1.webm")))
 }
 @Test fun missingCorruptAndUnreadableBytesKeepFallback() {
  val item=evidence()
  assertNull(resolveTechniqueMedia(exercise.id,listOf(item)){null})
  assertNull(resolveTechniqueMedia(exercise.id,listOf(item)){bytes+0})
  assertNull(resolveTechniqueMedia(exercise.id,listOf(item)){throw java.io.IOException("fixture missing")})
 }
 @Test fun cancellationIsNotConvertedIntoUnavailableMedia() {
  val cancelled=java.util.concurrent.CancellationException("fixture cancelled")
  try {
   resolveTechniqueMedia(exercise.id,listOf(evidence())){throw cancelled}
   fail("Cancellation must propagate")
  }catch(actual:java.util.concurrent.CancellationException){assertSame(cancelled,actual)}
 }
}
