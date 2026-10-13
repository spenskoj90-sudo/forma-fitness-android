package ru.forma.domain
val exerciseCatalog=listOf(
 Exercise("squat","Приседание",Muscle.LEGS,Equipment.BODYWEIGHT,"Стопы устойчиво. Колени по направлению носков. Комфортная амплитуда, плавный подъём."),
 Exercise("wall_push","Отжимание от стены",Muscle.CHEST,Equipment.BODYWEIGHT,"Ладони на уровне груди. Корпус прямой. Плавно приблизьтесь к стене и оттолкнитесь без рывка."),
 Exercise("bird_dog","Птица-собака",Muscle.CORE,Equipment.BODYWEIGHT,"На четвереньках вытяните противоположные руку и ногу. Таз ровный, поясницу не прогибайте. Чередуйте стороны."),
 Exercise("prone_w","Подъём рук W лёжа",Muscle.BACK,Equipment.BODYWEIGHT,"Лёжа на животе, согните руки буквой W. Мягко сведите лопатки и чуть поднимите руки. Шея нейтральна, без прогиба и боли."),
 Exercise("calf","Подъём на носки",Muscle.LEGS,Equipment.BODYWEIGHT,"Держитесь за устойчивую опору. Плавно поднимитесь на носки и опуститесь без пружинящих движений."),
 Exercise("goblet","Приседание с гантелью",Muscle.LEGS,Equipment.DUMBBELL,"Гантель у груди. Корпус устойчив, колени по направлению носков. Комфортная амплитуда без боли."),
 Exercise("db_row","Тяга гантелей в наклоне",Muscle.BACK,Equipment.DUMBBELL,"Наклон от таза, колени мягко согнуты. Спина нейтральна. Ведите локти назад без рывка, плавно опускайте вес."),
 Exercise("floor_press","Жим гантелей на полу",Muscle.CHEST,Equipment.DUMBBELL,"Стопы устойчивы. Локти под углом к корпусу. Плавный жим; опускайте до мягкого касания пола."),
 Exercise("raise","Подъём гантелей в стороны",Muscle.SHOULDERS,Equipment.DUMBBELL,"Очень лёгкий вес. Локти слегка согнуты, плечи опущены. Не раскачивайтесь, поднимайте до комфортной высоты."),
 Exercise("curl","Сгибание рук с гантелями",Muscle.ARMS,Equipment.DUMBBELL,"Локти около корпуса. Без раскачивания. Запястья ровные, плавное опускание."),
 Exercise("bb_row","Тяга штанги в наклоне",Muscle.BACK,Equipment.BARBELL,"Наклон от таза, мягкие колени, спина нейтральна. Тяните к низу живота без рывка, начинайте с лёгкого веса.")
)
fun suggestWeight(previous:Double,minReps:Int,target:Int,effort:Int,pain:Boolean,recovery:Int,returning:Boolean):Double{
 if(!previous.isFinite()||previous<0)return 0.0
 if(pain||recovery<3)return previous
 if(returning)return kotlin.math.round(previous*0.7*10)/10
 return if(minReps>=target&&effort<=7&&previous>0)kotlin.math.round(previous*1.05*10)/10 else previous
}
fun generateProgram(p:Profile,now:Long=System.currentTimeMillis()):List<TrainingDay>{
 if(!p.valid()||p.limitations.isNotBlank()||p.recovery<=1||p.active?.painReported==true)return emptyList()
 val available=exerciseCatalog.filter{it.equipment in p.equipment||it.equipment==Equipment.BODYWEIGHT};val strengthDays=mutableListOf<Int>()
 for(d in p.days.sorted())if(strengthDays.size<3&&strengthDays.none{val gap=kotlin.math.abs(d-it);gap<=1||gap==6})strengthDays+=d
 val returning=p.history.maxOfOrNull{it.finishedMs}?.let{now-it>21L*86400000}?:true
 val reps=when(p.primary){Goal.STRENGTH->8;Goal.ENDURANCE->15;else->12};val sets=if(p.experience==Experience.BEGINNER||returning)2 else 3
 val muscles=(listOf(Muscle.LEGS,Muscle.BACK,Muscle.CHEST,Muscle.CORE)+p.priorities.sortedBy{it.ordinal}).distinct()
 return p.days.sorted().map{d->if(d !in strengthDays)TrainingDay(d,"Лёгкое кардио",cardio=CardioKind.WALK,durationSec=if(p.primary in setOf(Goal.ENDURANCE,Goal.FAT_LOSS)||p.secondary in setOf(Goal.ENDURANCE,Goal.FAT_LOSS))1800 else 1200,explanation="Ходьба между силовыми днями; длительность и интервалы настраиваются.")
 else TrainingDay(d,"Силовая · всё тело",exercises=muscles.mapNotNull{m->
  val ex=available.filter{it.muscle==m}.maxByOrNull{when(it.equipment){Equipment.DUMBBELL->2;Equipment.BARBELL->1;else->0}}?:return@mapNotNull null
  val last=p.history.sortedByDescending{it.finishedMs}.firstOrNull{it.status==Phase.DONE&&it.sets.any{s->s.exerciseId==ex.id}}?.sets?.filter{it.exerciseId==ex.id}.orEmpty()
  val previous=last.lastOrNull()?.weight?:0.0;val weight=suggestWeight(previous,if(last.size>=sets)last.minOf{it.reps}else 0,reps,last.maxOfOrNull{it.effort}?:10,last.any{it.pain},p.recovery,returning)
  Prescription(ex,sets,reps,weight,if(p.primary==Goal.STRENGTH)120 else 90,"${p.primary.label}; ${p.experience.label}. "+if(returning)"Возвращение: 2 подхода, 70% прошлого веса."else if(weight>previous)"Все подходы выполнены: предлагается +5%, подтвердите вес."else"Вес сохранён, корректируйте по самочувствию.")
 },explanation="Всё тело и доступные акценты. Силовые дни разделены отдыхом. Вес: на одну гантель или общий вес штанги.")}
}
