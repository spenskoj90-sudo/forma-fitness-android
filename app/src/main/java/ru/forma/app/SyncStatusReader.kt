package ru.forma.app

/** Only a verified Auth lease can authorize a read of this local account scope. */
internal enum class LocalSyncState { NO_AUTH, SESSION_CHANGED, READY }

/**
 * No profile IDs, names, record IDs, opIds, payloads or raw Auth UID reach UI.
 * PENDING includes operations blocked by a conflict; ACKED is an exact local
 * server-echo record, not a guarantee that the provider remains reachable.
 */
internal data class LocalSyncSummary(
 val state:LocalSyncState,
 val pending:Int=0,
 val conflicts:Int=0,
 val acknowledged:Int=0
) {
 init {
  require(pending>=0 && conflicts>=0 && acknowledged>=0)
  require(state==LocalSyncState.READY ||
   (pending==0 && conflicts==0 && acknowledged==0))
 }
 fun accessibleMessage():String = when(state) {
  LocalSyncState.NO_AUTH ->
   "Облачная синхронизация не подключена. Данные остаются на устройстве."
  LocalSyncState.SESSION_CHANGED ->
   "Аккаунт изменился. Обновите статус синхронизации."
  LocalSyncState.READY ->
   "Локальная очередь: ожидают отправки — $pending, требуют ручного разбора — $conflicts, " +
    "подтверждены ранее — $acknowledged. Подтверждение не означает, что облако доступно сейчас."
 }
}

/**
 * Read-only scoped status for a future verified account/consent UI.
 * Never accept a UID from a profile, UI field, stored binding or caller.
 * An auth sign-out, account switch or generation change during the Room
 * read invalidates the entire result, including aggregate counts.
 *
 * No production Firebase/Auth provider is connected. Tests inject leases;
 * exposing this class does NOT authorize a real account or remote operation.
 */
internal class SyncStatusReader(
 private val db:FormaDatabase,
 private val identity:VerifiedSyncIdentity
) {
 suspend fun read():LocalSyncSummary {
  val lease=identity.currentLease()?.takeIf{it.uid.isNotBlank()}
   ?:return LocalSyncSummary(LocalSyncState.NO_AUTH)
  val counts=db.sync().countsForVerifiedAccount(lease.uid)
  if(identity.currentLease()!=lease)
   return LocalSyncSummary(LocalSyncState.SESSION_CHANGED)
  require(counts.total>=0 && counts.pending>=0 &&
   counts.conflicts>=0 && counts.acknowledged>=0 &&
   counts.total.toLong()==counts.pending.toLong()+counts.conflicts+counts.acknowledged) {
   "Unknown or invalid local synchronization state"
  }
  return LocalSyncSummary(LocalSyncState.READY,
   counts.pending,counts.conflicts,counts.acknowledged)
 }
}
