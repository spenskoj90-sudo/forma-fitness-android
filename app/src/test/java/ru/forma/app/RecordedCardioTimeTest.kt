package ru.forma.app

import org.junit.Assert.*
import org.junit.Test
import ru.forma.domain.*
import java.time.LocalDate
import java.time.ZoneId

class RecordedCardioTimeTest {
 private fun workout(id:String,day:TrainingDay,status:Phase,active:Int,finishedMs:Long)=
  Workout(id=id,day=day,startedMs=finishedMs-60000L,finishedMs=finishedMs,
   sets=emptyList(),status=status,activeSeconds=active)

 @Test fun summariesUseRecordedTimeIncludingStoppedCardioNotScheduledDuration() {
  val now=LocalDate.of(2026,11,4).atTime(12,0)
   .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
  val cardio=TrainingDay(day=1,name="Кардио",cardio=CardioKind.BRISK,durationSec=1800)
  val strength=TrainingDay(day=2,name="Силовая",durationSec=3600)
  val history=listOf(
   workout("done",cardio,Phase.DONE,540,now-300000L),
   workout("stopped",cardio,Phase.STOPPED,150,now-240000L),
   workout("strength",strength,Phase.DONE,360,now-180000L)
  )
  assertEquals(690L,recordedCardioSessionActiveSeconds(history))
  val report=weeklyReport(Profile(name="Синтетический профиль",history=history),now)
  assertTrue(report.contains("Активное время тренировок с кардио: 11 мин 30 сек"))
  assertFalse(report.contains("Кардио: 60 минут"))
  assertTrue(report.contains("Завершено: 2"))
  assertTrue(report.contains("Остановлено: 1"))
 }

 @Test fun emptyAndNonCardioHistoryDoNotInventCardioTime() {
  assertEquals(0L,recordedCardioSessionActiveSeconds(emptyList()))
  val strength=TrainingDay(day=1,name="Силовая",durationSec=1200)
  assertEquals(0L,recordedCardioSessionActiveSeconds(
   listOf(workout("strength",strength,Phase.DONE,240,100000L))))
 }
}
