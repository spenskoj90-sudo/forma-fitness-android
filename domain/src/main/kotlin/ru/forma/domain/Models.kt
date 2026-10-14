package ru.forma.domain
import kotlinx.serialization.Serializable
import java.util.UUID
@Serializable enum class Goal(val label:String){MUSCLE("Мышечная масса"),STRENGTH("Сила"),ENDURANCE("Выносливость"),FITNESS("Общая форма"),FAT_LOSS("Снижение жировой массы")}
@Serializable enum class Equipment(val label:String){BODYWEIGHT("Без оборудования"),DUMBBELL("Гантели"),BARBELL("Штанга")}
@Serializable enum class Experience(val label:String){BEGINNER("Начинающий"),INTERMEDIATE("Средний"),ADVANCED("Опытный")}
@Serializable enum class Muscle(val label:String){LEGS("Ноги"),BACK("Спина"),CHEST("Грудь"),SHOULDERS("Плечи"),ARMS("Руки"),CORE("Корпус")}
@Serializable enum class CardioKind(val label:String){WALK("Ходьба"),BRISK("Быстрая ходьба"),JOG("Лёгкий бег"),INTERVAL("Ходьба / бег")}
@Serializable data class Profile(val id:String=UUID.randomUUID().toString(),val name:String="",val age:Int=30,val heightCm:Double=170.0,val weightKg:Double=70.0,val experience:Experience=Experience.BEGINNER,val equipment:Set<Equipment> = setOf(Equipment.BODYWEIGHT,Equipment.DUMBBELL,Equipment.BARBELL),val limitations:String="",val primary:Goal=Goal.MUSCLE,val secondary:Goal?=Goal.FITNESS,val priorities:Set<Muscle> = emptySet(),val days:Set<Int> = setOf(1,2,3,5,6),val recovery:Int=3,val history:List<Workout> = emptyList(),val measurements:List<Measurement> = emptyList(),val active:Session?=null){fun valid()=name.isNotBlank()&&name.length<=80&&age in 18..100&&heightCm.isFinite()&&heightCm in 100.0..240.0&&weightKg.isFinite()&&weightKg in 30.0..300.0&&limitations.length<=2000&&recovery in 1..5&&days.all{it in 1..7}}
@Serializable data class Exercise(val id:String,val name:String,val muscle:Muscle,val equipment:Equipment,val cue:String,val mistakes:String="",val alternatives:List<String> = emptyList())
@Serializable data class Prescription(val exercise:Exercise,val sets:Int,val reps:Int,val weight:Double=0.0,val restSec:Int=90,val reason:String="")
@Serializable data class TrainingDay(val day:Int,val name:String,val exercises:List<Prescription> = emptyList(),val cardio:CardioKind?=null,val durationSec:Int=1200,val intervals:List<Int> = listOf(120,60),val explanation:String="")
@Serializable data class SetResult(val exerciseId:String,val reps:Int,val weight:Double,val effort:Int,val pain:Boolean,val timeMs:Long)
@Serializable data class Workout(val id:String,val day:TrainingDay,val startedMs:Long,val finishedMs:Long,val sets:List<SetResult>,val status:Phase,val activeSeconds:Int=0,val painReported:Boolean=false)
@Serializable data class Measurement(val id:String=UUID.randomUUID().toString(),val dateMs:Long,val values:Map<String,Double>)
val measurementLabels=linkedMapOf("weight" to "Вес, кг","height" to "Рост, см","chest" to "Грудь, см","waist" to "Талия, см","hips" to "Бёдра, см","shoulders" to "Плечи, см","biceps" to "Бицепсы, см","forearms" to "Предплечья, см","neck" to "Шея, см","calves" to "Икры, см")
fun Measurement.valid()=values.isNotEmpty()&&values.all{(k,v)->k in measurementLabels&&v.isFinite()&&when(k){"weight"->v in 30.0..300.0;"height"->v in 100.0..240.0;else->v>0&&v<=300}}
