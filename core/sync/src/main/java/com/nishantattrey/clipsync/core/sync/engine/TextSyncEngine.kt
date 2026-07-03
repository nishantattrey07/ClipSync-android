package com.nishantattrey.clipsync.core.sync.engine

import com.nishantattrey.clipsync.core.local.model.LocalDataResult
import com.nishantattrey.clipsync.core.local.persistence.OutboundTextEntity
import com.nishantattrey.clipsync.core.local.repository.LocalClipboardRepository
import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import com.nishantattrey.clipsync.core.protocol.crypto.AuthenticatedEncryption
import com.nishantattrey.clipsync.core.protocol.crypto.DerivedKeys
import com.nishantattrey.clipsync.core.protocol.encoding.WireEncoding
import com.nishantattrey.clipsync.core.protocol.model.ClipboardInsertResult
import com.nishantattrey.clipsync.core.protocol.model.FetchAfterParameters
import com.nishantattrey.clipsync.core.protocol.model.InsertClipboardItemParameters
import com.nishantattrey.clipsync.core.protocol.model.SyncCursor
import com.nishantattrey.clipsync.core.protocol.validation.ClipboardRowValidator
import com.nishantattrey.clipsync.core.protocol.validation.ServerTimestampCodec
import com.nishantattrey.clipsync.core.sync.model.ChannelSession
import com.nishantattrey.clipsync.core.sync.model.ClipboardCloudTransport
import com.nishantattrey.clipsync.core.sync.model.Clock
import com.nishantattrey.clipsync.core.sync.model.SyncFailure
import com.nishantattrey.clipsync.core.sync.model.TransportResult
import com.nishantattrey.clipsync.core.sync.persistence.DurableSyncStore
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException

