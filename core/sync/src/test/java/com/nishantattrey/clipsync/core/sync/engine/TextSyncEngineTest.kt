package com.nishantattrey.clipsync.core.sync.engine

import com.nishantattrey.clipsync.core.local.model.CaptureResult
import com.nishantattrey.clipsync.core.local.model.CaptureSource
import com.nishantattrey.clipsync.core.local.model.LocalClipboardItem
import com.nishantattrey.clipsync.core.local.model.LocalDataResult
import com.nishantattrey.clipsync.core.local.model.RetentionPeriod
import com.nishantattrey.clipsync.core.local.persistence.InboundTextJournalEntity
import com.nishantattrey.clipsync.core.local.persistence.OutboundTextEntity
import com.nishantattrey.clipsync.core.local.repository.LocalClipboardRepository
import com.nishantattrey.clipsync.core.protocol.crypto.AuthenticatedEncryption
import com.nishantattrey.clipsync.core.protocol.crypto.DerivedKeys
import com.nishantattrey.clipsync.core.protocol.encoding.WireEncoding
import com.nishantattrey.clipsync.core.protocol.model.ClipboardDeviceRecord
import com.nishantattrey.clipsync.core.protocol.model.ClipboardInsertResult
import com.nishantattrey.clipsync.core.protocol.model.ClipboardItemRecord
import com.nishantattrey.clipsync.core.protocol.model.FetchAfterParameters
import com.nishantattrey.clipsync.core.protocol.model.InsertClipboardItemParameters
import com.nishantattrey.clipsync.core.protocol.model.RegisterDeviceParameters
import com.nishantattrey.clipsync.core.protocol.model.SyncCursor
import com.nishantattrey.clipsync.core.sync.model.ChannelSession
import com.nishantattrey.clipsync.core.sync.model.ClipboardCloudTransport
import com.nishantattrey.clipsync.core.sync.model.CloudEndpoint
import com.nishantattrey.clipsync.core.sync.model.Clock
import com.nishantattrey.clipsync.core.sync.model.PermanentDeviceIdentity
import com.nishantattrey.clipsync.core.sync.model.SyncFailure
import com.nishantattrey.clipsync.core.sync.model.TransportResult
import com.nishantattrey.clipsync.core.sync.persistence.DurableSyncStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException

class TextSyncEngineTest {
    private val channel = "ab".repeat(32)
    private val device = "00000000-0000-0000-0000-000000000001"
    private val sender = "00000000-0000-0000-0000-000000000002"
    private val session = ChannelSession(CloudEndpoint("https://example.test", "public"), channel, PermanentDeviceIdentity(device, "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"))
    private val keys = DerivedKeys(channel, ByteArray(32) { 7 }, ByteArray(32) { 8 })
    private val crypto = PrefixCrypto()

    @Test fun retryReusesExactUuidAndCiphertextAfterProcessDeath() = runTest {
        val store = FakeStore()
        val transport = FakeTransport(mutableListOf(SyncFailure.Offline, null))
        engine(store, transport).prepareOutbound(ITEM_ONE, " exact\r\ntext ", 1_000, session, keys)
        val prepared = store.outbound.single()
        engine(store, transport).drainOutbound(session)
        store.outbound[0] = store.outbound[0].copy(nextAttemptAtEpochMillis = 2_000)
        engine(store, transport, now = 2_000).drainOutbound(session)
        assertEquals(listOf(ITEM_ONE, ITEM_ONE), transport.uploads.map { it.id })
        assertEquals(listOf(prepared.ciphertext, prepared.ciphertext), transport.uploads.map { it.ciphertext })
        assertEquals("synced", store.outbound.single().state)
    }

    @Test fun serverSuccessBeforeLocalAckRetriesAsAlreadyPresentWithoutChangingBytes() = runTest {
        val store = FakeStore(failFirstAcknowledgement = true)
        val transport = FakeTransport(mutableListOf(null, null))
        engine(store, transport).prepareOutbound(ITEM_ONE, "hello", 1_000, session, keys)
        runCatching { engine(store, transport).drainOutbound(session) }
        engine(store, transport).drainOutbound(session)
        assertEquals(2, transport.uploads.size)
        assertEquals(transport.uploads[0], transport.uploads[1])
    }

