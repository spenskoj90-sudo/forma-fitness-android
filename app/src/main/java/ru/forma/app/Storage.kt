package ru.forma.app
import android.app.Application
import android.content.Context
import androidx.room.*
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
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
 suspend fun saveProfile(p:Profile) {require(p.valid());db.withTransaction {require(load(p.id)==null);put(p)}}
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
  put(p.copy(active=next))
 }
 suspend fun exportBackup():String=db.withTransaction {encodeBackup(Backup(profiles=db.profiles().all().map(::decode)))}
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
data class Preferences(val selected:String="",val voice:Boolean=true,val sound:Boolean=true,val metronome:Boolean=false,val dark:Boolean?=true,val volume:Int=65,val bpm:Int=60,val speechRate:Float=1f)
class SettingsRepository(private val context:Context) {
 private val volume=intPreferencesKey("volume");private val bpm=intPreferencesKey("bpm");private val rate=floatPreferencesKey("speech_rate")
 private val selected=stringPreferencesKey("selected");private val voice=booleanPreferencesKey("voice");private val sound=booleanPreferencesKey("sound");private val metronome=booleanPreferencesKey("metronome");private val theme=stringPreferencesKey("theme")
 val settings=context.store.data.map{Preferences(it[selected].orEmpty(),it[voice]?:true,it[sound]?:true,it[metronome]?:false,when(it[theme]){"dark"->true;"light"->false;"system"->null;else->true},(it[volume]?:65).coerceIn(0,100),(it[bpm]?:60).coerceIn(40,180),(it[rate]?:1f).coerceIn(0.7f,1.3f))}
 suspend fun select(id:String){context.store.edit{it[selected]=id}}
 suspend fun audio(v:Boolean,s:Boolean,m:Boolean){context.store.edit{it[voice]=v;it[sound]=s;it[metronome]=m}}
 suspend fun coach(v:Int,b:Int,r:Float){require(v in 0..100 && b in 40..180 && r.isFinite() && r in 0.7f..1.3f);context.store.edit{it[volume]=v;it[bpm]=b;it[rate]=r}}
 suspend fun dark(v:Boolean?){context.store.edit{it[theme]=when(v){true->"dark";false->"light";null->"system"}}}
}
class FormaApp:Application(){
 @Volatile var serviceRunning=false
 val coachStatus=MutableStateFlow("Голос проверяется при начале сопровождения")
 val processStartedMs=System.currentTimeMillis()
 private val elapsedAnchor=android.os.SystemClock.elapsedRealtime()
 fun workoutNow()=processStartedMs+(android.os.SystemClock.elapsedRealtime()-elapsedAnchor)
 val database by lazy{Room.databaseBuilder(this,FormaDatabase::class.java,"forma.db").build()}
 val repository by lazy{ProfileRepository(database)}
 val settings by lazy{SettingsRepository(this)}
}
