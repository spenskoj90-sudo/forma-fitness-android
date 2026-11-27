package ru.forma.domain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.*
class BackupTest {
 private val p=Profile(id="a",name="A",measurements=listOf(Measurement(id="m",dateMs=1000,values=mapOf("weight" to 70.0))))
 @Test fun twoProfilesRoundTrip(){val b=Backup(profiles=listOf(p,p.copy(id="b",name="B")));assertEquals(b,decodeBackup(encodeBackup(b)))}
 @Test fun unsupportedVersionRejected(){assertThrows(IllegalArgumentException::class.java){decodeBackup("{\"version\":99,\"profiles\":[]}")}}
 @Test fun duplicateProfileRejected(){assertThrows(IllegalArgumentException::class.java){encodeBackup(Backup(profiles=listOf(p,p)))}}
 @Test fun activeWorkoutRejected(){assertThrows(IllegalArgumentException::class.java){encodeBackup(Backup(profiles=listOf(p.copy(active=startSession("a",generateProgram(p).first(),0)))))}}

 @Test fun invalidNestedMeasurementRejected(){
  val invalid=p.copy(measurements=listOf(Measurement(dateMs=0,values=mapOf("weight" to -1.0))))
  assertThrows(IllegalArgumentException::class.java){encodeBackup(Backup(profiles=listOf(invalid)))}
 }
 @Test fun oversizedInputRejected(){assertThrows(IllegalArgumentException::class.java){decodeBackup(" ".repeat(MAX_BACKUP_BYTES+1))}}
 @Test fun stoppedHistorySurvives(){val s=stopSession(startSession("a",generateProgram(p).first(),0));val saved=finishSession(p,s,1000);assertEquals(saved,decodeBackup(encodeBackup(Backup(profiles=listOf(saved)))).profiles.single())}
 @Test fun limitationsAreBoundedAtProfileEntry(){
  assertFalse(p.copy(limitations="x".repeat(2001)).valid())
  assertTrue(p.copy(limitations="x".repeat(2000)).valid())
 }

 @Test fun completedWorkoutRequiresThePlannedSetsForEachExercise() {
  val first = exerciseCatalog[0]
  val second = exerciseCatalog[1]
  val day = TrainingDay(1, "План", listOf(
   Prescription(first, 1, 10),
   Prescription(first, 1, 10),
   Prescription(second, 1, 10)
  ))
  val firstSet = SetResult(first.id, 10, 0.0, 5, false, 1500)
  val secondSet = firstSet.copy(exerciseId = second.id)
  val completed = Workout("workout", day, 1000, 2000,
   listOf(firstSet, firstSet, secondSet), Phase.DONE)
  val backup = Backup(profiles = listOf(p.copy(history = listOf(completed))))
  assertEquals(backup, decodeBackup(encodeBackup(backup)))

  val wrongSets = completed.copy(sets = listOf(firstSet, firstSet, firstSet))
  val malformed = Backup(profiles = listOf(p.copy(history = listOf(wrongSets))))
  assertThrows(IllegalArgumentException::class.java) { decodeBackup(Json.encodeToString(malformed)) }
 }

}
