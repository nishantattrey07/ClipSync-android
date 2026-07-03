package com.nishantattrey.clipsync.core.local.persistence

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "local_clipboard_items",
    indices = [
        Index(value = ["fingerprint"]),
        Index(value = ["created_at_epoch_millis", "id"]),
        Index(value = ["is_bookmarked", "created_at_epoch_millis", "id"]),
    ],
)
data class LocalClipboardEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "encrypted_payload", typeAffinity = ColumnInfo.BLOB)
    val encryptedPayload: ByteArray,
    @ColumnInfo(name = "fingerprint", typeAffinity = ColumnInfo.BLOB)
    val fingerprint: ByteArray,
    @ColumnInfo(name = "envelope_version")
    val envelopeVersion: Int,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "capture_source")
    val captureSource: String,
    @ColumnInfo(name = "plaintext_byte_count")
    val plaintextByteCount: Int,
    @ColumnInfo(name = "is_bookmarked")
    val isBookmarked: Boolean,
    @ColumnInfo(name = "cloud_sync_state", defaultValue = "'local'")
    val cloudSyncState: String = "local",
) {
    override fun equals(other: Any?): Boolean = other is LocalClipboardEntity &&
        id == other.id && encryptedPayload.contentEquals(other.encryptedPayload) &&
        fingerprint.contentEquals(other.fingerprint) && envelopeVersion == other.envelopeVersion &&
        createdAtEpochMillis == other.createdAtEpochMillis && captureSource == other.captureSource &&
        plaintextByteCount == other.plaintextByteCount && isBookmarked == other.isBookmarked &&
        cloudSyncState == other.cloudSyncState

    override fun hashCode(): Int = id.hashCode()
}

@Dao
interface LocalClipboardDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: LocalClipboardEntity): Long

    @Query("SELECT * FROM local_clipboard_items WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: ByteArray): LocalClipboardEntity?

    @Query("SELECT * FROM local_clipboard_items WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): LocalClipboardEntity?

    @Query("SELECT * FROM local_clipboard_items WHERE cloud_sync_state = 'local' ORDER BY created_at_epoch_millis ASC, id ASC LIMIT :limit")
    suspend fun loadUnsynced(limit: Int): List<LocalClipboardEntity>

    @Query("UPDATE local_clipboard_items SET cloud_sync_state = :state WHERE id = :id")
    suspend fun setCloudSyncState(id: String, state: String): Int

    @Query(
        """SELECT * FROM local_clipboard_items
        WHERE (:bookmarksOnly = 0 OR is_bookmarked = 1)
          AND (:beforeCreated IS NULL OR created_at_epoch_millis < :beforeCreated
            OR (created_at_epoch_millis = :beforeCreated AND id < :beforeId))
        ORDER BY created_at_epoch_millis DESC, id DESC
        LIMIT :limit""",
    )
    suspend fun loadPage(
        bookmarksOnly: Boolean,
        beforeCreated: Long?,
        beforeId: String?,
        limit: Int,
    ): List<LocalClipboardEntity>

    @Query("UPDATE local_clipboard_items SET is_bookmarked = :bookmarked WHERE id = :id")
    suspend fun setBookmarked(id: String, bookmarked: Boolean): Int

    @Query("DELETE FROM local_clipboard_items WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM local_clipboard_items WHERE is_bookmarked = 0")
    suspend fun clearUnbookmarked(): Int

    @Query("DELETE FROM local_clipboard_items WHERE is_bookmarked = 0 AND created_at_epoch_millis < :cutoff")
    suspend fun deleteUnbookmarkedOlderThan(cutoff: Long): Int

    @Query(
        """DELETE FROM local_clipboard_items WHERE is_bookmarked = 0 AND id NOT IN (
        SELECT id FROM local_clipboard_items WHERE is_bookmarked = 0
        ORDER BY created_at_epoch_millis DESC, id DESC LIMIT :keepCount)""",
    )
    suspend fun trimUnbookmarked(keepCount: Int): Int

    @Query("SELECT COUNT(*) FROM local_clipboard_items")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM local_clipboard_items")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM local_clipboard_items")
    suspend fun deleteAll(): Int
}

