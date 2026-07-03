package com.nishantattrey.clipsync.core.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nishantattrey.clipsync.core.local.crypto.AesGcmLocalPayloadCipher
import com.nishantattrey.clipsync.core.local.crypto.AndroidKeystoreLocalKeyMaterial
import com.nishantattrey.clipsync.core.local.crypto.HmacSha256LocalFingerprint
import com.nishantattrey.clipsync.core.local.crypto.LOCAL_FINGERPRINT_KEY_ALIAS
import com.nishantattrey.clipsync.core.local.model.CaptureSource
import com.nishantattrey.clipsync.core.local.model.LocalDataResult
import com.nishantattrey.clipsync.core.local.model.LocalKeyPurpose
import com.nishantattrey.clipsync.core.local.model.LocalRecoveryState
import com.nishantattrey.clipsync.core.local.persistence.ClipSyncDatabase
import com.nishantattrey.clipsync.core.local.repository.LocalRecoveryCoordinator
import com.nishantattrey.clipsync.core.local.repository.RoomLocalClipboardRepository
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalStorageInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "gate2-instrumented.db"

    @Before fun prepare() {
        context.deleteDatabase(databaseName)
        AndroidKeystoreLocalKeyMaterial().deleteAll()
    }

    @After fun cleanUp() {
        context.deleteDatabase(databaseName)
        AndroidKeystoreLocalKeyMaterial().deleteAll()
    }

    @Test fun keystoreKeysAreDistinctAndSurviveAdapterRecreation() {
        val first = AndroidKeystoreLocalKeyMaterial()
        val aes = first.getOrCreate(LocalKeyPurpose.PAYLOAD_ENCRYPTION)
        val hmac = first.getOrCreate(LocalKeyPurpose.DEDUP_FINGERPRINT)
        val second = AndroidKeystoreLocalKeyMaterial()

        assertEquals("AES", aes.algorithm)
        assertEquals("HmacSHA256", hmac.algorithm)
        assertEquals(aes, second.get(LocalKeyPurpose.PAYLOAD_ENCRYPTION))
        assertEquals(hmac, second.get(LocalKeyPurpose.DEDUP_FINGERPRINT))
    }

    @Test fun encryptedRowsSurviveDatabaseAndRepositoryRecreationWithoutPlaintext() = runBlocking {
        val exact = "  private\r\ntext  "
        var database = openDatabase()
        var repository = repository(database)
        LocalRecoveryCoordinator(database.localClipboardDao(), AndroidKeystoreLocalKeyMaterial()).state()
        repository.capture(exact, CaptureSource.SHARE)
        val row = database.localClipboardDao().loadPage(false, null, null, 1).single()
        assertFalse(String(row.encryptedPayload, Charsets.UTF_8).contains("private"))
        assertFalse(String(row.fingerprint, Charsets.UTF_8).contains("private"))
        database.close()

        database = openDatabase()
        repository = repository(database)
        val loaded = repository.page(false) as LocalDataResult.Success
        assertEquals(exact, loaded.value.single().text)
        database.close()
    }

    @Test fun missingFingerprintKeyIsExplicitRecovery() = runBlocking {
        val database = openDatabase()
        LocalRecoveryCoordinator(database.localClipboardDao(), AndroidKeystoreLocalKeyMaterial()).state()
        repository(database).capture("stored", CaptureSource.COMPOSER)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(LOCAL_FINGERPRINT_KEY_ALIAS)

        val state = LocalRecoveryCoordinator(database.localClipboardDao(), AndroidKeystoreLocalKeyMaterial()).state()
        assertTrue(state is LocalRecoveryState.MissingKeys)
        assertTrue((state as LocalRecoveryState.MissingKeys).aliases.contains(LocalKeyPurpose.DEDUP_FINGERPRINT))
        database.close()
    }

    @Test fun completeSchemaVersionOneReopensWithoutDestructiveFallback() {
        openDatabase().close()
        val reopened = openDatabase()
        assertEquals(1, reopened.openHelper.readableDatabase.version)
        reopened.close()
    }

    private fun openDatabase(): ClipSyncDatabase =
        Room.databaseBuilder(context, ClipSyncDatabase::class.java, databaseName).build()

    private fun repository(database: ClipSyncDatabase): RoomLocalClipboardRepository {
        val keys = AndroidKeystoreLocalKeyMaterial()
        return RoomLocalClipboardRepository(
            database.localClipboardDao(),
            AesGcmLocalPayloadCipher(keys),
            HmacSha256LocalFingerprint(keys),
        )
    }
}
