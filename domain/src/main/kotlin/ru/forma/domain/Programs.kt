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
 Exercise("bb_row","Тяга штанги в наклоне",Muscle.BACK,Equipment.BARBELL,"Наклон от таза, мягкие колени, спина нейтральна. Тяните к низу живота без рывка, начинайте с лёгкого веса."),
 Exercise("chair_squat","Приседание к стулу",Muscle.LEGS,Equipment.BODYWEIGHT,"Поставьте устойчивый стул у стены. Отведите таз назад, мягко коснитесь сиденья и встаньте. Колени следуют носкам.","Падение на сиденье, отрыв пяток, неустойчивый стул.",listOf("squat","goblet")),
 Exercise("bridge","Ягодичный мост",Muscle.LEGS,Equipment.BODYWEIGHT,"Лёжа на спине, согните колени и поставьте стопы на пол. Поднимите таз до прямой линии плечи–таз–колени, плавно опустите.","Переразгибание поясницы, рывок, задержка дыхания.",listOf("chair_squat")),
 Exercise("incline_push","Отжимание от высокой опоры",Muscle.CHEST,Equipment.BODYWEIGHT,"Упритесь в устойчивую закреплённую опору. Корпус прямой; плавно согните локти и оттолкнитесь. Начинайте с высокой опоры.","Скользящая опора, провисание поясницы, плечи у ушей.",listOf("wall_push","floor_press")),
 Exercise("dead_bug","Мёртвый жук",Muscle.CORE,Equipment.BODYWEIGHT,"Лёжа на спине, поднимите согнутые ноги. Медленно вытягивайте противоположные руку и ногу в комфортной амплитуде. Чередуйте стороны.","Прогиб поясницы, быстрые движения, задержка дыхания.",listOf("bird_dog")),
 Exercise("db_rdl","Румынская тяга с гантелями",Muscle.LEGS,Equipment.DUMBBELL,"Колени мягко согнуты. Отведите таз назад, ведите лёгкие гантели близко к ногам. Спина нейтральна; выпрямляйтесь движением таза.","Округление спины, превращение в присед, чрезмерная глубина.",listOf("goblet","bridge")),
 Exercise("hammer","Молотковое сгибание",Muscle.ARMS,Equipment.DUMBBELL,"Ладони направлены друг к другу. Локти около корпуса; плавно поднимите гантели и опустите без раскачивания.","Рывок корпусом, залом запястий, движение локтей вперёд.",listOf("curl")),
 Exercise("db_press","Жим лёгких гантелей стоя",Muscle.SHOULDERS,Equipment.DUMBBELL,"Стопы устойчивы, корпус собран. Плавно поднимайте лёгкие гантели в комфортной амплитуде, без выгибания поясницы.","Запрокидывание головы, прогиб поясницы, работа через боль.",listOf("raise")),
 Exercise("bb_rdl","Румынская тяга со штангой",Muscle.LEGS,Equipment.BARBELL,"Начните с лёгкого грифа. Мягкие колени, таз назад; гриф близко к ногам. Спина нейтральна. Не стремитесь опустить гриф к полу.","Округление спины, слишком тяжёлый вес, рывок.",listOf("squat","db_rdl")),
 Exercise("bb_curl","Сгибание рук со штангой",Muscle.ARMS,Equipment.BARBELL,"Лёгкий гриф, устойчивое положение. Локти рядом с корпусом, запястья ровные. Поднимайте и опускайте без рывка.","Раскачивание, залом запястий, движение плечами.",listOf("curl","hammer")),
 Exercise("wall_slide","Скольжение руками по стене",Muscle.SHOULDERS,Equipment.BODYWEIGHT,"Спиной к стене, рёбра опущены. Ведите руки вверх только пока плечам комфортно. Плавно вернитесь.","Прогиб в пояснице, поднятые к ушам плечи."),
 Exercise("self_curl","Сгибание руки с сопротивлением другой руки",Muscle.ARMS,Equipment.BODYWEIGHT,"Одной рукой плавно сгибайте локоть, другой создавайте небольшое контролируемое сопротивление. Не задерживайте дыхание.","Рывки, давление на запястье, работа через боль."),
 Exercise("band_row","Тяга резинки к поясу",Muscle.BACK,Equipment.RESISTANCE_BAND,"Закрепите резинку на надёжной опоре. Тяните локти назад, не округляя спину; плавно отпускайте.","Ненадёжная точка крепления, рывки, чрезмерное натяжение."),
 Exercise("band_press","Жим резинки вперёд",Muscle.CHEST,Equipment.RESISTANCE_BAND,"Надёжно зафиксируйте резинку позади себя. Выжимайте руки вперёд на комфортную амплитуду, без рывка.","Резинка скользит, локти слишком широко, прогиб поясницы."),
 Exercise("band_extension","Разгибание рук с резинкой",Muscle.ARMS,Equipment.RESISTANCE_BAND,"Удерживайте локти возле корпуса. Медленно выпрямляйте руки с лёгким сопротивлением.","Размахивание плечами, резкие движения."),
 Exercise("kettlebell_squat","Приседание с гирей у груди",Muscle.LEGS,Equipment.KETTLEBELL,"Держите лёгкую гирю у груди двумя руками. Контролируйте колени и устойчивость стоп.","Округление спины, уход коленей внутрь."),
 Exercise("kettlebell_deadlift","Тяга гири от возвышения",Muscle.LEGS,Equipment.KETTLEBELL,"Начните с гири на устойчивом возвышении. Отведите таз назад, удерживайте спину нейтральной и встаньте без рывка.","Избыточный вес, округление спины, рывок."),
 Exercise("scap_pull","Контролируемое движение лопаток на турнике",Muscle.BACK,Equipment.PULL_UP_BAR,"Возьмитесь за устойчивый турник с доступной опорой под ногами. Слегка опустите лопатки, затем вернитесь без полного провисания.","Неустойчивая опора, резкое раскачивание, движение через боль."),
 Exercise("mat_plank","Планка на предплечьях",Muscle.CORE,Equipment.MAT,"Предплечья под плечами, тело вытянуто. Держите комфортную короткую продолжительность, спокойно дышите.","Провисание таза, задержка дыхания."),
 Exercise("bench_step","Подъём на низкую устойчивую платформу",Muscle.LEGS,Equipment.BENCH,"Используйте только низкую нескользящую ступень. Поднимайтесь контролируемо, держитесь за опору при необходимости.","Шаткая или высокая скамья, прыжки, потеря равновесия."),
 Exercise("cable_triceps","Разгибание рук на блоке",Muscle.ARMS,Equipment.CABLE_MACHINE,"Локти у корпуса, разгибайте руки без помощи спины. Верните рукоять плавно.","Слишком большой вес, рывок корпусом."),
 Exercise("lat_pulldown","Тяга верхнего блока к груди",Muscle.BACK,Equipment.LAT_PULLDOWN,"Устойчиво сядьте, тяните рукоять к верхней части груди, направляя локти вниз. Не тяните за голову.","Потягивание за шею, чрезмерное отклонение, рывок."),
 Exercise("leg_press","Жим ногами в тренажёре",Muscle.LEGS,Equipment.LEG_PRESS_MACHINE,"Поясница и таз опираются на сиденье. Разгибайте колени без блокировки, опускайте платформу в комфортной амплитуде.","Отрыв таза, глубокое сгибание с округлением поясницы, блокировка коленей."),
 Exercise("chest_press","Жим от груди в тренажёре",Muscle.CHEST,Equipment.CHEST_PRESS_MACHINE,"Отрегулируйте сиденье: рукояти на уровне груди. Жмите плавно, лопатки опираются на спинку.","Рывки, слишком высокое сиденье, избыточная амплитуда."),
 Exercise("machine_row","Горизонтальная тяга сидя",Muscle.BACK,Equipment.SEATED_ROW_MACHINE,"Сидите устойчиво, тяните локти назад, сохраняя корпус неподвижным. Плавно выпрямите руки.","Раскачивание корпусом, округление спины, рывок."),
 Exercise("leg_curl","Сгибание ног в тренажёре",Muscle.LEGS,Equipment.LEG_CURL_MACHINE,"Настройте ось тренажёра под колено. Сгибайте ноги плавно с удобной амплитудой.","Отрыв таза, удар по ограничителю, чрезмерный вес."),
 Exercise("smith_squat","Приседание в тренажёре Смита",Muscle.LEGS,Equipment.SMITH_MACHINE,"Установите страховочные упоры и комфортную высоту грифа. Приседайте плавно, сохраняя устойчивость.","Нет упоров, неподходящая траектория, работа через боль.")

).map{e->if(e.mistakes.isNotBlank())e else e.copy(mistakes=when(e.id){
 "squat","goblet"->"Отрыв пяток, колени внутрь, слишком глубокое движение через боль."
 "db_row","bb_row"->"Округление спины, рывок, чрезмерный вес."
 "floor_press","wall_push"->"Разведение локтей строго в стороны, рывок, задержка дыхания."
 "bird_dog"->"Вращение таза и прогиб поясницы."
 "raise","curl"->"Раскачивание корпусом, слишком тяжёлый вес, залом запястий."
 else->"Рывки, задержка дыхания и продолжение при боли."
},alternatives=exerciseAlternatives(e.id))}
private fun exerciseAlternatives(id:String)=when(id){"squat"->listOf("chair_squat","goblet");"goblet"->listOf("squat","db_rdl");"wall_push"->listOf("incline_push","floor_press");"floor_press"->listOf("wall_push","incline_push");"bird_dog"->listOf("dead_bug");"db_row"->listOf("bb_row","prone_w");"bb_row"->listOf("db_row","prone_w");"prone_w"->listOf("db_row");"raise"->listOf("db_press");"curl"->listOf("hammer","bb_curl");else->emptyList()}
fun suggestWeight(previous:Double,minReps:Int,target:Int,effort:Int,pain:Boolean,recovery:Int,returning:Boolean):Double{
 if(!previous.isFinite()||previous<0)return 0.0
 if(pain)return previous
 if(returning)return kotlin.math.round(previous*0.7*10)/10
 if(recovery<3)return previous
 return if(minReps>=target&&effort<=7&&previous>0)kotlin.math.round(previous*1.05*10)/10 else previous
}
fun generateProgram(p:Profile,now:Long=System.currentTimeMillis(),place:TrainingPlace=TrainingPlace.COMBINED):List<TrainingDay>{
 if(!p.valid()||p.limitations.isNotBlank()||p.recovery<=1||p.active?.painReported==true)return emptyList()
 val gear=availableEquipment(p,place);val available=exerciseCatalog.filter{it.equipment in gear};val strengthDays=mutableListOf<Int>()
 for(d in p.days.sorted())if(strengthDays.size<3&&strengthDays.none{val gap=kotlin.math.abs(d-it);gap<=1||gap==6})strengthDays+=d
 val returning=p.history.filter{it.status==Phase.DONE&&it.day.cardio==null}.maxOfOrNull{it.finishedMs}?.let{now-it>21L*86400000}?:true
 val reps=when(p.primary){Goal.STRENGTH->8;Goal.ENDURANCE->15;else->12};val sets=if(p.experience==Experience.BEGINNER||returning)2 else 3
 // Cardio completion cannot erase the safety signal from a stopped strength workout.
 val latestStrength=p.history.filter{it.day.cardio==null}.maxByOrNull{it.finishedMs}
 val holdProgression=latestStrength?.let{it.status!=Phase.DONE||it.painReported}==true
 val baseMuscles=if(p.primary==Goal.MUSCLE)listOf(Muscle.BACK,Muscle.CHEST,Muscle.SHOULDERS,Muscle.ARMS,Muscle.CORE,Muscle.LEGS)else listOf(Muscle.LEGS,Muscle.BACK,Muscle.CHEST,Muscle.CORE)
 val muscles=(p.priorities.sortedBy{it.ordinal}+baseMuscles).distinct()
 return p.days.sorted().map{d->if(d !in strengthDays)TrainingDay(d,"Лёгкое кардио",cardio=CardioKind.WALK,durationSec=if(p.primary in setOf(Goal.ENDURANCE,Goal.FAT_LOSS)||p.secondary in setOf(Goal.ENDURANCE,Goal.FAT_LOSS))1800 else 1200,explanation="Ходьба между силовыми днями; длительность и интервалы настраиваются.")
 else TrainingDay(d,"Силовая · всё тело",exercises=muscles.mapNotNull{m->
  val choices=available.filter{it.muscle==m}.sortedByDescending{when(it.equipment){
    Equipment.BODYWEIGHT,Equipment.MAT->0
    Equipment.RESISTANCE_BAND,Equipment.PULL_UP_BAR->1
    Equipment.DUMBBELL,Equipment.KETTLEBELL,Equipment.BENCH->2
    else->3
   }}
  val ex=choices.firstOrNull()?:return@mapNotNull null
  val last=p.history.sortedByDescending{it.finishedMs}.firstOrNull{it.status==Phase.DONE&&it.sets.any{s->s.exerciseId==ex.id}}?.sets?.filter{it.exerciseId==ex.id}.orEmpty()
  val previous=last.lastOrNull()?.weight?:0.0;val weight=suggestWeight(previous,if(last.size>=sets)last.minOf{it.reps}else 0,reps,last.maxOfOrNull{it.effort}?:10,last.any{it.pain}||holdProgression,p.recovery,returning)
  Prescription(ex,sets,reps,weight,if(p.primary==Goal.STRENGTH)120 else 90,"${p.primary.label}; ${p.experience.label}. "+if(returning)"Возвращение: 2 подхода, 70% прошлого веса."else if(holdProgression)"Последнее силовое занятие остановлено: нагрузка не повышается."else if(weight>previous)"Все подходы выполнены: предлагается +5%, подтвердите вес."else"Вес сохранён, корректируйте по самочувствию.")
 },explanation="Всё тело и доступные акценты. Место: ${place.label}. Силовые дни разделены отдыхом. Вес вводится по единице выбранного снаряда: одна гантель, вся штанга, одна гиря или показание тренажёра.")}
}
