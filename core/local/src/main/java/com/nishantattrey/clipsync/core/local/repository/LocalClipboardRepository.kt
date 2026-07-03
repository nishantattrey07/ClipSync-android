package com.nishantattrey.clipsync.core.local.repository

import com.nishantattrey.clipsync.core.local.crypto.CorruptLocalPayloadException
import com.nishantattrey.clipsync.core.local.crypto.InvalidatedLocalKeyException
import com.nishantattrey.clipsync.core.local.crypto.LOCAL_ENVELOPE_VERSION
import com.nishantattrey.clipsync.core.local.crypto.LocalFingerprint
import com.nishantattrey.clipsync.core.local.crypto.LocalKeyMaterial
import com.nishantattrey.clipsync.core.local.crypto.LocalPayloadCipher
import com.nishantattrey.clipsync.core.local.crypto.MissingLocalKeyException
import com.nishantattrey.clipsync.core.local.model.CaptureResult
import com.nishantattrey.clipsync.core.local.model.CaptureSource
import com.nishantattrey.clipsync.core.local.model.EmptyCaptureException
import com.nishantattrey.clipsync.core.local.model.LocalClipboardItem
import com.nishantattrey.clipsync.core.local.model.LocalDataResult
import com.nishantattrey.clipsync.core.local.model.LocalKeyPurpose
import com.nishantattrey.clipsync.core.local.model.LocalRecoveryState
import com.nishantattrey.clipsync.core.local.model.OversizedCaptureException
import com.nishantattrey.clipsync.core.local.model.RetentionPeriod
import com.nishantattrey.clipsync.core.local.persistence.LocalClipboardDao
import com.nishantattrey.clipsync.core.local.persistence.LocalClipboardEntity
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

const val MAX_LOCAL_TEXT_UTF8_BYTES = 5_000_000
const val MAX_UNBOOKMARKED_HISTORY = 200
const val MAX_PAGE_SIZE = 100
const val SEARCH_CHUNK_SIZE = 32
const val MAX_SEARCH_RESULTS = 100
const val MAX_SEARCH_SCAN = 1_000

fun interface EpochMillisClock { fun now(): Long }
fun interface LocalIdGenerator { fun next(): String }

interface LocalClipboardRepository {
    val changes: Flow<Int>
    suspend fun capture(text: String, source: CaptureSource): LocalDataResult<CaptureResult>
    suspend fun page(bookmarksOnly: Boolean, before: LocalClipboardItem? = null, limit: Int = MAX_PAGE_SIZE): LocalDataResult<List<LocalClipboardItem>>
    suspend fun search(query: String, bookmarksOnly: Boolean = false): LocalDataResult<List<LocalClipboardItem>>
    suspend fun find(id: String): LocalDataResult<LocalClipboardItem?>
    suspend fun setBookmarked(id: String, bookmarked: Boolean): Boolean
    suspend fun delete(id: String): Boolean
    suspend fun clearUnbookmarked(): Int
    suspend fun applyRetention(period: RetentionPeriod): Int
}

