package com.nishantattrey.clipsync.core.local

import com.nishantattrey.clipsync.core.local.crypto.AesGcmLocalPayloadCipher
import com.nishantattrey.clipsync.core.local.crypto.HmacSha256LocalFingerprint
import com.nishantattrey.clipsync.core.local.crypto.LocalEnvelopeCodec
import com.nishantattrey.clipsync.core.local.crypto.LocalEncryptionEnvelope
import com.nishantattrey.clipsync.core.local.crypto.LocalKeyMaterial
import com.nishantattrey.clipsync.core.local.crypto.LocalFingerprint
import com.nishantattrey.clipsync.core.local.crypto.LocalPayloadCipher
import com.nishantattrey.clipsync.core.local.crypto.InvalidatedLocalKeyException
import com.nishantattrey.clipsync.core.local.model.CaptureResult
import com.nishantattrey.clipsync.core.local.model.CaptureSource
import com.nishantattrey.clipsync.core.local.model.EmptyCaptureException
import com.nishantattrey.clipsync.core.local.model.LocalDataResult
import com.nishantattrey.clipsync.core.local.model.LocalKeyPurpose
import com.nishantattrey.clipsync.core.local.persistence.LocalClipboardDao
import com.nishantattrey.clipsync.core.local.persistence.LocalClipboardEntity
import com.nishantattrey.clipsync.core.local.repository.EpochMillisClock
import com.nishantattrey.clipsync.core.local.repository.LocalIdGenerator
import com.nishantattrey.clipsync.core.local.repository.RoomLocalClipboardRepository
import com.nishantattrey.clipsync.core.local.repository.LocalRecoveryCoordinator
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRepositoryTest {
    @Test
    fun `capture preserves whitespace and deduplicates with keyed fingerprint`() = runTest {
        val dao = FakeDao()
        val repository = repository(dao)
        val exact = "  line one\r\nline two  "

        val first = repository.capture(exact, CaptureSource.COMPOSER) as LocalDataResult.Success
        val duplicate = repository.capture(exact, CaptureSource.SHARE) as LocalDataResult.Success
        val loaded = repository.page(false) as LocalDataResult.Success

        assertTrue(first.value is CaptureResult.Stored)
        assertEquals((first.value as CaptureResult.Stored).id, (duplicate.value as CaptureResult.Duplicate).id)
        assertEquals(exact, loaded.value.single().text)
        assertEquals(1, dao.items.size)
        assertTrue(!dao.items.single().fingerprint.contentEquals(exact.toByteArray()))
        assertTrue(!String(dao.items.single().encryptedPayload).contains("line one"))
    }

    @Test
    fun `only truly empty strings are rejected`() = runTest {
        val repository = repository(FakeDao())
        var rejected = false
        try {
            repository.capture("", CaptureSource.COMPOSER)
        } catch (_: EmptyCaptureException) {
            rejected = true
        }
        assertTrue(rejected)
        assertTrue(repository.capture("   ", CaptureSource.COMPOSER) is LocalDataResult.Success)
    }

    @Test
    fun `equal timestamps are ordered by descending UUID`() = runTest {
        val dao = FakeDao()
        val ids = ArrayDeque(listOf("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002"))
        val repository = repository(dao, LocalIdGenerator { ids.removeFirst() })
        repository.capture("first", CaptureSource.COMPOSER)
        repository.capture("second", CaptureSource.COMPOSER)

        val loaded = repository.page(false) as LocalDataResult.Success
        assertEquals("second", loaded.value[0].text)
        assertEquals("first", loaded.value[1].text)
    }

    @Test
    fun `search decrypts bounded chunks and returns bounded results`() = runTest {
        val dao = FakeDao()
        val counter = java.util.concurrent.atomic.AtomicInteger()
        val repository = repository(dao, LocalIdGenerator { "%08d-0000-0000-0000-000000000000".format(counter.incrementAndGet()) })
        repeat(200) { repository.capture("entry-$it", CaptureSource.COMPOSER) }

        val result = repository.search("not-present") as LocalDataResult.Success
        assertTrue(result.value.isEmpty())
        assertTrue(dao.loadPageCalls <= 7)
    }

    @Test
    fun `bookmarks survive cap and clear history`() = runTest {
        val dao = FakeDao()
        val counter = java.util.concurrent.atomic.AtomicInteger()
        val repository = repository(dao, LocalIdGenerator { "%08d-0000-0000-0000-000000000000".format(counter.incrementAndGet()) })
        repository.capture("bookmark", CaptureSource.COMPOSER)
        val bookmarkId = dao.items.single().id
        repository.setBookmarked(bookmarkId, true)
        repeat(201) { repository.capture("entry-$it", CaptureSource.COMPOSER) }

        assertEquals(201, dao.items.size)
        repository.clearUnbookmarked()
        assertEquals(listOf(bookmarkId), dao.items.map { it.id })
    }

    @Test
    fun `history pages expose all two hundred retained items`() = runTest {
        val dao = FakeDao()
        val counter = java.util.concurrent.atomic.AtomicInteger()
        val repository = repository(dao, LocalIdGenerator { "%08d-0000-0000-0000-000000000000".format(counter.incrementAndGet()) })
        repeat(200) { repository.capture("entry-$it", CaptureSource.COMPOSER) }

        val first = (repository.page(false) as LocalDataResult.Success).value
        val second = (repository.page(false, first.last()) as LocalDataResult.Success).value
        assertEquals(100, first.size)
        assertEquals(100, second.size)
        assertEquals(200, (first + second).map { it.id }.toSet().size)
    }

    @Test
    fun `recovery probes classify unusable and temporary keys`() = runTest {
        val invalidated = LocalRecoveryCoordinator(
            FakeDao(),
            FakeKeys(),
            ProbeCipher(InvalidatedLocalKeyException(LocalKeyPurpose.PAYLOAD_ENCRYPTION, InvalidKeyException())),
            ProbeFingerprint(),
            Dispatchers.Unconfined,
        ).state()
        assertTrue(invalidated is com.nishantattrey.clipsync.core.local.model.LocalRecoveryState.InvalidatedKeys)

        val temporary = LocalRecoveryCoordinator(
            FakeDao(),
            FakeKeys(),
            ProbeCipher(),
            ProbeFingerprint(GeneralSecurityException("temporarily unavailable")),
            Dispatchers.Unconfined,
        ).state()
        assertTrue(temporary is com.nishantattrey.clipsync.core.local.model.LocalRecoveryState.TemporarilyUnavailable)
    }

    @Test
    fun `envelope rejects AAD row swapping`() {
        val cipher = AesGcmLocalPayloadCipher(FakeKeys())
        val encrypted = cipher.encrypt("one", "secret".toByteArray())
        var rejected = false
        try {
            cipher.decrypt("two", encrypted)
        } catch (_: Exception) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun `envelope codec is versioned and exact`() {
        val envelope = LocalEncryptionEnvelope(1, ByteArray(12) { it.toByte() }, ByteArray(16) { (it + 1).toByte() })
        val decoded = LocalEnvelopeCodec.decode(LocalEnvelopeCodec.encode(envelope))
        assertEquals(1, decoded.version)
        assertArrayEquals(envelope.nonce, decoded.nonce)
        assertArrayEquals(envelope.ciphertextAndTag, decoded.ciphertextAndTag)
    }

    @Test
    fun `entity envelope version mismatch is reported as corruption`() = runTest {
        val dao = FakeDao()
        val repository = repository(dao)
        repository.capture("value", CaptureSource.COMPOSER)
        dao.items[0] = dao.items[0].copy(envelopeVersion = 2)

        val result = repository.page(false)
        assertTrue(result is LocalDataResult.CorruptItem)
    }

    private fun repository(dao: FakeDao, ids: LocalIdGenerator = LocalIdGenerator { "00000000-0000-0000-0000-000000000001" }) =
        RoomLocalClipboardRepository(
            dao = dao,
            cipher = AesGcmLocalPayloadCipher(FakeKeys()),
            fingerprint = HmacSha256LocalFingerprint(FakeKeys()),
            clock = EpochMillisClock { 100L },
            ids = ids,
            ioDispatcher = Dispatchers.Unconfined,
        )
}

private class FakeKeys : LocalKeyMaterial {
    private val values = mapOf(
        LocalKeyPurpose.PAYLOAD_ENCRYPTION to KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(),
        LocalKeyPurpose.DEDUP_FINGERPRINT to KeyGenerator.getInstance("HmacSHA256").apply { init(256) }.generateKey(),
    )
    override fun get(purpose: LocalKeyPurpose): SecretKey? = values[purpose]
    override fun getOrCreate(purpose: LocalKeyPurpose): SecretKey = requireNotNull(values[purpose])
    override fun deleteAll() = Unit
}

private class ProbeCipher(private val failure: GeneralSecurityException? = null) : LocalPayloadCipher {
    override fun encrypt(id: String, plaintext: ByteArray) = byteArrayOf()
    override fun decrypt(id: String, envelope: ByteArray) = byteArrayOf()
    override fun verifyUsable() { failure?.let { throw it } }
}

private class ProbeFingerprint(private val failure: GeneralSecurityException? = null) : LocalFingerprint {
    override fun compute(plaintext: ByteArray) = ByteArray(32)
    override fun verifyUsable() { failure?.let { throw it } }
}

private class FakeDao : LocalClipboardDao {
    val items = mutableListOf<LocalClipboardEntity>()
    var loadPageCalls = 0
    private val count = MutableStateFlow(0)

    override suspend fun insert(entity: LocalClipboardEntity): Long {
        if (items.any { it.id == entity.id }) return -1
        items += entity
        count.value = items.size
        return items.size.toLong()
    }
    override suspend fun findByFingerprint(fingerprint: ByteArray) = items.find { it.fingerprint.contentEquals(fingerprint) }
    override suspend fun findById(id: String) = items.find { it.id == id }
    override suspend fun loadUnsynced(limit: Int) = items
        .filter { it.cloudSyncState == "local" }
        .sortedWith(compareBy<LocalClipboardEntity> { it.createdAtEpochMillis }.thenBy { it.id })
        .take(limit)
    override suspend fun setCloudSyncState(id: String, state: String): Int {
        val index = items.indexOfFirst { it.id == id }; if (index < 0) return 0
        items[index] = items[index].copy(cloudSyncState = state); return 1
    }
    override suspend fun loadPage(bookmarksOnly: Boolean, beforeCreated: Long?, beforeId: String?, limit: Int) =
        items.asSequence().also { loadPageCalls++ }.filter { !bookmarksOnly || it.isBookmarked }
            .filter { beforeCreated == null || it.createdAtEpochMillis < beforeCreated ||
                (it.createdAtEpochMillis == beforeCreated && it.id < requireNotNull(beforeId)) }
            .sortedWith(compareByDescending<LocalClipboardEntity> { it.createdAtEpochMillis }.thenByDescending { it.id })
            .take(limit).toList()
    override suspend fun setBookmarked(id: String, bookmarked: Boolean): Int {
        val index = items.indexOfFirst { it.id == id }; if (index < 0) return 0
        items[index] = items[index].copy(isBookmarked = bookmarked); return 1
    }
    override suspend fun deleteById(id: String): Int = if (items.removeAll { it.id == id }) 1 else 0
    override suspend fun clearUnbookmarked(): Int { val old = items.size; items.removeAll { !it.isBookmarked }; return old - items.size }
    override suspend fun deleteUnbookmarkedOlderThan(cutoff: Long): Int { val old = items.size; items.removeAll { !it.isBookmarked && it.createdAtEpochMillis < cutoff }; return old - items.size }
    override suspend fun trimUnbookmarked(keepCount: Int): Int {
        val keep = items.filter { !it.isBookmarked }.sortedWith(compareByDescending<LocalClipboardEntity> { it.createdAtEpochMillis }.thenByDescending { it.id }).take(keepCount).map { it.id }.toSet()
        val old = items.size; items.removeAll { !it.isBookmarked && it.id !in keep }; return old - items.size
    }
    override suspend fun count() = items.size
    override fun observeCount(): Flow<Int> = count
    override suspend fun deleteAll(): Int { val old = items.size; items.clear(); count.value = 0; return old }
}
