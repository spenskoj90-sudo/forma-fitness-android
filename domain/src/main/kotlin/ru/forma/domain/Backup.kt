package ru.forma.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

const val MAX_BACKUP_BYTES=8*1024*1024
@Serializable data class Backup(val version:Int=1,val profiles:List<Profile>)
private val backupJson=Json{encodeDefaults=true;ignoreUnknownKeys=false}

private fun validDay(d:TrainingDay)=d.day in 1..7 && d.name.length in 1..200 &&
 d.durationSec in 60..7200 && d.intervals.size in 2..20 && d.intervals.all{it in 10..1800} &&
 (d.cardio!=null || d.exercises.isNotEmpty()) && d.exercises.size<=20 &&
 d.exercises.all{it.sets in 1..10 && it.reps in 1..100 && it.restSec in 0..600 && it.weight.isFinite() && it.weight in 0.0..500.0 && it.exercise.id in exerciseCatalog.map{e->e.id}}

fun validateBackup(b:Backup){
 require(b.version==1){"Неподдерживаемая версия резервной копии"}
 require(b.profiles.size in 1..20){"Копия должна содержать от 1 до 20 профилей"}
 require(b.profiles.map{it.id}.distinct().size==b.profiles.size){"Повторяющийся профиль"}
 b.profiles.forEach{p->
  require(p.valid() && p.id.length in 1..100 && p.limitations.length<=2000 && p.active==null){"Завершите занятие перед резервным копированием"}
  require(p.measurements.size<=10000 && p.history.size<=10000){"Слишком много записей"}
  require(p.measurements.map{it.id}.distinct().size==p.measurements.size && p.history.map{it.id}.distinct().size==p.history.size){"Повторяющиеся записи"}
  require(p.measurements.all{it.id.length in 1..100 && it.dateMs>=0 && it.valid()}){"Некорректные замеры"}
  p.history.forEach{w->
   require(w.id.length in 1..100 && w.startedMs>=0 && w.finishedMs>=w.startedMs && validDay(w.day)) {"Некорректная тренировка"}
   require(w.status in setOf(Phase.DONE,Phase.STOPPED) && w.activeSeconds in 0..86400 && w.sets.size<=200)
   require(w.sets.all{s->s.exerciseId in w.day.exercises.map{it.exercise.id} && s.reps in 1..100 && s.weight.isFinite() && s.weight in 0.0..500.0 && s.effort in 1..10 && s.timeMs in w.startedMs..w.finishedMs}){"Некорректные подходы"}
   require(w.status!=Phase.DONE || w.day.cardio!=null || w.sets.size==w.day.exercises.sumOf{it.sets}){"Неполное занятие не может быть завершённым"}
  }
 }
}
fun encodeBackup(b:Backup):String{validateBackup(b);return backupJson.encodeToString(b).also{require(it.toByteArray(Charsets.UTF_8).size<=MAX_BACKUP_BYTES){"Копия слишком большая"}}}
fun decodeBackup(text:String):Backup{
 require(text.length<=MAX_BACKUP_BYTES && text.toByteArray(Charsets.UTF_8).size<=MAX_BACKUP_BYTES){"Файл превышает 8 МБ"}
 return try{backupJson.decodeFromString<Backup>(text).also(::validateBackup)}catch(e:Exception){throw IllegalArgumentException("Копия повреждена или её версия не поддерживается",e)}
}