class RoomLocalClipboardRepository(
    private val dao: LocalClipboardDao,
    private val cipher: LocalPayloadCipher,
    private val fingerprint: LocalFingerprint,
    private val clock: EpochMillisClock = EpochMillisClock(System::currentTimeMillis),
    private val ids: LocalIdGenerator = LocalIdGenerator { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalClipboardRepository {
    override val changes: Flow<Int> = dao.observeCount()

    override suspend fun capture(text: String, source: CaptureSource): LocalDataResult<CaptureResult> =
        withContext(ioDispatcher) {
            if (text.isEmpty()) throw EmptyCaptureException()
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            try {
                if (bytes.size > MAX_LOCAL_TEXT_UTF8_BYTES) throw OversizedCaptureException()
                val digest = fingerprint.compute(bytes)
                dao.findByFingerprint(digest)?.let { return@withContext LocalDataResult.Success(CaptureResult.Duplicate(it.id)) }
                val id = ids.next()
                val entity = LocalClipboardEntity(
                    id = id,
                    encryptedPayload = cipher.encrypt(id, bytes),
                    fingerprint = digest,
                    envelopeVersion = LOCAL_ENVELOPE_VERSION,
                    createdAtEpochMillis = clock.now(),
                    captureSource = source.name,
                    plaintextByteCount = bytes.size,
                    isBookmarked = false,
                )
                val inserted = dao.insert(entity)
                val result = if (inserted != -1L) {
                    CaptureResult.Stored(id)
                } else {
                    CaptureResult.Duplicate(requireNotNull(dao.findByFingerprint(digest)).id)
                }
                dao.trimUnbookmarked(MAX_UNBOOKMARKED_HISTORY)
                LocalDataResult.Success(result)
            } catch (error: MissingLocalKeyException) {
                LocalDataResult.RecoveryRequired(LocalRecoveryState.MissingKeys(setOf(error.purpose)))
            } catch (error: InvalidatedLocalKeyException) {
                LocalDataResult.RecoveryRequired(LocalRecoveryState.InvalidatedKeys(setOf(error.purpose)))
            } catch (error: GeneralSecurityException) {
                LocalDataResult.RecoveryRequired(
                    LocalRecoveryState.TemporarilyUnavailable(error.javaClass.simpleName),
                )
            } finally {
                bytes.fill(0)
            }
        }

    override suspend fun page(
        bookmarksOnly: Boolean,
        before: LocalClipboardItem?,
        limit: Int,
    ): LocalDataResult<List<LocalClipboardItem>> = withContext(ioDispatcher) {
        decryptEntities(
            dao.loadPage(bookmarksOnly, before?.createdAtEpochMillis, before?.id, limit.coerceIn(1, MAX_PAGE_SIZE)),
        )
    }

    override suspend fun search(query: String, bookmarksOnly: Boolean): LocalDataResult<List<LocalClipboardItem>> =
        withContext(ioDispatcher) {
            val results = mutableListOf<LocalClipboardItem>()
            var beforeCreated: Long? = null
            var beforeId: String? = null
            var scanned = 0
            while (results.size < MAX_SEARCH_RESULTS && scanned < MAX_SEARCH_SCAN) {
                val chunk = dao.loadPage(bookmarksOnly, beforeCreated, beforeId, SEARCH_CHUNK_SIZE)
                if (chunk.isEmpty()) break
                for (entity in chunk) {
                    scanned++
                    when (val decoded = decryptEntity(entity)) {
                        is LocalDataResult.Success -> if (decoded.value.text.contains(query, ignoreCase = true)) {
                            results += decoded.value
                        }
                        is LocalDataResult.RecoveryRequired -> return@withContext decoded
                        is LocalDataResult.CorruptItem -> return@withContext decoded
                    }
                    if (results.size == MAX_SEARCH_RESULTS || scanned == MAX_SEARCH_SCAN) break
                }
                val last = chunk.last()
                beforeCreated = last.createdAtEpochMillis
                beforeId = last.id
                if (chunk.size < SEARCH_CHUNK_SIZE) break
            }
            LocalDataResult.Success(results.toList())
        }

    override suspend fun find(id: String): LocalDataResult<LocalClipboardItem?> = withContext(ioDispatcher) {
        val entity = dao.findById(id) ?: return@withContext LocalDataResult.Success(null)
        when (val result = decryptEntity(entity)) {
            is LocalDataResult.Success -> LocalDataResult.Success(result.value)
            is LocalDataResult.RecoveryRequired -> result
            is LocalDataResult.CorruptItem -> result
        }
    }

    override suspend fun setBookmarked(id: String, bookmarked: Boolean): Boolean =
        withContext(ioDispatcher) { dao.setBookmarked(id, bookmarked) == 1 }

    override suspend fun delete(id: String): Boolean = withContext(ioDispatcher) { dao.deleteById(id) == 1 }

    override suspend fun clearUnbookmarked(): Int = withContext(ioDispatcher) { dao.clearUnbookmarked() }

    override suspend fun applyRetention(period: RetentionPeriod): Int = withContext(ioDispatcher) {
        val expired = period.durationMillis?.let { dao.deleteUnbookmarkedOlderThan(clock.now() - it) } ?: 0
        expired + dao.trimUnbookmarked(MAX_UNBOOKMARKED_HISTORY)
    }

    private fun decryptEntities(entities: List<LocalClipboardEntity>): LocalDataResult<List<LocalClipboardItem>> {
        val items = ArrayList<LocalClipboardItem>(entities.size)
        for (entity in entities) {
            when (val decoded = decryptEntity(entity)) {
                is LocalDataResult.Success -> items += decoded.value
                is LocalDataResult.RecoveryRequired -> return decoded
                is LocalDataResult.CorruptItem -> return decoded
            }
        }
        return LocalDataResult.Success(items)
    }

    private fun decryptEntity(entity: LocalClipboardEntity): LocalDataResult<LocalClipboardItem> = try {
        if (entity.envelopeVersion != LOCAL_ENVELOPE_VERSION) {
            throw CorruptLocalPayloadException()
        }
        val plaintext = cipher.decrypt(entity.id, entity.encryptedPayload)
        try {
            LocalDataResult.Success(
                LocalClipboardItem(
                    id = entity.id,
                    text = plaintext.toString(StandardCharsets.UTF_8),
                    createdAtEpochMillis = entity.createdAtEpochMillis,
                    captureSource = CaptureSource.valueOf(entity.captureSource),
                    isBookmarked = entity.isBookmarked,
                ),
            )
        } finally {
            plaintext.fill(0)
        }
    } catch (error: MissingLocalKeyException) {
        LocalDataResult.RecoveryRequired(LocalRecoveryState.MissingKeys(setOf(error.purpose)))
    } catch (error: InvalidatedLocalKeyException) {
        LocalDataResult.RecoveryRequired(LocalRecoveryState.InvalidatedKeys(setOf(error.purpose)))
    } catch (error: CorruptLocalPayloadException) {
        LocalDataResult.CorruptItem(entity.id)
    } catch (error: IllegalArgumentException) {
        LocalDataResult.CorruptItem(entity.id)
    } catch (error: GeneralSecurityException) {
        LocalDataResult.RecoveryRequired(
            LocalRecoveryState.TemporarilyUnavailable(error.javaClass.simpleName),
        )
    }
}

interface LocalRecoveryManager {
    suspend fun state(): LocalRecoveryState
    suspend fun resetEncryptedHistory(confirmed: Boolean): LocalRecoveryState
}

class LocalRecoveryCoordinator(
    private val dao: LocalClipboardDao,
    private val keys: LocalKeyMaterial,
    private val cipher: LocalPayloadCipher,
    private val fingerprint: LocalFingerprint,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalRecoveryManager {
    override suspend fun state(): LocalRecoveryState = withContext(ioDispatcher) {
        val hasRows = dao.count() > 0
        val missing = mutableSetOf<LocalKeyPurpose>()
        val invalidated = mutableSetOf<LocalKeyPurpose>()
        for (purpose in LocalKeyPurpose.entries) {
            try {
                if (hasRows) {
                    if (keys.get(purpose) == null) missing += purpose
                } else {
                    keys.getOrCreate(purpose)
                }
            } catch (error: InvalidatedLocalKeyException) {
                invalidated += purpose
            } catch (error: GeneralSecurityException) {
                return@withContext LocalRecoveryState.TemporarilyUnavailable(error.javaClass.simpleName)
            }
        }
        if (missing.isEmpty() && invalidated.isEmpty()) {
            try {
                cipher.verifyUsable()
            } catch (error: MissingLocalKeyException) {
                missing += error.purpose
            } catch (error: InvalidatedLocalKeyException) {
                invalidated += error.purpose
            } catch (error: GeneralSecurityException) {
                return@withContext LocalRecoveryState.TemporarilyUnavailable(error.javaClass.simpleName)
            }
            try {
                fingerprint.verifyUsable()
            } catch (error: MissingLocalKeyException) {
                missing += error.purpose
            } catch (error: InvalidatedLocalKeyException) {
                invalidated += error.purpose
            } catch (error: GeneralSecurityException) {
                return@withContext LocalRecoveryState.TemporarilyUnavailable(error.javaClass.simpleName)
            }
        }
        when {
            invalidated.isNotEmpty() -> LocalRecoveryState.InvalidatedKeys(invalidated)
            missing.isNotEmpty() -> LocalRecoveryState.MissingKeys(missing)
            else -> LocalRecoveryState.Ready
        }
    }

    override suspend fun resetEncryptedHistory(confirmed: Boolean): LocalRecoveryState = withContext(ioDispatcher) {
        require(confirmed) { "Explicit confirmation is required." }
        val current = state()
        require(current is LocalRecoveryState.MissingKeys || current is LocalRecoveryState.InvalidatedKeys) {
            "Destructive reset is only allowed for missing or invalidated keys."
        }
        dao.deleteAll()
        keys.deleteAll()
        LocalKeyPurpose.entries.forEach(keys::getOrCreate)
        state()
    }
}
