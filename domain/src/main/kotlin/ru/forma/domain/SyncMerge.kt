package ru.forma.domain

/**
 * A pure offline-sync merge plan. It performs NO network request and gives no
 * user access rights. Authentication/ownership must be verified on the server
 * before any remote snapshot is returned to a device.
 *
 * A conflicting UUID is NEVER silently overwritten: the local record remains
 * and both alternatives are surfaced for explicit resolution.
 */
data class SyncScope(val accountId: String, val profileId: String) {
    fun valid(): Boolean = accountId.isNotBlank() && profileId.isNotBlank()
}

data class SyncConflict<T>(val id: String, val local: T, val remote: T)
data class RecordMerge<T>(val records: List<T>, val conflicts: List<SyncConflict<T>>)
data class ProfileSyncPlan(
    val scope: SyncScope,
    val workouts: RecordMerge<Workout>,
    val measurements: RecordMerge<Measurement>
) {
    val hasConflicts: Boolean
        get() = workouts.conflicts.isNotEmpty() || measurements.conflicts.isNotEmpty()
}

private fun <T> mergeByStableId(
    local: List<T>, remote: List<T>, identity: (T) -> String
): RecordMerge<T> {
    require(local.all { identity(it).isNotBlank() } && remote.all { identity(it).isNotBlank() }) {
        "Missing record identity"
    }
    require(local.map(identity).distinct().size == local.size &&
        remote.map(identity).distinct().size == remote.size) {
        "Duplicate record UUID in a single snapshot"
    }
    val all = LinkedHashMap<String, T>()
    local.forEach { all[identity(it)] = it }
    val conflicts = mutableListOf<SyncConflict<T>>()
    remote.forEach { incoming ->
        val id = identity(incoming)
        val existing = all[id]
        if (existing == null) all[id] = incoming
        else if (existing != incoming) conflicts += SyncConflict(id, existing, incoming)
    }
    return RecordMerge(all.values.toList(), conflicts)
}

/**
 * Never merge across accounts or profiles. A caller cannot simply rename the
 * scope to gain access; authenticated server-side authorization is mandatory.
 */
fun planProfileSync(
    localScope: SyncScope,
    remoteScope: SyncScope,
    localWorkouts: List<Workout>,
    remoteWorkouts: List<Workout>,
    localMeasurements: List<Measurement>,
    remoteMeasurements: List<Measurement>
): ProfileSyncPlan {
    require(localScope.valid() && remoteScope.valid() && localScope == remoteScope) {
        "Cannot sync unrelated accounts or profiles"
    }
    return ProfileSyncPlan(
        scope = localScope,
        workouts = mergeByStableId(localWorkouts, remoteWorkouts) { it.id },
        measurements = mergeByStableId(localMeasurements, remoteMeasurements) { it.id }
    )
}
