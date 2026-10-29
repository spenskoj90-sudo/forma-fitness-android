package ru.forma.app

import org.junit.Assert.*
import org.junit.Test

class ChartSummaryTest {
 @Test fun datedMeasurementsRemainChronologicalWithAccessibleUnits() {
  val points=listOf(ChartSample(2000L,84.0),ChartSample(1000L,80.0))
  val summary=chartSummary(points,"Вес, кг","кг")
  assertTrue(summary.contains("2 значений"))
  assertTrue(summary.contains("Первое"))
  assertTrue(summary.indexOf("80.0")<summary.indexOf("84.0"))
  assertTrue(summary.contains("Минимум 80.0"))
  assertTrue(summary.contains("максимум 84.0"))
  assertTrue(summary.contains("кг"))
 }
 @Test fun emptyAndInvalidChartValuesDoNotInventData() {
  assertTrue(chartSummary(emptyList(),"Талия","см").contains("нет сохранённых значений"))
  val text=chartSummary(listOf(ChartSample(1000L,Double.NaN),ChartSample(2000L,91.0)),"Талия","см")
  assertTrue(text.contains("1 значений"))
  assertFalse(text.contains("NaN"))
 }
}