    @Test fun inboundIsJournaledBeforeDecryptAndPreservesWhitespace() = runTest {
        val events = mutableListOf<String>()
        val store = FakeStore(events = events)
        val local = FakeLocal(events)
        val encrypted = WireEncoding.standardBase64(crypto.encrypt(" \nvalue\r\n ".encodeToByteArray(), keys.encryptionKey))
        val transport = FakeTransport(rows = listOf(row(ITEM_ONE, sender, "2026-01-01T00:00:00.000001Z", encrypted)))
        engine(store, transport, local = local).catchUp(session, keys)
        assertEquals(listOf("journal", "store", "cursor", "delete"), events)
        assertEquals(" \nvalue\r\n ", local.items.single().text)
    }

    @Test fun sameTimestampUsesCanonicalUuidOrderAndExcludesOwnDevice() = runTest {
        val ciphertext = WireEncoding.standardBase64(crypto.encrypt("x".encodeToByteArray(), keys.encryptionKey))
        val rows = listOf(
            row(ITEM_ONE, device, TS, ciphertext),
            row(ITEM_TWO, sender, TS, ciphertext),
        )
        val store = FakeStore()
        val local = FakeLocal()
        val result = engine(store, FakeTransport(rows = rows), local = local).catchUp(session, keys)
        assertEquals(1, result.received)
        assertEquals(listOf(ITEM_TWO), local.items.map { it.id })
        assertEquals(ITEM_TWO, store.savedCursor?.id)
    }

    @Test fun corruptAuthenticationIsAcknowledgedAndDoesNotRemainForever() = runTest {
        val store = FakeStore()
        val transport = FakeTransport(rows = listOf(row(ITEM_ONE, sender, TS, WireEncoding.standardBase64(ByteArray(28) { 99 }))))
        engine(store, transport).catchUp(session, keys)
        assertTrue(store.journal.isEmpty())
        assertEquals(ITEM_ONE, store.savedCursor?.id)
        assertEquals("authentication_failed", store.permanentFailures.single())
    }

