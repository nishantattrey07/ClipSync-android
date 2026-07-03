package com.nishantattrey.clipsync.core.sync.image

import androidx.room.withTransaction
import com.nishantattrey.clipsync.core.local.persistence.ClipSyncDatabase
import com.nishantattrey.clipsync.core.local.persistence.InboundTextJournalEntity
import com.nishantattrey.clipsync.core.local.persistence.LocalImageEntity
import com.nishantattrey.clipsync.core.local.persistence.OutboundImageEntity
import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import com.nishantattrey.clipsync.core.protocol.crypto.AuthenticatedEncryption
import com.nishantattrey.clipsync.core.protocol.crypto.DerivedKeys
import com.nishantattrey.clipsync.core.protocol.encoding.WireEncoding
import com.nishantattrey.clipsync.core.protocol.model.ClipboardInsertResult
import com.nishantattrey.clipsync.core.protocol.model.InsertClipboardItemParameters
import com.nishantattrey.clipsync.core.protocol.model.SyncCursor
import com.nishantattrey.clipsync.core.protocol.validation.StoragePathValidator
import com.nishantattrey.clipsync.core.sync.model.ChannelSession
import com.nishantattrey.clipsync.core.sync.model.ClipboardCloudTransport
import com.nishantattrey.clipsync.core.sync.model.Clock
import com.nishantattrey.clipsync.core.sync.model.StorageUploadResult
import com.nishantattrey.clipsync.core.sync.model.SyncFailure
import com.nishantattrey.clipsync.core.sync.model.TransportResult
import com.nishantattrey.clipsync.core.sync.persistence.DurableSyncStore
import java.util.UUID
import com.nishantattrey.clipsync.core.protocol.crypto.Hashes

