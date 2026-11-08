package ru.forma.app

import androidx.room.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.forma.domain.*
import java.util.UUID

// No production binding writer exists until verified Firebase Auth + explicit
// local-to-cloud consent are connected. Tests may seed synthetic bindings.
@Entity(tableName="sync_bindings", indices=[Index(value=["accountUid"])])
data class SyncBindingRow(
 @PrimaryKey val profileId:String,
 val accountUid:String,
 val consentAtMs:Long
)
@Entity(tableName="sync_outbox", indices=[
 Index(value=["accountUid","profileId","kind","recordId","revision"],unique=true),
 Index(value=["state"])
])
data class SyncOutboxRow(
 @PrimaryKey val opId:String,
 val accountUid:String,
 val profileId:String,
 val kind:String,
 val recordId:String,
 val revision:Int,
 val snapshot:String,
 val state:String,
 val tombstone:Boolean,
 val createdAtMs:Long
)
@Dao interface SyncOutboxDao {
 @Query("SELECT * FROM sync_bindings WHERE profileId=:profileId")
 suspend fun binding(profileId:String):SyncBindingRow?
 // Only used by synthetic tests until explicit, verified Auth consent exists.
 @Insert(onConflict=OnConflictStrategy.ABORT)
 suspend fun bindForVerifiedConsent(row:SyncBindingRow)
 // Diagnostics may count all records, but cannot read another account's payload.
 @Query("SELECT COUNT(*) FROM sync_outbox")
 suspend fun totalRows():Int
 // The eventual sender MUST use the verified Firebase Auth UID, not a UI/profile field.
 // Parent profile must exist before nested measurement/workout creates.
 // Its final tombstone goes last, as Firestore denies writes below a deleted parent.
 // Revisions for each document remain in ascending order; never skip older events.
 // Newer revisions of the *same* document may not skip an unresolved
 // conflict. A conflicting parent profile also blocks all its nested data:
 // uploading a child below a divergent remote parent could mix histories.
 // Other independent profiles remain eligible for progress.
 @Query("SELECT * FROM sync_outbox AS current WHERE current.state='PENDING' AND current.accountUid=:uid AND NOT EXISTS (SELECT 1 FROM sync_outbox AS older WHERE older.accountUid=current.accountUid AND older.profileId=current.profileId AND older.kind=current.kind AND older.recordId=current.recordId AND older.revision<current.revision AND older.state='CONFLICT') AND (current.kind='profile' OR NOT EXISTS (SELECT 1 FROM sync_outbox AS blockedProfile WHERE blockedProfile.accountUid=current.accountUid AND blockedProfile.profileId=current.profileId AND blockedProfile.kind='profile' AND blockedProfile.state='CONFLICT')) ORDER BY current.profileId,CASE WHEN current.kind='profile' AND current.tombstone=0 THEN 0 WHEN current.kind='profile' THEN 2 ELSE 1 END,current.kind,current.recordId,current.revision")
 suspend fun pendingForAccount(uid:String):List<SyncOutboxRow>
 @Query("SELECT * FROM sync_outbox AS current WHERE current.state='PENDING' AND current.accountUid=:uid AND NOT EXISTS (SELECT 1 FROM sync_outbox AS older WHERE older.accountUid=current.accountUid AND older.profileId=current.profileId AND older.kind=current.kind AND older.recordId=current.recordId AND older.revision<current.revision AND older.state='CONFLICT') AND (current.kind='profile' OR NOT EXISTS (SELECT 1 FROM sync_outbox AS blockedProfile WHERE blockedProfile.accountUid=current.accountUid AND blockedProfile.profileId=current.profileId AND blockedProfile.kind='profile' AND blockedProfile.state='CONFLICT')) ORDER BY current.profileId,CASE WHEN current.kind='profile' AND current.tombstone=0 THEN 0 WHEN current.kind='profile' THEN 2 ELSE 1 END,current.kind,current.recordId,current.revision LIMIT 1")
 suspend fun nextPendingForAccount(uid:String):SyncOutboxRow?
 // ACK is permitted only AFTER a future authenticated adapter has read back
 // an exact remote opId/revision/payload/tombstone for this verified UID.
 // This is merely an optimistic Room transition, NOT proof of server delivery;
 // no production adapter/caller is enabled at this stage.
 @Query("UPDATE sync_outbox SET state='ACKED' WHERE state='PENDING' AND accountUid=:uid AND opId=:opId AND profileId=:profileId AND kind=:kind AND recordId=:recordId AND revision=:revision AND snapshot=:snapshot AND tombstone=:tombstone")
 suspend fun acknowledgeExactRemoteEcho(
  uid:String,opId:String,profileId:String,kind:String,recordId:String,
  revision:Int,snapshot:String,tombstone:Boolean
 ):Int
 // Comparing remote predecessor prevents a newly connected device from
 // treating a different account/device revision as its own confirmed history.
 @Query("SELECT * FROM sync_outbox WHERE accountUid=:uid AND profileId=:profileId AND kind=:kind AND recordId=:recordId AND revision=:revision LIMIT 1")
 suspend fun revisionForAccount(uid:String,profileId:String,kind:String,recordId:String,revision:Int):SyncOutboxRow?
 // Conflicts preserve the exact local snapshot/opId and survive process death.
 // No remote conflicting payload is stored until reviewed by an authenticated
 // conflict UI. Only a future explicit review flow may resolve this state.
 @Query("UPDATE sync_outbox SET state='CONFLICT' WHERE state='PENDING' AND accountUid=:uid AND opId=:opId AND profileId=:profileId AND kind=:kind AND recordId=:recordId AND revision=:revision AND snapshot=:snapshot AND tombstone=:tombstone")
 suspend fun parkExactConflict(
  uid:String,opId:String,profileId:String,kind:String,recordId:String,
  revision:Int,snapshot:String,tombstone:Boolean
 ):Int
 @Query("SELECT * FROM sync_outbox WHERE state='CONFLICT' AND accountUid=:uid ORDER BY profileId,kind,recordId,revision")
 suspend fun conflictsForAccount(uid:String):List<SyncOutboxRow>
 @Query("SELECT MAX(revision) FROM sync_outbox WHERE accountUid=:uid AND profileId=:profileId AND kind=:kind AND recordId=:recordId")
 suspend fun latestRevision(uid:String,profileId:String,kind:String,recordId:String):Int?
 @Insert(onConflict=OnConflictStrategy.ABORT)
 suspend fun enqueue(row:SyncOutboxRow)
}