class TextSyncEngine(
    private val transport: ClipboardCloudTransport,
    private val queue: DurableSyncStore,
    private val local: LocalClipboardRepository,
    private val encryption: AuthenticatedEncryption,
    private val clock: Clock,
) {
    suspend fun prepareOutbound(
        itemId: String,
        exactText: String,
        createdAtEpochMillis: Long,
        session: ChannelSession,
        keys: DerivedKeys,
    ) {
        require(keys.channelId == session.channelId) { "Active channel does not match derived keys." }
        val plaintext = exactText.toByteArray(StandardCharsets.UTF_8)
        try {
            require(plaintext.isNotEmpty()) { "Empty text cannot be synchronized." }
            require(plaintext.size <= ProtocolV1.MAX_TEXT_PLAINTEXT_BYTES) { "Text exceeds the V1 limit." }
            val encrypted = encryption.encrypt(plaintext, keys.encryptionKey)
            try {
                queue.enqueuePrepared(
                    OutboundTextEntity(
                        itemId = itemId,
                        channelId = session.channelId,
                        deviceId = session.identity.deviceId,
                        ciphertext = WireEncoding.standardBase64(encrypted),
                        payloadVersion = ProtocolV1.PAYLOAD_VERSION,
                        createdAtEpochMillis = createdAtEpochMillis,
                        state = "pending",
                        attemptCount = 0,
                        nextAttemptAtEpochMillis = createdAtEpochMillis,
                        lastFailure = null,
                    ),
                )
            } finally {
                encrypted.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    suspend fun drainOutbound(session: ChannelSession, limit: Int = 25): DrainResult {
        var uploaded = 0
        for (entry in queue.dueOutbound(clock.epochMillis(), limit)) {
            if (entry.channelId != session.channelId || entry.deviceId != session.identity.deviceId) continue
            val parameters = InsertClipboardItemParameters(
                id = entry.itemId,
                channelId = entry.channelId,
                deviceId = entry.deviceId,
                deviceSecret = session.identity.deviceSecret,
                kind = "text",
                payloadVersion = entry.payloadVersion,
                ciphertext = entry.ciphertext,
            )
            when (val result = transport.insertText(parameters)) {
                is TransportResult.Success -> {
                    require(result.value == ClipboardInsertResult.INSERTED || result.value == ClipboardInsertResult.ALREADY_PRESENT)
                    queue.markOutbound(entry, "synced", Long.MAX_VALUE, null)
                    uploaded++
                }
                is TransportResult.Failure -> {
                    val retryAt = retryAt(entry.attemptCount, result.failure)
                    if (retryAt == null) queue.markOutbound(entry, "failed", Long.MAX_VALUE, failureClass(result.failure))
                    else queue.markOutbound(entry, "retry", retryAt, failureClass(result.failure))
                    return DrainResult(uploaded, result.failure)
                }
            }
        }
        return DrainResult(uploaded, null)
    }

    suspend fun catchUp(session: ChannelSession, keys: DerivedKeys): CatchUpResult {
        require(keys.channelId == session.channelId) { "Active channel does not match derived keys." }
        var received = 0
        while (true) {
            val cursor = queue.cursor(session.channelId)
            val response = transport.fetchAfter(
                FetchAfterParameters(
                    channelId = session.channelId,
                    limit = ProtocolV1.PAGE_SIZE,
                    afterTimestamp = cursor?.let { ServerTimestampCodec.formatMicroseconds(it.createdAtMicroseconds) },
                    afterId = cursor?.id,
                ),
            )
            val rows = when (response) {
                is TransportResult.Success -> response.value
                is TransportResult.Failure -> return CatchUpResult(received, response.failure)
            }
            val validated = rows.map { row ->
                ClipboardRowValidator.validate(row, session.channelId)
                require(row.kind == "text") { "Gate 3 accepts text rows only." }
                row to ServerTimestampCodec.parseMicroseconds(row.createdAt)
            }
            require(validated.zipWithNext().all { (a, b) ->
                a.second < b.second || (a.second == b.second && a.first.id < b.first.id)
            }) { "Catch-up page is not canonically ordered." }
            queue.journalPage(validated)
            processJournal(session, keys)
            received += validated.count { it.first.deviceId != session.identity.deviceId }
            if (rows.size < ProtocolV1.PAGE_SIZE) return CatchUpResult(received, null)
        }
    }

    private suspend fun processJournal(session: ChannelSession, keys: DerivedKeys) {
        for (entry in queue.pendingInbound(session.channelId)) {
            val cursor = SyncCursor(entry.channelId, entry.createdAtMicroseconds, entry.itemId)
            if (entry.deviceId == session.identity.deviceId) {
                queue.acknowledgeAndAdvance(entry.itemId, cursor)
                continue
            }
            val encrypted = try {
                WireEncoding.decodeStandardBase64(entry.ciphertext)
            } catch (_: IllegalArgumentException) {
                queue.markPermanentInboundFailure(entry.itemId, "invalid_ciphertext", cursor)
                continue
            }
            val plaintext = try {
                try {
                    encryption.decrypt(encrypted, keys.encryptionKey)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: SecurityException) {
                    queue.markPermanentInboundFailure(entry.itemId, "authentication_failed", cursor)
                    continue
                } catch (_: IllegalArgumentException) {
                    queue.markPermanentInboundFailure(entry.itemId, "invalid_envelope", cursor)
                    continue
                }
            } finally {
                encrypted.fill(0)
            }
            try {
                if (plaintext.isEmpty() || plaintext.size > ProtocolV1.MAX_TEXT_PLAINTEXT_BYTES) {
                    queue.markPermanentInboundFailure(entry.itemId, "invalid_plaintext_size", cursor)
                    continue
                }
                val text = decodeUtf8Strict(plaintext) ?: run {
                    queue.markPermanentInboundFailure(entry.itemId, "invalid_utf8", cursor)
                    continue
                }
                when (local.storeInbound(entry.itemId, text, entry.createdAtMicroseconds / 1_000L)) {
                    is LocalDataResult.Success -> queue.acknowledgeAndAdvance(entry.itemId, cursor)
                    is LocalDataResult.CorruptItem -> return
                    is LocalDataResult.RecoveryRequired -> return
                }
            } finally {
                plaintext.fill(0)
            }
        }
    }

    private fun retryAt(attemptCount: Int, failure: SyncFailure): Long? = when (failure) {
        SyncFailure.Offline, SyncFailure.TimedOut, SyncFailure.TemporarilyUnavailable -> {
            val exponent = attemptCount.coerceIn(0, 8)
            clock.epochMillis() + boundedRetryDelayMillis(exponent)
        }
        else -> null
    }

    private fun failureClass(failure: SyncFailure): String = failure::class.simpleName ?: "sync_failure"

    private fun decodeUtf8Strict(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }
}

internal fun boundedRetryDelayMillis(exponent: Int): Long =
    (1_000L shl exponent.coerceIn(0, 8)).coerceAtMost(300_000L)

data class DrainResult(val uploaded: Int, val stoppedBy: SyncFailure?)
data class CatchUpResult(val received: Int, val stoppedBy: SyncFailure?)
