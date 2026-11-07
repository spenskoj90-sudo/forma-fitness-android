package ru.forma.domain

import org.junit.Assert.*
import org.junit.Test

class MeasurementJournalTest {
 @Test fun identicalRetryKeepsOneMeasurementAndNeverRewritesOtherRecords() {
  val p=Profile(id="owner",name="Owner")
  val first=Measurement(id="stable-draft-id",dateMs=1000L,
   values=mapOf("weight" to 75.5))
  val once=p.withMeasurement(first)
  val twice=once.withMeasurement(first)
  assertEquals(once,twice)
  assertEquals(listOf(first),twice.measurements)
  assertEquals(75.5,twice.weightKg,0.0)
  assertEquals(p.id,twice.id)
 }

 @Test fun sameDraftUuidWithDifferentPayloadFailsInsteadOfDuplicating() {
  val p=Profile(id="owner",name="Owner")
   .withMeasurement(Measurement(id="draft",dateMs=1000L,values=mapOf("weight" to 70.0)))
  assertTrue(runCatching{
   p.withMeasurement(Measurement(id="draft",dateMs=1000L,values=mapOf("weight" to 71.0)))
  }.isFailure)
  assertEquals(1,p.measurements.size)
  assertEquals(70.0,p.weightKg,0.0)
 }

 @Test fun editingRequiresExistingIdAndPreservesOtherMeasurementAndIdentity() {
  val original=Measurement(id="original",dateMs=1000L,values=mapOf("waist" to 80.0))
  val other=Measurement(id="other",dateMs=2000L,values=mapOf("height" to 180.0))
  val p=Profile(id="owner",name="Owner").withMeasurement(original).withMeasurement(other)
  assertTrue(runCatching{
   p.withMeasurement(Measurement(id="missing",dateMs=9000L,
    values=mapOf("waist" to 85.0)),editingId="missing")
  }.isFailure)
  val updated=original.copy(values=mapOf("waist" to 79.0))
  val changed=p.withMeasurement(updated,editingId="original")
  assertEquals(listOf(updated,other),changed.measurements)
  assertEquals(180.0,changed.heightCm,0.0)
  assertEquals(p.id,changed.id)
 }

 @Test fun exactReplayAfterManualProfileChangeDoesNotRevertWeightOrHeight() {
  val entry=Measurement(id="stable-draft",dateMs=1000L,
   values=mapOf("weight" to 75.0,"height" to 180.0))
  val updated=Profile(id="owner",name="Owner").withMeasurement(entry)
   .copy(weightKg=79.0,heightCm=181.0)
  // A late second tap or editor retry must not restore the old measurements
  // into profile summary fields changed since the original journal write.
  assertEquals(updated,updated.withMeasurement(entry))
  assertEquals(updated,updated.withMeasurement(entry,editingId=entry.id))
  assertEquals(79.0,updated.withMeasurement(entry).weightKg,0.0)
  assertEquals(181.0,updated.withMeasurement(entry).heightCm,0.0)
 }

 @Test fun duplicateMeasurementDoesNotMixDistinctProfiles() {
  val first=Measurement(id="shared-id",dateMs=1000L,values=mapOf("weight" to 82.0))
  val a=Profile(id="a",name="A").withMeasurement(first)
  val b=Profile(id="b",name="B").withMeasurement(first.copy(values=mapOf("weight" to 65.0)))
  assertEquals(82.0,a.weightKg,0.0)
  assertEquals(65.0,b.weightKg,0.0)
  assertNotEquals(a.id,b.id)
 }
 @Test fun waistInsertAndOldCorrectionPreserveManualWeightAndHeight() {
  val body=Measurement(id="body",dateMs=1000L,values=mapOf("weight" to 75.0,"height" to 180.0))
  val waist=Measurement(id="waist",dateMs=2000L,values=mapOf("waist" to 80.0))
  val manual=Profile(id="owner",name="Owner").withMeasurement(body)
   .withMeasurement(waist).copy(weightKg=79.0,heightCm=183.0)
  val inserted=manual.withMeasurement(Measurement(id="new-waist",dateMs=3000L,values=mapOf("waist" to 81.0)))
  assertEquals(manual.copy(measurements=inserted.measurements),inserted)
  val corrected=inserted.withMeasurement(waist.copy(values=mapOf("waist" to 78.0)),waist.id)
  assertEquals(79.0,corrected.weightKg,0.0)
  assertEquals(183.0,corrected.heightCm,0.0)
 }