class ImageSyncEngine(
    private val database: ClipSyncDatabase,
    private val transport: ClipboardCloudTransport,
    private val files: PreparedImageStore,
    private val processor: ImageProcessor,
    private val encryption: AuthenticatedEncryption,
    private val clock: Clock,
) {
    suspend fun promoteLocalCapture(
        image: LocalImageEntity,
        exactEncodedBytes: ByteArray,
        session: ChannelSession,
        keys: DerivedKeys,
    ) {
        require(image.cloudSyncState == "local") { "Only local images can be promoted." }
        require(keys.channelId == session.channelId)
        val validated = processor.validateAndThumbnail(exactEncodedBytes, image.mimeType)
        val encryptedFull = encryption.encrypt(exactEncodedBytes, keys.encryptionKey)
        val encryptedThumbnail = try { encryption.encrypt(validated.thumbnailJpeg, keys.encryptionKey) }
        finally { validated.thumbnailJpeg.fill(0) }
        val uploadFile = try { files.write(image.itemId, encryptedFull, "upload") }
        finally { encryptedFull.fill(0) }
        val thumbnail = try { WireEncoding.standardBase64(encryptedThumbnail) }
        finally { encryptedThumbnail.fill(0) }
        val path = StoragePathValidator.make(UUID.fromString(image.itemId))
        database.withTransaction {
            val dao = database.syncPersistenceDao()
            check(dao.insertOutboundImage(
                OutboundImageEntity(
                    image.itemId, session.channelId, session.identity.deviceId, uploadFile, path,
                    thumbnail, validated.format.mimeType, validated.width, validated.height,
                    ProtocolV1.AES_GCM_NONCE_BYTES + exactEncodedBytes.size + ProtocolV1.AES_GCM_TAG_BYTES,
                    image.createdAtEpochMillis, "prepared", 0, clock.epochMillis(), null,
                ),
            ) != -1L) { "Image upload is already prepared." }
            dao.setLocalImageCloudState(image.itemId, "queued")
        }
    }

    suspend fun prepareCapture(
        itemId: String,
        exactEncodedBytes: ByteArray,
        claimedMimeType: String,
        source: String,
        session: ChannelSession,
        keys: DerivedKeys,
        upload: Boolean,
        fingerprint: ByteArray,
        displayName: String?,
    ) {
        require(keys.channelId == session.channelId)
        val validated = processor.validateAndThumbnail(exactEncodedBytes, claimedMimeType)
        val encryptedFull = encryption.encrypt(exactEncodedBytes, keys.encryptionKey)
        val encryptedThumbnail = try { encryption.encrypt(validated.thumbnailJpeg, keys.encryptionKey) }
        finally { validated.thumbnailJpeg.fill(0) }
        val fileName = try { files.write(itemId, encryptedFull) } finally { encryptedFull.fill(0) }
        val path = StoragePathValidator.make(UUID.fromString(itemId))
        val thumbnail = try { WireEncoding.standardBase64(encryptedThumbnail) } finally { encryptedThumbnail.fill(0) }
        database.withTransaction {
            val dao = database.syncPersistenceDao()
            check(dao.insertLocalImage(
                LocalImageEntity(itemId, fileName, validated.format.mimeType, validated.width, validated.height,
                    clock.epochMillis(), source, null, if (upload) "queued" else "local", false,
                    fingerprint.copyOf(), displayName),
            ) != -1L) { "Image item UUID already exists." }
            if (upload) {
                check(dao.insertOutboundImage(
                    OutboundImageEntity(itemId, session.channelId, session.identity.deviceId, fileName, path,
                        thumbnail, validated.format.mimeType, validated.width, validated.height,
                        ProtocolV1.AES_GCM_NONCE_BYTES + exactEncodedBytes.size + ProtocolV1.AES_GCM_TAG_BYTES,
                        clock.epochMillis(), "prepared", 0, clock.epochMillis(), null),
                ) != -1L) { "Image upload UUID already exists." }
            }
        }
    }

    suspend fun drain(session: ChannelSession, limit: Int = 10): ImageDrainResult {
        val dao = database.syncPersistenceDao()
        var uploaded = 0
        for (entry in dao.loadDueOutboundImages(clock.epochMillis(), limit.coerceIn(1, 25))) {
            if (entry.channelId != session.channelId || entry.deviceId != session.identity.deviceId) continue
            if (entry.state != "object_uploaded") {
                val bytes = try { files.read(entry.preparedFileName) } catch (_: Exception) {
                    dao.updateOutboundImage(entry.itemId, "failed", entry.attemptCount + 1, Long.MAX_VALUE, "missing_prepared_object")
                    dao.setLocalImageCloudState(entry.itemId, "failed")
                    return ImageDrainResult(uploaded, SyncFailure.Rejected("Prepared image is unavailable."))
                }
                val storage = try { transport.uploadEncryptedImage(entry.imagePath, bytes) } finally { bytes.fill(0) }
                when (storage) {
                    is TransportResult.Failure -> {
                        markFailure(entry, storage.failure)
                        return ImageDrainResult(uploaded, storage.failure)
                    }
                    is TransportResult.Success -> when (storage.value) {
                        StorageUploadResult.COLLISION -> {
                            dao.updateOutboundImage(entry.itemId, "failed", entry.attemptCount + 1, Long.MAX_VALUE, "storage_collision")
                            dao.setLocalImageCloudState(entry.itemId, "failed")
                            return ImageDrainResult(uploaded, SyncFailure.Rejected("Encrypted image object collision."))
                        }
                        StorageUploadResult.UPLOADED, StorageUploadResult.IDENTICAL_EXISTING ->
                            dao.updateOutboundImage(entry.itemId, "object_uploaded", entry.attemptCount, clock.epochMillis(), null)
                    }
                }
            }
            val inserted = transport.insertText(
                InsertClipboardItemParameters(
                    entry.itemId, entry.channelId, entry.deviceId, session.identity.deviceSecret, "image",
                    ProtocolV1.PAYLOAD_VERSION, imagePath = entry.imagePath,
                    thumbnailCiphertext = entry.thumbnailCiphertext, mimeType = entry.mimeType,
                ),
            )
            when (inserted) {
                is TransportResult.Success -> {
                    require(inserted.value == ClipboardInsertResult.INSERTED || inserted.value == ClipboardInsertResult.ALREADY_PRESENT)
                    dao.updateOutboundImage(entry.itemId, "synced", entry.attemptCount + 1, Long.MAX_VALUE, null)
                    dao.setLocalImageCloudState(entry.itemId, "synced")
                    val localFile = dao.findLocalImage(entry.itemId)?.encryptedFileName
                    if (localFile != null && localFile != entry.preparedFileName) {
                        files.delete(entry.preparedFileName)
                    }
                    uploaded++
                }
                is TransportResult.Failure -> {
                    markFailure(entry.copy(state = "object_uploaded"), inserted.failure)
                    return ImageDrainResult(uploaded, inserted.failure)
                }
            }
        }
        return ImageDrainResult(uploaded, null)
    }

    private suspend fun markFailure(entry: OutboundImageEntity, failure: SyncFailure) {
        val retryable = failure == SyncFailure.Offline || failure == SyncFailure.TimedOut || failure == SyncFailure.TemporarilyUnavailable
        database.syncPersistenceDao().updateOutboundImage(
            entry.itemId,
            if (retryable) if (entry.state == "object_uploaded") "object_uploaded" else "retry" else "failed",
            entry.attemptCount + 1,
            if (retryable) clock.epochMillis() + (1_000L shl entry.attemptCount.coerceIn(0, 8)) else Long.MAX_VALUE,
            failure::class.simpleName ?: "image_failure",
        )
        database.syncPersistenceDao().setLocalImageCloudState(
            entry.itemId,
            if (retryable) "retrying" else "failed",
        )
    }
}

