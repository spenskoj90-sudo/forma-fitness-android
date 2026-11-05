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

 @Test fun duplicateMeasurementDoesNotMixDistinctProfiles() {
  val first=Measurement(id="shared-id",dateMs=1000L,values=mapOf("weight" to 82.0))
  val a=Profile(id="a",name="A").withMeasurement(first)
  val b=Profile(id="b",name="B").withMeasurement(first.copy(values=mapOf("weight" to 65.0)))
  assertEquals(82.0,a.weightKg,0.0)
  assertEquals(65.0,b.weightKg,0.0)
  assertNotEquals(a.id,b.id)
 }
}