@Serializable internal data class CloudProfilePayload(
 val name:String,val age:Int,val heightCm:Double,val weightKg:Double,
 val experience:String,val equipment:List<String>,val limitations:String,
 val primary:String,val secondary:String?,val priorities:List<String>,
 val days:List<Int>,val recovery:Int
)
@Serializable internal data class CloudMeasurementPayload(val dateMs:Long,val values:Map<String,Double>)
@Serializable internal data class CloudWorkoutPayload(
 val day:TrainingDay,val startedMs:Long,val finishedMs:Long,
 val sets:List<SetResult>,val status:String,val activeSeconds:Int,
 val painReported:Boolean
)
// Keep the local→cloud path contract identical to Firestore stableId().
// A legacy odd ID remains safe in Room; explicit binding fails atomically
// rather than silently rewriting historical UUIDs or losing queued data.
private val cloudDocumentId=Regex("^[A-Za-z0-9_-]{1,128}$")
private fun Profile.cloudPayload()=CloudProfilePayload(
 name,age,heightCm,weightKg,experience.name,
 equipment.map{it.name}.sorted(),limitations,primary.name,secondary?.name,
 priorities.map{it.name}.sorted(),days.sorted(),recovery
)
private fun Measurement.cloudPayload()=CloudMeasurementPayload(dateMs,values.toSortedMap())
private fun Workout.cloudPayload():CloudWorkoutPayload {
 require(status==Phase.DONE||status==Phase.STOPPED){"No unfinished workout uploads"}
 return CloudWorkoutPayload(day,startedMs,finishedMs,sets,status.name,activeSeconds,painReported)
}

/**
 * Called only inside an existing db.withTransaction profile mutation.
 * Offline mutations with no consent binding never queue or upload anything;
 * session ticks do not change profile/history/measurement projections.
 */
internal suspend fun enqueueBoundChanges(db:FormaDatabase,before:Profile?,after:Profile,json:Json){
 val binding=db.sync().binding(after.id)?:return
 require(binding.profileId==after.id&&binding.accountUid.isNotBlank()&&binding.consentAtMs>0){
  "Verified account consent is required"
 }
 require(cloudDocumentId.matches(after.id)){"Profile ID cannot be represented in Firestore"}
 suspend fun queue(kind:String,id:String,payload:String,tombstone:Boolean=false){
  require(cloudDocumentId.matches(id)){"Record ID cannot be represented in Firestore"}
  val last=db.sync().latestRevision(binding.accountUid,after.id,kind,id)?:0
  require(last<Int.MAX_VALUE){"Sync revision exhausted"}
  db.sync().enqueue(SyncOutboxRow(
   opId=UUID.randomUUID().toString(),accountUid=binding.accountUid,
   profileId=after.id,kind=kind,recordId=id,revision=last+1,
   snapshot=payload,state="PENDING",tombstone=tombstone,
   createdAtMs=System.currentTimeMillis()
  ))
 }
 if(before?.cloudPayload()!=after.cloudPayload())
  queue("profile",after.id,json.encodeToString(after.cloudPayload()))

 val oldMeasures=before?.measurements.orEmpty().associateBy{it.id}
 val newMeasures=after.measurements.associateBy{it.id}
 require(newMeasures.size==after.measurements.size){"Duplicate measurement UUID"}
 after.measurements.forEach{m->
  if(oldMeasures[m.id]!=m)queue("measurement",m.id,json.encodeToString(m.cloudPayload()))
 }
 before?.measurements.orEmpty().forEach{m->
  if(m.id !in newMeasures)queue("measurement",m.id,json.encodeToString(m.cloudPayload()),true)
 }

 val oldWorkouts=before?.history.orEmpty().associateBy{it.id}
 val newWorkouts=after.history.associateBy{it.id}
 require(newWorkouts.size==after.history.size){"Duplicate workout UUID"}
 after.history.forEach{w->
  if(oldWorkouts[w.id]!=w)queue("workout",w.id,json.encodeToString(w.cloudPayload()))
 }
 before?.history.orEmpty().forEach{w->
  if(w.id !in newWorkouts)queue("workout",w.id,json.encodeToString(w.cloudPayload()),true)
 }
 // before.active/after.active, timers and in-progress sets remain local.
}