class ImageInboundHandler(
    private val database: ClipSyncDatabase,
    private val transport: ClipboardCloudTransport,
    private val files: PreparedImageStore,
    private val processor: ImageProcessor,
    private val encryption: AuthenticatedEncryption,
    private val durable: DurableSyncStore,
) {
    suspend fun process(entry: InboundTextJournalEntity, cursor: SyncCursor, keys: DerivedKeys): Boolean {
        val path = entry.imagePath ?: return permanent(entry, cursor, "missing_image_path")
        val mime = entry.mimeType ?: return permanent(entry, cursor, "missing_image_mime")
        try { StoragePathValidator.validate(path) } catch (_: IllegalArgumentException) {
            return permanent(entry, cursor, "invalid_image_path")
        }
        val downloaded = when (val result = transport.downloadEncryptedImage(path)) {
            is TransportResult.Failure -> return false
            is TransportResult.Success -> result.value
        }
        try {
            val plaintext = try { encryption.decrypt(downloaded, keys.encryptionKey) }
            catch (_: SecurityException) { return permanent(entry, cursor, "image_authentication_failed") }
            val validated = try { processor.validateAndThumbnail(plaintext, mime) }
            catch (_: IllegalArgumentException) { return permanent(entry, cursor, "invalid_image_payload") }
            finally { plaintext.fill(0) }
            try {
                val fileName = files.write(entry.itemId, downloaded)
                database.syncPersistenceDao().insertLocalImage(
                    LocalImageEntity(entry.itemId, fileName, mime, validated.width, validated.height,
                        entry.createdAtMicroseconds / 1_000L, "CLOUD", entry.deviceId, "received", false,
                        Hashes.sha256(downloaded), null),
                )
                durable.acknowledgeAndAdvance(entry.itemId, cursor)
                return true
            } finally { validated.thumbnailJpeg.fill(0) }
        } finally { downloaded.fill(0) }
    }

    private suspend fun permanent(entry: InboundTextJournalEntity, cursor: SyncCursor, reason: String): Boolean {
        durable.markPermanentInboundFailure(entry.itemId, reason, cursor)
        return true
    }
}

data class ImageDrainResult(val uploaded: Int, val stoppedBy: SyncFailure?)
