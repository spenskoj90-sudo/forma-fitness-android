package ru.forma.app
import android.app.Application
import android.content.Context
import androidx.room.*
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import ru.forma.domain.*
@Entity(tableName="profiles") data class ProfileRow(@PrimaryKey val id:String,val name:String,val document:String)
@Dao interface ProfileDao {
 @Query("SELECT * FROM profiles ORDER BY name,id") fun observe():Flow<List<ProfileRow>>
 @Query("SELECT * FROM profiles WHERE id=:id") suspend fun get(id:String):ProfileRow?
 @Query("SELECT * FROM profiles") suspend fun all():List<ProfileRow>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(row:ProfileRow)
}
@Database(entities=[ProfileRow::class],version=1,exportSchema=false) abstract class FormaDatabase:RoomDatabase() {abstract fun profiles():ProfileDao}
class ProfileRepository(private val db:FormaDatabase) {
 private val json=Json{ignoreUnknownKeys=true;encodeDefaults=true}
 private fun decode(row:ProfileRow)=json.decodeFromString<Profile>(row.document).also{require(it.id==row.id)}
 val profiles=db.profiles().observe().map{rows->rows.map(::decode)}
 suspend fun load(id:String)=db.profiles().get(id)?.let(::decode)
 suspend fun saveProfile(p:Profile) {require(p.valid());db.withTransaction {require(db.profiles().all().size<MAX_BACKUP_PROFILES){"Лимит локальных профилей: $MAX_BACKUP_PROFILES"};require(load(p.id)==null);put(p)}}
 suspend fun start(id:String,day:TrainingDay,now:Long):Session=db.withTransaction {
  require(db.profiles().all().map(::decode).none{it.active!=null}) { "Есть незавершённое занятие" }
  val p=load(id)?:error("Профиль не найден")
  require(p.limitations.isBlank()&&p.recovery>1)
  val session=startSession(id,day,now);put(p.copy(active=session));session
 }
 suspend fun mutateSession(id:String,sessionId:String,stage:String?=null,op:(Session)->Session)=db.withTransaction {
  val p=load(id)?:return@withTransaction
  val active=p.active?:return@withTransaction
  if(active.id!=sessionId || (stage!=null && sessionStage(active)!=stage))return@withTransaction
  val next=op(active);require(next.id==active.id && next.profileId==id)
  // Paused ticks and stale callbacks must not rewrite an unchanged profile.
  if(next!=active)put(p.copy(active=next))
 }
 /**
  * The service never established reliable foreground supervision. Preserve
  * the entire session, but don't count any time since its last durable tick.
  * The guard is evaluated inside the Room transaction to reject late failures.
  */
 suspend fun pauseWhenServiceUnavailable(
  id:String,sessionId:String,now:Long,isCurrent:()->Boolean={true}
 )=mutateSession(id,sessionId){s->
  if(isCurrent())recoverSession(s,now) else s
 }
 /** Prefer exporting a single selected profile; all-profile export must be
  * explicit in UI, since different local profiles may belong to other people.
  */
 suspend fun exportBackup(profileId:String?=null):String=db.withTransaction {
  val selected=if(profileId==null)db.profiles().all().map(::decode)
   else listOf(load(profileId)?:error("Профиль не найден"))
  encodeBackup(Backup(profiles=selected))
 }
 suspend fun importBackup(text:String):List<String> {
  val backup=decodeBackup(text)
  return db.withTransaction {
   val existing=db.profiles().all().map(::decode)
   require(existing.none{it.active!=null}){"Завершите занятие перед импортом"}
   require(existing.size+backup.profiles.size<=MAX_BACKUP_PROFILES){"На устройстве может быть не более $MAX_BACKUP_PROFILES профилей"}
   backup.profiles.map{p->val copy=p.copy(id=java.util.UUID.randomUUID().toString());put(copy);copy.id}
  }
 }
 private suspend fun put(p:Profile)=db.profiles().put(ProfileRow(p.id,p.name,json.encodeToString(p)))
 suspend fun mutate(id:String,op:(Profile)->Profile)=db.withTransaction {val old=load(id)?:error("Профиль не найден");val next=op(old);require(next.id==id && next.valid());put(next)}
}
private val Context.store by preferencesDataStore(name="forma_preferences")
data class Preferences(val selected:String="",val voice:Boolean=true,val sound:Boolean=true,val metronome:Boolean=false,val dark:Boolean?=true,val volume:Int=65,val bpm:Int=60,val speechRate:Float=1f,val trainingPlace:TrainingPlace=TrainingPlace.HOME)
class SettingsRepository(private val context:Context) {
 private val volume=intPreferencesKey("volume");private val bpm=intPreferencesKey("bpm");private val rate=floatPreferencesKey("speech_rate")
 private val selected=stringPreferencesKey("selected");private val voice=booleanPreferencesKey("voice");private val sound=booleanPreferencesKey("sound");private val metronome=booleanPreferencesKey("metronome");private val theme=stringPreferencesKey("theme");private val placeKey=stringPreferencesKey("training_place")
 val settings=context.store.data.map{Preferences(it[selected].orEmpty(),it[voice]?:true,it[sound]?:true,it[metronome]?:false,when(it[theme]){"dark"->true;"light"->false;"system"->null;else->true},(it[volume]?:65).coerceIn(0,100),(it[bpm]?:60).coerceIn(40,180),(it[rate]?:1f).coerceIn(0.7f,1.3f),TrainingPlace.entries.firstOrNull{place->place.name==it[placeKey]}?:TrainingPlace.HOME)}
 suspend fun select(id:String){context.store.edit{it[selected]=id}}
 suspend fun audio(v:Boolean,s:Boolean,m:Boolean){context.store.edit{it[voice]=v;it[sound]=s;it[metronome]=m}}
 suspend fun coach(v:Int,b:Int,r:Float){require(v in 0..100 && b in 40..180 && r.isFinite() && r in 0.7f..1.3f);context.store.edit{it[volume]=v;it[bpm]=b;it[rate]=r}}
 suspend fun dark(v:Boolean?){context.store.edit{it[theme]=when(v){true->"dark";false->"light";null->"system"}}}
 suspend fun trainingPlace(place:TrainingPlace){context.store.edit{it[placeKey]=place.name}}
}
class FormaApp:Application(){
 @Volatile var serviceRunning=false
 internal val audioFocusEpoch=AudioFocusEpoch()
 internal val serviceInterruptions=ServiceInterruptionTracker()
 internal val applicationScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 internal suspend fun mutateWorkoutSession(
  profileId:String,sessionId:String,stage:String?=null,op:(Session)->Session
 )=mutateWithServiceInterruption(repository,serviceInterruptions,profileId,sessionId,stage,::workoutNow,op)
 val coachStatus=MutableStateFlow("Голос проверяется при начале сопровождения")
 val serviceError=MutableStateFlow<String?>(null)
 val processStartedMs=System.currentTimeMillis()
 private val elapsedAnchor=android.os.SystemClock.elapsedRealtime()
 fun workoutNow()=processStartedMs+(android.os.SystemClock.elapsedRealtime()-elapsedAnchor)
 val database by lazy{Room.databaseBuilder(this,FormaDatabase::class.java,"forma.db").build()}
 val repository by lazy{ProfileRepository(database)}
 val settings by lazy{SettingsRepository(this)}
}