    @Test fun retryBackoffIsBoundedWithoutWallClockSleeps() {
        assertEquals(1_000L, boundedRetryDelayMillis(0))
        assertEquals(256_000L, boundedRetryDelayMillis(8))
        assertEquals(256_000L, boundedRetryDelayMillis(Int.MAX_VALUE))
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNeverConvertedIntoRetry() = runTest {
        val store = FakeStore()
        engine(store, CancellingTransport()).prepareOutbound(ITEM_ONE, "text", 1_000, session, keys)
        engine(store, CancellingTransport()).drainOutbound(session)
    }

    private fun engine(
        store: FakeStore,
        transport: ClipboardCloudTransport,
        local: FakeLocal = FakeLocal(),
        now: Long = 1_000,
    ) =
        TextSyncEngine(transport, store, local, crypto, Clock { now })

    private fun row(id: String, deviceId: String, timestamp: String, ciphertext: String) =
        ClipboardItemRecord(id, channel, deviceId, "text", 1, ciphertext = ciphertext, createdAt = timestamp)

    private companion object {
        const val ITEM_ONE = "00000000-0000-0000-0000-000000000010"
        const val ITEM_TWO = "00000000-0000-0000-0000-000000000020"
        const val TS = "2026-01-01T00:00:00.000001Z"
    }
}

private class CancellingTransport : ClipboardCloudTransport {
    override suspend fun registerDevice(parameters: RegisterDeviceParameters) = TransportResult.Success(Unit)
    override suspend fun insertText(parameters: InsertClipboardItemParameters): TransportResult<ClipboardInsertResult> =
        throw CancellationException("cancelled")
    override suspend fun fetchAfter(parameters: FetchAfterParameters) = TransportResult.Success(emptyList<ClipboardItemRecord>())
    override suspend fun fetchDevices(channelId: String) = TransportResult.Success(emptyList<ClipboardDeviceRecord>())
}

private class PrefixCrypto : AuthenticatedEncryption {
    override fun encrypt(plaintext: ByteArray, key: ByteArray) = ByteArray(28) { 1 } + plaintext
    override fun decrypt(combined: ByteArray, key: ByteArray): ByteArray {
        if (combined.take(28).any { it != 1.toByte() }) throw SecurityException("auth")
        return combined.copyOfRange(28, combined.size)
    }
}

private class FakeTransport(
    private val failures: MutableList<SyncFailure?> = mutableListOf(),
    private val rows: List<ClipboardItemRecord> = emptyList(),
) : ClipboardCloudTransport {
    val uploads = mutableListOf<InsertClipboardItemParameters>()
    override suspend fun registerDevice(parameters: RegisterDeviceParameters) = TransportResult.Success(Unit)
    override suspend fun insertText(parameters: InsertClipboardItemParameters): TransportResult<ClipboardInsertResult> {
        uploads += parameters
        val failure = if (failures.isEmpty()) null else failures.removeAt(0)
        return failure?.let { TransportResult.Failure(it) }
            ?: TransportResult.Success(if (uploads.size == 1) ClipboardInsertResult.INSERTED else ClipboardInsertResult.ALREADY_PRESENT)
    }
    override suspend fun fetchAfter(parameters: FetchAfterParameters) = TransportResult.Success(rows)
    override suspend fun fetchDevices(channelId: String) = TransportResult.Success(emptyList<ClipboardDeviceRecord>())
}

private class FakeStore(
    private val failFirstAcknowledgement: Boolean = false,
    private val events: MutableList<String> = mutableListOf(),
) : DurableSyncStore {
    val outbound = mutableListOf<OutboundTextEntity>()
    val journal = mutableListOf<InboundTextJournalEntity>()
    val permanentFailures = mutableListOf<String>()
    var savedCursor: SyncCursor? = null
    private var acknowledgementFailed = false
    override suspend fun enqueuePrepared(entity: OutboundTextEntity): Boolean {
        if (outbound.any { it.itemId == entity.itemId }) return false
        outbound += entity; return true
    }
    override suspend fun dueOutbound(now: Long, limit: Int) = outbound.filter { it.state in setOf("pending", "retry") && it.nextAttemptAtEpochMillis <= now }.take(limit)
    override suspend fun markOutbound(entity: OutboundTextEntity, state: String, nextAttemptAt: Long, failure: String?): Int {
        if (failFirstAcknowledgement && !acknowledgementFailed && state == "synced") {
            acknowledgementFailed = true
            throw IllegalStateException("simulated process death")
        }
        val index = outbound.indexOfFirst { it.itemId == entity.itemId }
        outbound[index] = entity.copy(state = state, attemptCount = entity.attemptCount + 1, nextAttemptAtEpochMillis = nextAttemptAt, lastFailure = failure)
        return 1
    }
    override suspend fun journalPage(rows: List<Pair<ClipboardItemRecord, Long>>) {
        events += "journal"
        rows.forEach { (row, micros) -> if (journal.none { it.itemId == row.id }) journal += InboundTextJournalEntity(
            row.id, row.channelId, row.deviceId, row.kind, row.payloadVersion, row.ciphertext,
            row.imagePath, row.thumbnailCiphertext, row.mimeType, row.createdAt, micros, "pending", null,
        ) }
    }
    override suspend fun pendingInbound(channelId: String, limit: Int) = journal.filter { it.channelId == channelId }.take(limit)
    override suspend fun acknowledgeAndAdvance(itemId: String, cursor: SyncCursor) { savedCursor = cursor; events += "cursor"; journal.removeAll { it.itemId == itemId }; events += "delete" }
    override suspend fun markPermanentInboundFailure(itemId: String, safeClass: String, cursor: SyncCursor) { permanentFailures += safeClass; acknowledgeAndAdvance(itemId, cursor) }
    override suspend fun cursor(channelId: String) = savedCursor
}

private class FakeLocal(private val events: MutableList<String> = mutableListOf()) : LocalClipboardRepository {
    val items = mutableListOf<LocalClipboardItem>()
    override val changes = MutableStateFlow(0)
    override suspend fun capture(text: String, source: CaptureSource) = LocalDataResult.Success(CaptureResult.Stored("unused"))
    override suspend fun storeInbound(id: String, text: String, createdAtEpochMillis: Long): LocalDataResult<Boolean> {
        events += "store"
        if (items.any { it.id == id }) return LocalDataResult.Success(false)
        items += LocalClipboardItem(id, text, createdAtEpochMillis, CaptureSource.CLOUD, false)
        return LocalDataResult.Success(true)
    }
    override suspend fun page(bookmarksOnly: Boolean, before: LocalClipboardItem?, limit: Int) = LocalDataResult.Success(items.toList())
    override suspend fun search(query: String, bookmarksOnly: Boolean) = LocalDataResult.Success(items.toList())
    override suspend fun find(id: String) = LocalDataResult.Success(items.firstOrNull { it.id == id })
    override suspend fun setBookmarked(id: String, bookmarked: Boolean) = false
    override suspend fun delete(id: String) = false
    override suspend fun clearUnbookmarked() = 0
    override suspend fun applyRetention(period: RetentionPeriod) = 0
}
