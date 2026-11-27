package ru.forma.domain

import org.junit.Assert.*
import org.junit.Test

class TechniqueCatalogTest {
 @Test fun findsExercisesByNameEquipmentMuscleAndSafetyTextOffline(){
  val available=setOf(Equipment.BODYWEIGHT,Equipment.RESISTANCE_BAND)
  val bands=findTechniqueExercises(exerciseCatalog,available,query="резинка")
  assertTrue(bands.isNotEmpty())
  assertTrue(bands.all{it.equipment==Equipment.RESISTANCE_BAND})
  assertTrue(bands.any{it.id=="band_row"})
  assertEquals(listOf("band_row"),findTechniqueExercises(
   exerciseCatalog,available,muscle=Muscle.BACK,query="ТЯГА   РЕЗИНКИ")
   .map{it.id})
  assertTrue(findTechniqueExercises(exerciseCatalog,available,query="рывки").isNotEmpty())
  assertFalse(findTechniqueExercises(exerciseCatalog,available,query="фантастическое движение").isNotEmpty())
 }

 @Test fun unavailableEquipmentIsNeverShownWithoutExplicitOptIn(){
  val available=setOf(Equipment.BODYWEIGHT)
  val local=findTechniqueExercises(exerciseCatalog,available,query="жим ногами")
  assertTrue(local.isEmpty())
  val all=findTechniqueExercises(exerciseCatalog,available,includeUnavailable=true,query="жим ногами")
  assertEquals(listOf("leg_press"),all.map{it.id})
  assertEquals(exerciseCatalog.size,
   findTechniqueExercises(exerciseCatalog,available,includeUnavailable=true).size)
 }

 @Test fun searchingDoesNotMutateCatalogProfilesOrStoredExerciseIdentity(){
  val profile=Profile(id="local",name="Synthetic")
  val before=exerciseCatalog.toList()
  val found=findTechniqueExercises(exerciseCatalog,profile.equipment,includeUnavailable=true,
   muscle=Muscle.CHEST,query="плечи")
  assertTrue(found.all{it.muscle==Muscle.CHEST})
  assertEquals(before,exerciseCatalog)
  assertEquals(setOf(Equipment.BODYWEIGHT),profile.equipment)
 }
}
