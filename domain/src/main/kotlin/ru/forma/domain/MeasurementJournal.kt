package ru.forma.domain

/**
 * Persist one measurement per editor draft UUID, not per tap/coroutine.
 * An exact retry is a no-op; an equal ID with different content is a conflict.
 * Existing records can only be edited by their original ID.
 */
fun Profile.withMeasurement(item:Measurement,editingId:String?=null):Profile {
 require(item.valid()){"Некорректные значения замера"}
 val found=measurements.firstOrNull{it.id==item.id}
 val records=if(editingId!=null) {
  require(editingId==item.id&&found!=null){"Замер больше не существует"}
  measurements.map{if(it.id==editingId)item else it}
 } else when {
  found==null->measurements+item
  found==item->measurements
  else->throw IllegalArgumentException("Черновик замера уже сохранён с другими значениями")
 }
 val ordered=records.sortedBy{it.dateMs}
 return copy(measurements=records,
  weightKg=ordered.lastOrNull{it.values.containsKey("weight")}?.values?.get("weight")?:weightKg,
  heightCm=ordered.lastOrNull{it.values.containsKey("height")}?.values?.get("height")?:heightCm)
}
