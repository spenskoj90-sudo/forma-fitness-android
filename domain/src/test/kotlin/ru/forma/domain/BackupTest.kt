package ru.forma.domain
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
}
