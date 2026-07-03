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
        Index(value = ["fingerprint"], unique = true),
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
) {
    override fun equals(other: Any?): Boolean = other is LocalClipboardEntity &&
        id == other.id && encryptedPayload.contentEquals(other.encryptedPayload) &&
        fingerprint.contentEquals(other.fingerprint) && envelopeVersion == other.envelopeVersion &&
        createdAtEpochMillis == other.createdAtEpochMillis && captureSource == other.captureSource &&
        plaintextByteCount == other.plaintextByteCount && isBookmarked == other.isBookmarked

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

@Database(entities = [LocalClipboardEntity::class], version = 1, exportSchema = true)
abstract class ClipSyncDatabase : RoomDatabase() {
    abstract fun localClipboardDao(): LocalClipboardDao
}
