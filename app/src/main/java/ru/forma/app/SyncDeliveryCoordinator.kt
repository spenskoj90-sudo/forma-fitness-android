package ru.forma.app

/** A short-lived identity lease from a trusted Auth provider, never from a UI field. */
internal data class VerifiedSyncLease(val uid: String, val generation: Long)

internal interface VerifiedSyncIdentity {
 suspend fun currentLease(): VerifiedSyncLease?
}

/** Canonical data *read back* from the authenticated remote document path. */
internal data class SyncRemoteEcho(
 val accountUid: String,
 val profileId: String,
 val kind: String,
 val recordId: String,
 val revision: Int,
 val opId: String,
 val snapshot: String,
 val tombstone: Boolean
) {
 fun matches(row: SyncOutboxRow): Boolean =
  accountUid == row.accountUid && profileId == row.profileId &&
   kind == row.kind && recordId == row.recordId &&
   revision == row.revision && opId == row.opId &&
   snapshot == row.snapshot && tombstone == row.tombstone
}

/**
 * Not implemented in production until Firebase Auth, consent and Rules are verified.
 * read() must operate inside the token-bound UID scope; compareAndCommit() must
 * perform an atomic Firestore transaction whose precondition is *the entire*
 * previous echo (or absence), never a blind set()/last-write-wins update.
 * For a nested document it must ALSO verify expectedParent in the SAME
 * Firestore transaction before committing the child: a parent that changed
 * after read-back is never a safe target for local measurements/workouts.
 * The adapter must canonicalize the remote payload identically to the Room
 * snapshot before producing SyncRemoteEcho. A locally constructed echo is NOT
 * server acknowledgement.
 */
internal interface AuthenticatedSyncRemote {
 suspend fun read(lease: VerifiedSyncLease, row: SyncOutboxRow): SyncRemoteEcho?
 suspend fun compareAndCommit(
  lease: VerifiedSyncLease, row: SyncOutboxRow, expected: SyncRemoteEcho?,
  expectedParent: SyncRemoteEcho?
 ): Boolean
}

internal enum class SyncDeliveryStep {
 NO_AUTH, EMPTY, SESSION_CHANGED, CONFLICT, RETRY, ACKED
}

/**
 * Fail-closed one-record protocol for future opt-in, authenticated delivery.
 * It is deliberately NOT connected to a production transport or an app screen.
 * Exceptions (including offline and cancellation) leave the local outbox intact.
 */
internal class SyncDeliveryCoordinator(
 private val db: FormaDatabase,
 private val identity: VerifiedSyncIdentity,
 private val remote: AuthenticatedSyncRemote
) {
 suspend fun deliverOne(): SyncDeliveryStep {
  val lease = identity.currentLease()?.takeIf { it.uid.isNotBlank() }
   ?: return SyncDeliveryStep.NO_AUTH
  val dao = db.sync()
  val row = dao.nextPendingForAccount(lease.uid) ?: return SyncDeliveryStep.EMPTY
  if (identity.currentLease() != lease) return SyncDeliveryStep.SESSION_CHANGED
  // A nested write cannot rely on parentActive() alone: Firestore only checks
  // that the parent exists, not that its revision still matches this device.
  // Recheck the most recent ACKed parent before each child operation; the
  // adapter must repeat this precondition atomically at remote commit time.
  val parentEcho = if (row.kind == "profile") null else {
   val ackedParent = dao.latestAcknowledgedProfileForAccount(lease.uid, row.profileId)
    ?: return SyncDeliveryStep.RETRY
   if (ackedParent.tombstone) return park(lease, row)
   val observed = remote.read(lease, ackedParent)
   if (identity.currentLease() != lease) return SyncDeliveryStep.SESSION_CHANGED
   if (observed?.matches(ackedParent) != true) return park(lease, row)
   observed
  }
  if (identity.currentLease() != lease) return SyncDeliveryStep.SESSION_CHANGED
  val current = remote.read(lease, row)
  if (identity.currentLease() != lease) return SyncDeliveryStep.SESSION_CHANGED
  if (current?.matches(row) == true) return ack(lease, row)

  val previous = when {
   current == null && row.revision == 1 && !row.tombstone -> null
   current != null && row.revision > 1 && current.revision == row.revision - 1 -> {
    val committed = dao.revisionForAccount(
     lease.uid, row.profileId, row.kind, row.recordId, row.revision - 1
    )
    if (committed?.state != "ACKED" || current.tombstone ||
     !current.matches(committed)) return park(lease, row)
    current
   }
   else -> return park(lease, row)
  }
  if (identity.currentLease() != lease) return SyncDeliveryStep.SESSION_CHANGED
  if (!remote.compareAndCommit(lease, row, previous, parentEcho)) return SyncDeliveryStep.RETRY
  if (identity.currentLease() != lease) return SyncDeliveryStep.SESSION_CHANGED
  val echoed = remote.read(lease, row)
  if (identity.currentLease() != lease) return SyncDeliveryStep.SESSION_CHANGED
  if (echoed?.matches(row) != true) return SyncDeliveryStep.RETRY
  return ack(lease, row)
 }

 // A genuine remote divergence must survive process death, without deleting
 // local data, overwriting the remote, or allowing subsequent revisions for
 // this document to be sent ahead of the conflicting one.
 private suspend fun park(lease: VerifiedSyncLease, row: SyncOutboxRow): SyncDeliveryStep {
  if (identity.currentLease() != lease) return SyncDeliveryStep.SESSION_CHANGED
  val n = db.sync().parkExactConflict(
   lease.uid, row.opId, row.profileId, row.kind, row.recordId,
   row.revision, row.snapshot, row.tombstone
  )
  return if (n == 1) SyncDeliveryStep.CONFLICT else SyncDeliveryStep.RETRY
 }

 private suspend fun ack(lease: VerifiedSyncLease, row: SyncOutboxRow): SyncDeliveryStep {
  if (identity.currentLease() != lease) return SyncDeliveryStep.SESSION_CHANGED
  val n = db.sync().acknowledgeExactRemoteEcho(
   lease.uid, row.opId, row.profileId, row.kind, row.recordId,
   row.revision, row.snapshot, row.tombstone
  )
  return if (n == 1) SyncDeliveryStep.ACKED else SyncDeliveryStep.RETRY
 }
}
