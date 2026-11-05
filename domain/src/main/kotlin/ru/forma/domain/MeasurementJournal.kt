package ru.forma.domain

/**
 * Persist one measurement per editor draft UUID, not per tap/coroutine.
 * An exact retry is a no-op; an equal ID with different content is a conflict.
 * Existing records can only be edited by their original ID.
 */
fun Profile.withMeasurement(item:Measurement,editingId:String?=null):Profile {
 require(item.valid()){"Некорректные значения замера"}
 val found=measurements.firstOrNull{it.id==item.id}
 // The same committed record may be replayed after the user has separately
 // changed profile height or weight. An exact retry must not recompute them
 // from an older journal entry and overwrite the later profile edit.
 if(found==item && (editingId==null || editingId==item.id))return this
 val records=if(editingId!=null) {
  require(editingId==item.id&&found!=null){"Замер больше не существует"}
  measurements.map{if(it.id==editingId)item else it}
 } else when {
  found==null->measurements+item
  else->throw IllegalArgumentException("Черновик замера уже сохранён с другими значениями")
 }
 val ordered=records.sortedBy{it.dateMs}
 return copy(measurements=records,
  weightKg=ordered.lastOrNull{it.values.containsKey("weight")}?.values?.get("weight")?:weightKg,
  heightCm=ordered.lastOrNull{it.values.containsKey("height")}?.values?.get("height")?:heightCm)
}
