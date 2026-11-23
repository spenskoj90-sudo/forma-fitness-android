package ru.forma.app

internal class LocalWorkoutAccessRevoked : IllegalStateException()

internal class LocalWorkoutAccess(
    val lease: VerifiedSyncLease,
    private val isCurrent: () -> Boolean,
) {
    fun requireCurrent() {
        if (!isCurrent()) throw LocalWorkoutAccessRevoked()
    }
}

/** This opaque identifier is process-local and contains no account data. */
internal class AccountWorkoutScope(
    val id: String,
    val lease: VerifiedSyncLease,
    val repository: ProfileRepository,
)
