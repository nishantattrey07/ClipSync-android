package com.nishantattrey.clipsync.core.sync.persistence

import androidx.room.withTransaction
import com.nishantattrey.clipsync.core.local.persistence.ClipSyncDatabase
import com.nishantattrey.clipsync.core.local.persistence.InboundTextJournalEntity
import com.nishantattrey.clipsync.core.local.persistence.OutboundTextEntity
import com.nishantattrey.clipsync.core.local.persistence.SyncCursorEntity
import com.nishantattrey.clipsync.core.protocol.model.ClipboardItemRecord
import com.nishantattrey.clipsync.core.protocol.model.SyncCursor

interface DurableSyncStore {
    suspend fun enqueuePrepared(entity: OutboundTextEntity): Boolean
    suspend fun dueOutbound(now: Long, limit: Int = 25): List<OutboundTextEntity>
    suspend fun markOutbound(entity: OutboundTextEntity, state: String, nextAttemptAt: Long, failure: String?): Int
    suspend fun journalPage(rows: List<Pair<ClipboardItemRecord, Long>>)
    suspend fun pendingInbound(channelId: String, limit: Int = 50): List<InboundTextJournalEntity>
    suspend fun acknowledgeAndAdvance(itemId: String, cursor: SyncCursor)
    suspend fun markPermanentInboundFailure(itemId: String, safeClass: String, cursor: SyncCursor)
    suspend fun cursor(channelId: String): SyncCursor?
}

class SyncQueueStore(private val database: ClipSyncDatabase) : DurableSyncStore {
    private val dao = database.syncPersistenceDao()

    override suspend fun enqueuePrepared(entity: OutboundTextEntity): Boolean {
        val inserted = dao.insertOutbound(entity) != -1L
        if (!inserted) {
            val existing = requireNotNull(dao.findOutbound(entity.itemId))
            require(existing.channelId == entity.channelId && existing.deviceId == entity.deviceId) {
                "Prepared upload identity changed."
            }
        }
        return inserted
    }
    override suspend fun dueOutbound(now: Long, limit: Int) = dao.loadDueOutbound(now, limit.coerceIn(1, 100))
    override suspend fun markOutbound(
        entity: OutboundTextEntity,
        state: String,
        nextAttemptAt: Long,
        failure: String?,
    ): Int = database.withTransaction {
        val updated = dao.updateOutbound(entity.itemId, state, entity.attemptCount + 1, nextAttemptAt, failure)
        when (state) {
            "synced" -> database.localClipboardDao().setCloudSyncState(entity.itemId, "synced")
            "failed" -> database.localClipboardDao().setCloudSyncState(entity.itemId, "failed")
            else -> database.localClipboardDao().setCloudSyncState(entity.itemId, "retrying")
        }
        updated
    }

    override suspend fun journalPage(rows: List<Pair<ClipboardItemRecord, Long>>) {
        database.withTransaction {
            rows.forEach { (row, micros) ->
                dao.journalInbound(
                    InboundTextJournalEntity(
                        itemId = row.id,
                        channelId = row.channelId,
                        deviceId = row.deviceId,
                        kind = row.kind,
                        payloadVersion = row.payloadVersion,
                        ciphertext = row.ciphertext,
                        imagePath = row.imagePath,
                        thumbnailCiphertext = row.thumbnailCiphertext,
                        mimeType = row.mimeType,
                        createdAt = row.createdAt,
                        createdAtMicroseconds = micros,
                        state = "pending",
                        failureClass = null,
                    ),
                )
            }
        }
    }

    override suspend fun pendingInbound(channelId: String, limit: Int) =
        dao.loadPendingInbound(channelId, limit.coerceIn(1, 100))

    override suspend fun acknowledgeAndAdvance(itemId: String, cursor: SyncCursor) {
        database.withTransaction {
            dao.updateInbound(itemId, "acknowledged", null)
            dao.upsertCursor(SyncCursorEntity(cursor.channelId, cursor.createdAtMicroseconds, cursor.id))
            dao.deleteAcknowledgedInbound(itemId)
        }
    }

    override suspend fun markPermanentInboundFailure(itemId: String, safeClass: String, cursor: SyncCursor) {
        database.withTransaction {
            dao.updateInbound(itemId, "acknowledged", safeClass)
            dao.upsertCursor(SyncCursorEntity(cursor.channelId, cursor.createdAtMicroseconds, cursor.id))
            dao.deleteAcknowledgedInbound(itemId)
        }
    }

    override suspend fun cursor(channelId: String): SyncCursor? = dao.loadCursor(channelId)?.let {
        SyncCursor(it.channelId, it.createdAtMicroseconds, it.itemId)
    }
}