 @Test fun latestWeightChangesOnlyWeightAndUnchangedFieldsInCorrectionStayManual() {
  val body=Measurement(id="body",dateMs=1000L,values=mapOf("weight" to 75.0,"height" to 180.0))
  val manual=Profile(id="owner",name="Owner").withMeasurement(body).copy(heightCm=183.0)
  val corrected=manual.withMeasurement(body.copy(values=body.values+("weight" to 76.0)),body.id)
  assertEquals(76.0,corrected.weightKg,0.0)
  assertEquals(183.0,corrected.heightCm,0.0)
  val newest=corrected.withMeasurement(Measurement(id="new",dateMs=2000L,values=mapOf("weight" to 80.0)))
  assertEquals(80.0,newest.weightKg,0.0)
  assertEquals(183.0,newest.heightCm,0.0)
 }

 @Test fun historicalBodyInsertAndCorrectionNeverRollBackNewerValues() {
  val old=Measurement(id="old",dateMs=1000L,values=mapOf("weight" to 75.0,"height" to 180.0))
  val latest=Measurement(id="latest",dateMs=3000L,values=mapOf("weight" to 80.0,"height" to 185.0))
  val p=Profile(id="owner",name="Owner").withMeasurement(old).withMeasurement(latest)
  val corrected=p.withMeasurement(old.copy(values=mapOf("weight" to 74.0,"height" to 179.0)),old.id)
  assertEquals(p.copy(measurements=corrected.measurements),corrected)
  val backfilled=corrected.withMeasurement(Measurement(id="backfill",dateMs=2000L,values=old.values))
  assertEquals(p.copy(measurements=backfilled.measurements),backfilled)
 }

 @Test fun latestHistoricalCorrectionDoesNotOverrideManualValuesOrRestoreRemovedFields() {
  val old=Measurement(id="old",dateMs=1000L,values=mapOf("weight" to 75.0,"height" to 180.0))
  val manual=Profile(id="owner",name="Owner").withMeasurement(old).copy(weightKg=79.0,heightCm=183.0)
  val corrected=manual.withMeasurement(old.copy(values=mapOf("weight" to 74.0,"height" to 179.0)),old.id)
  assertEquals(manual.copy(measurements=corrected.measurements),corrected)
  val removed=manual.withMeasurement(old.copy(values=mapOf("waist" to 80.0)),old.id)
  assertEquals(manual.copy(measurements=removed.measurements),removed)
 }

 @Test fun latestHeightChangesOnlyHeight() {
  val p=Profile(id="owner",name="Owner",weightKg=79.0)
  val height=Measurement(id="height",dateMs=1000L,values=mapOf("height" to 182.0))
  val inserted=p.withMeasurement(height)
  assertEquals(79.0,inserted.weightKg,0.0)
  assertEquals(182.0,inserted.heightCm,0.0)
  val corrected=inserted.withMeasurement(height.copy(values=mapOf("height" to 184.0)),height.id)
  assertEquals(79.0,corrected.weightKg,0.0)
  assertEquals(184.0,corrected.heightCm,0.0)
 }
 @Test fun equalTimestampHasNoAuthorityToReplaceCurrentBodyValue() {
  val first=Measurement(id="first",dateMs=1000L,values=mapOf("weight" to 75.0))
  val p=Profile(id="owner",name="Owner").withMeasurement(first)
  val tied=p.withMeasurement(first.copy(id="tied",values=mapOf("weight" to 74.0)))
  assertEquals(75.0,tied.weightKg,0.0)
  val corrected=tied.withMeasurement(first.copy(values=mapOf("weight" to 73.0)),first.id)
  assertEquals(75.0,corrected.weightKg,0.0)
 }

 @Test fun correctionCannotMoveOriginalRecordDate() {
  val first=Measurement(id="first",dateMs=1000L,values=mapOf("weight" to 75.0))
  val p=Profile(id="owner",name="Owner").withMeasurement(first)
  assertTrue(runCatching{p.withMeasurement(first.copy(dateMs=2000L),first.id)}
   .exceptionOrNull() is IllegalArgumentException)
 }
 @Test fun addingBodyFieldsToHistoricalWaistDoesNotOverrideManualProfile() {
  val waist=Measurement(id="waist",dateMs=1000L,values=mapOf("waist" to 80.0))
  val manual=Profile(id="owner",name="Owner").withMeasurement(waist).copy(weightKg=79.0,heightCm=183.0)
  val edited=waist.copy(values=waist.values+mapOf("weight" to 75.0,"height" to 180.0))
  val corrected=manual.withMeasurement(edited,waist.id)
  assertEquals(manual.copy(measurements=listOf(edited)),corrected)
 }
}
