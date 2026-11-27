package ru.forma.domain

import org.junit.Assert.*
import org.junit.Test

class RecordedLoadSemanticsTest {
 private fun exercise(id:String)=exerciseCatalog.single{it.id==id}

 @Test fun pairedAndSingleDumbbellSpecifyOneDumbbellWithoutDoubling(){
  val pair=exercise("floor_press")
  val single=exercise("goblet")
  assertTrue(recordedLoadInputLabel(pair).contains("одной гантели из пары"))
  assertEquals("Вес одной гантели, кг",recordedLoadInputLabel(single))
  assertEquals("кг на одну гантель из пары",recordedExerciseLoadUnit(pair))
  assertEquals("кг на одну гантель",recordedExerciseLoadUnit(single))
  assertEquals("10.0 кг на одну гантель из пары",recordedExerciseLoadDisplay(
   SetResult(pair.id,12,10.0,7,false,2000L),pair))
 }

 @Test fun sameEquipmentDifferentExercisesNeverMergeIntoMisleadingTonnage(){
  val pair=exercise("floor_press")
  val one=exercise("goblet")
  val band=exercise("band_row")
  val day=TrainingDay(1,"Нагрузка",exercises=listOf(
   Prescription(pair,1,12),Prescription(one,1,10),Prescription(band,1,15)
  ))
  val w=Workout("historical",day,1000L,2000L,listOf(
   SetResult(pair.id,12,10.0,7,false,1500L),
   SetResult(one.id,10,14.0,7,false,1600L),
   SetResult(band.id,15,0.0,7,false,1700L)
  ),Phase.STOPPED)
  val rows=recordedLoadVolumeByExercise(listOf(w))
  assertEquals(2,rows.size)
  assertEquals(120.0,rows.single{it.exercise.id=="floor_press"}.kgRepetitions,0.0)
  assertEquals(140.0,rows.single{it.exercise.id=="goblet"}.kgRepetitions,0.0)
  assertFalse(rows.any{it.exercise.id=="band_row"})
 }

 @Test fun plannerNeverReusesHistoricalUnmeasuredKilogramsAsNewPrescription(){
  val band=exerciseCatalog.single{it.id=="band_row"}
  val p=Profile(id="a",name="Test",
   equipment=setOf(Equipment.BODYWEIGHT,Equipment.RESISTANCE_BAND))
  val day=TrainingDay(1,"Legacy",exercises=listOf(Prescription(band,1,12)))
  val legacy=Workout("legacy",day,1000L,2000L,listOf(
   SetResult("band_row",12,30.0,7,false,1500L)),Phase.DONE)
  val program=generateProgram(p.copy(history=listOf(legacy)),now=3000L)
  val unmeasured=program.flatMap{it.exercises}
   .filter{!recordsExternalKilograms(it.exercise.equipment)}
  assertTrue(unmeasured.isNotEmpty())
  assertTrue(unmeasured.all{it.weight==0.0})
  assertTrue(unmeasured.all{it.reason.contains("кг")})
 }

 @Test fun unmeasuredBandAndBodyweightRecordZeroWithoutInventingKilograms(){
  val band=exercise("band_row")
  val body=exercise("squat")
  for(ex in listOf(band,body)){
   assertFalse(recordsExternalKilograms(ex.equipment))
   val s=skipStage(startSession("a",TrainingDay(1,"Локально",
    exercises=listOf(Prescription(ex,1,12))),1000L),1000L)
   val done=logSet(s,22.0,12,7,false,1200L)
   assertEquals(Phase.DONE,done.phase)
   assertEquals(0.0,done.sets.single().weight,0.0)
   assertFalse(recordedExerciseLoadDisplay(done.sets.single(),ex).contains("кг"))
  }
  assertTrue(unmeasuredLoadExplanation(band.equipment).contains("резинки"))
  assertEquals("22.0 кг (старая запись, нагрузка не подтверждена)",
   recordedExerciseLoadDisplay(SetResult(band.id,12,22.0,7,false,1000L),band))
 }
}