@Entity(
    tableName = "outbound_text_queue",
    indices = [Index(value = ["state", "next_attempt_at_epoch_millis", "created_at_epoch_millis", "item_id"])],
)
data class OutboundTextEntity(
    @androidx.room.PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "channel_id") val channelId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "ciphertext") val ciphertext: String,
    @ColumnInfo(name = "payload_version") val payloadVersion: Int,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "next_attempt_at_epoch_millis") val nextAttemptAtEpochMillis: Long,
    @ColumnInfo(name = "last_failure") val lastFailure: String?,
)

@Entity(
    tableName = "inbound_text_journal",
    indices = [Index(value = ["channel_id", "created_at_microseconds", "item_id"])],
)
data class InboundTextJournalEntity(
    @androidx.room.PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "channel_id") val channelId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "payload_version") val payloadVersion: Int,
    @ColumnInfo(name = "ciphertext") val ciphertext: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "created_at_microseconds") val createdAtMicroseconds: Long,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "failure_class") val failureClass: String?,
)

@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @androidx.room.PrimaryKey @ColumnInfo(name = "channel_id") val channelId: String,
    @ColumnInfo(name = "created_at_microseconds") val createdAtMicroseconds: Long,
    @ColumnInfo(name = "item_id") val itemId: String,
)

@Entity(
    tableName = "device_directory",
    primaryKeys = ["channel_id", "device_id"],
    indices = [Index(value = ["channel_id", "updated_at"])],
)
data class DeviceDirectoryEntity(
    @ColumnInfo(name = "channel_id") val channelId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "profile_ciphertext") val profileCiphertext: String,
    @ColumnInfo(name = "profile_version") val profileVersion: Int,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

@Dao
interface SyncPersistenceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOutbound(entity: OutboundTextEntity): Long

    @Query("SELECT * FROM outbound_text_queue WHERE item_id = :itemId LIMIT 1")
    suspend fun findOutbound(itemId: String): OutboundTextEntity?

    @Query("SELECT * FROM outbound_text_queue WHERE state IN ('pending', 'retry') AND next_attempt_at_epoch_millis <= :now ORDER BY created_at_epoch_millis ASC, item_id ASC LIMIT :limit")
    suspend fun loadDueOutbound(now: Long, limit: Int): List<OutboundTextEntity>

    @Query("UPDATE outbound_text_queue SET state = :state, attempt_count = :attemptCount, next_attempt_at_epoch_millis = :nextAttemptAt, last_failure = :failure WHERE item_id = :itemId")
    suspend fun updateOutbound(itemId: String, state: String, attemptCount: Int, nextAttemptAt: Long, failure: String?): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun journalInbound(entity: InboundTextJournalEntity): Long

    @Query("SELECT * FROM inbound_text_journal WHERE channel_id = :channelId AND state IN ('pending', 'retry') ORDER BY created_at_microseconds ASC, item_id ASC LIMIT :limit")
    suspend fun loadPendingInbound(channelId: String, limit: Int): List<InboundTextJournalEntity>

    @Query("UPDATE inbound_text_journal SET state = :state, failure_class = :failureClass WHERE item_id = :itemId")
    suspend fun updateInbound(itemId: String, state: String, failureClass: String?): Int

    @Query("DELETE FROM inbound_text_journal WHERE item_id = :itemId AND state = 'acknowledged'")
    suspend fun deleteAcknowledgedInbound(itemId: String): Int

    @Query("SELECT * FROM sync_cursors WHERE channel_id = :channelId LIMIT 1")
    suspend fun loadCursor(channelId: String): SyncCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(cursor: SyncCursorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevices(devices: List<DeviceDirectoryEntity>)

    @Query("SELECT * FROM device_directory WHERE channel_id = :channelId ORDER BY updated_at DESC, device_id DESC")
    fun observeDevices(channelId: String): Flow<List<DeviceDirectoryEntity>>
}

@Database(
    entities = [
        LocalClipboardEntity::class,
        OutboundTextEntity::class,
        InboundTextJournalEntity::class,
        SyncCursorEntity::class,
        DeviceDirectoryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ClipSyncDatabase : RoomDatabase() {
    abstract fun localClipboardDao(): LocalClipboardDao
    abstract fun syncPersistenceDao(): SyncPersistenceDao
}
