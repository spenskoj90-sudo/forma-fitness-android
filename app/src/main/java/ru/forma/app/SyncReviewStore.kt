package ru.forma.app

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "sync_review", primaryKeys = ["accountUid", "profileId", "kind", "recordId"])
data class SyncReviewRow(val accountUid: String, val profileId: String, val kind: String, val recordId: String)

@Entity(tableName = "sync_pull", primaryKeys = ["accountUid", "profileId", "kind"])
data class SyncPullRow(
    val accountUid: String, val profileId: String, val kind: String,
    val seconds: Long, val nanos: Int, val recordId: String,
)

@Entity(tableName = "sync_resolution_archive", indices = [Index(value = ["accountUid", "savedAtMs"])])
data class SyncResolutionArchiveRow(
    @PrimaryKey val id: String,
    val accountUid: String, val profileId: String, val kind: String, val recordId: String,
    val localSnapshot: String?, val cloudSnapshot: String, val cloudDeleted: Boolean,
    val cloudRevision: Int, val cloudOpId: String, val queueSnapshot: String,
    val choice: String, val savedAtMs: Long,
)

@Dao interface SyncReviewDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun mark(row: SyncReviewRow)

    @Query("DELETE FROM sync_review WHERE accountUid=:uid AND profileId=:profile AND kind=:kind AND recordId=:record")
    suspend fun clear(uid: String, profile: String, kind: String, record: String)

    @Query("SELECT accountUid, profileId, kind, recordId FROM sync_review WHERE accountUid=:uid UNION SELECT accountUid, profileId, kind, recordId FROM sync_outbox WHERE accountUid=:uid AND state='CONFLICT' ORDER BY profileId, kind, recordId LIMIT :limit")
    suspend fun reviews(uid: String, limit: Int): List<SyncReviewRow>

    @Query("""
        SELECT COUNT(*) FROM (
            SELECT profileId, kind, recordId FROM sync_review WHERE accountUid=:uid
            UNION
            SELECT profileId, kind, recordId FROM sync_outbox WHERE accountUid=:uid AND state='CONFLICT'
        )
    """)
    suspend fun count(uid: String): Int

    @Query("""
        SELECT EXISTS(SELECT 1 FROM sync_review
            WHERE accountUid=:uid AND profileId=:profile AND kind=:kind AND recordId=:record)
        OR EXISTS(SELECT 1 FROM sync_outbox
            WHERE accountUid=:uid AND profileId=:profile AND kind=:kind AND recordId=:record AND state='CONFLICT')
    """)
    suspend fun contains(uid: String, profile: String, kind: String, record: String): Boolean

    @Query("SELECT * FROM sync_pull WHERE accountUid=:uid AND profileId=:profile AND kind=:kind")
    suspend fun cursor(uid: String, profile: String, kind: String): SyncPullRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCursor(row: SyncPullRow)

    @Insert
    suspend fun archive(row: SyncResolutionArchiveRow)

    @Query("SELECT * FROM sync_resolution_archive WHERE accountUid=:uid ORDER BY savedAtMs DESC,id LIMIT :limit")
    suspend fun archiveForAccount(uid: String, limit: Int): List<SyncResolutionArchiveRow>
}
