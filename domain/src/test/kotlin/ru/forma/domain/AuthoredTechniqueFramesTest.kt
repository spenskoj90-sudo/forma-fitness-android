package ru.forma.domain

import org.junit.Assert.*
import org.junit.Test

class AuthoredTechniqueFramesTest {
 @Test fun everyExistingExerciseHasFourDistinctOfflineStages() {
  assertEquals(37, exerciseCatalog.size)
  assertEquals(37, exerciseCatalog.map { it.id }.toSet().size)
  exerciseCatalog.forEach { exercise ->
   val frames = authoredTechniqueFrames(exercise)
   assertEquals("Missing author storyboard for ${exercise.id}", 4, frames.size)
   assertEquals(listOf("1 · Подготовка", "2 · Движение", "3 · Возврат", "4 · Самопроверка"), frames.map { it.title })
   assertTrue(frames.all { it.instruction.isNotBlank() })
   assertTrue(frames[3].instruction.contains(exercise.mistakes))
  }
 }
 @Test fun mismatchedEquipmentAndUnknownIdsNeverReuseAnotherExerciseStoryboard() {
  val squat = exerciseCatalog.first { it.id == "squat" }
  assertTrue(authoredTechniqueFrames(squat.copy(equipment = Equipment.BARBELL)).isEmpty())
  assertTrue(authoredTechniqueFrames(squat.copy(id = "unlisted")).isEmpty())
  assertNotEquals(authoredTechniqueFrames(squat)[1].instruction,
   authoredTechniqueFrames(exerciseCatalog.first { it.id == "wall_push" })[1].instruction)
 }
}
