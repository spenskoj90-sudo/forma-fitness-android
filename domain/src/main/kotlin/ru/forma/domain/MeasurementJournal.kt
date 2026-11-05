package ru.forma.domain

/**
 * Persist one measurement per editor draft UUID, not per tap/coroutine.
 * An exact retry is a no-op; an equal ID with different content is a conflict.
 * Existing records can only be edited by their original ID and date.
 *
 * Profile weight/height are current, independently editable values, not a view
 * derived from the journal. Only a changed, supplied field can update them.
 * A new entry must be strictly newer than every other entry for that field;
 * equal timestamps have no unambiguous authority. A correction must additionally
 * contain the field already and match the profile's old value, preserving
 * subsequent manual overrides. Newly added historical fields are journal-only.
 * Removing a historical field never restores an older value into the profile.
 * Without provenance metadata, a manual value equal to the old journal value
 * is indistinguishable from that value; such a latest-entry correction applies.
 */
fun Profile.withMeasurement(item:Measurement,editingId:String?=null):Profile {
 require(item.valid()){"Некорректные значения замера"}
 val found=measurements.firstOrNull{it.id==item.id}
 if(found==item && (editingId==null || editingId==item.id))return this
 val records=if(editingId!=null) {
  require(editingId==item.id&&found!=null){"Замер больше не существует"}
  require(item.dateMs==found.dateMs){"Дата исходного замера не должна изменяться"}
  measurements.map{if(it.id==editingId)item else it}
 } else when {
  found==null->measurements+item
  else->throw IllegalArgumentException("Черновик замера уже сохранён с другими значениями")
 }
 fun currentValue(key:String,current:Double):Double {
  val next=item.values[key]?:return current
  if(found?.values?.get(key)==next)return current
  if(measurements.any{it.id!=item.id && it.dateMs>=item.dateMs && key in it.values})return current
  if(found!=null && found.values[key]!=current)return current
  return next
 }
 return copy(measurements=records,
  weightKg=currentValue("weight",weightKg),
  heightCm=currentValue("height",heightCm))
}
