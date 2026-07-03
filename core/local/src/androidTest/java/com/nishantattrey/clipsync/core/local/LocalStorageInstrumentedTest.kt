package com.nishantattrey.clipsync.core.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
import com.nishantattrey.clipsync.core.local.persistence.MIGRATION_1_2
import com.nishantattrey.clipsync.core.local.persistence.MIGRATION_2_3
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
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalStorageInstrumentedTest {
    @get:Rule val migrationHelper = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        ClipSyncDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )
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
        recovery(database).state()
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
        recovery(database).state()
        repository(database).capture("stored", CaptureSource.COMPOSER)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(LOCAL_FINGERPRINT_KEY_ALIAS)

        val state = recovery(database).state()
        assertTrue(state is LocalRecoveryState.MissingKeys)
        assertTrue((state as LocalRecoveryState.MissingKeys).aliases.contains(LocalKeyPurpose.DEDUP_FINGERPRINT))
        database.close()
    }

    @Test fun completeSchemaVersionThreeReopensWithoutDestructiveFallback() {
        openDatabase().close()
        val reopened = openDatabase()
        assertEquals(3, reopened.openHelper.readableDatabase.version)
        reopened.close()
    }

    @Test fun migrationTwoToThreePreservesTextJournalAndAddsImageTables() {
        val name = "gate4-migration.db"
        context.deleteDatabase(name)
        migrationHelper.createDatabase(name, 2).apply {
            execSQL(
                "INSERT INTO inbound_text_journal (item_id, channel_id, device_id, kind, payload_version, ciphertext, created_at, created_at_microseconds, state) VALUES (?, ?, ?, 'text', 1, ?, '2026-01-01T00:00:00.000001Z', 1, 'pending')",
                arrayOf(
                    "00000000-0000-0000-0000-000000000001",
                    "ab".repeat(32),
                    "00000000-0000-0000-0000-000000000002",
                    "AA==",
                ),
            )
            close()
        }
        migrationHelper.runMigrationsAndValidate(name, 3, true, MIGRATION_2_3).apply {
            query("SELECT kind, ciphertext, image_path FROM inbound_text_journal").use {
                assertTrue(it.moveToFirst())
                assertEquals("text", it.getString(0))
                assertEquals("AA==", it.getString(1))
                assertTrue(it.isNull(2))
            }
            query("SELECT COUNT(*) FROM local_images").close()
            query("SELECT COUNT(*) FROM outbound_image_queue").close()
            close()
        }
        context.deleteDatabase(name)
    }

    @Test fun migrationOneToTwoPreservesEncryptedHistoryAndAddsDurableSyncTables() {
        val name = "gate3-migration.db"
        context.deleteDatabase(name)
        migrationHelper.createDatabase(name, 1).apply {
            execSQL(
                "INSERT INTO local_clipboard_items (id, encrypted_payload, fingerprint, envelope_version, created_at_epoch_millis, capture_source, plaintext_byte_count, is_bookmarked) VALUES (?, ?, ?, 1, 1, 'COMPOSER', 1, 0)",
                arrayOf("00000000-0000-0000-0000-000000000001", byteArrayOf(1), byteArrayOf(2)),
            )
            close()
        }
        migrationHelper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2).apply {
            query("SELECT cloud_sync_state FROM local_clipboard_items").use {
                assertTrue(it.moveToFirst())
                assertEquals("local", it.getString(0))
            }
            query("SELECT COUNT(*) FROM outbound_text_queue").close()
            query("SELECT COUNT(*) FROM inbound_text_journal").close()
            close()
        }
        context.deleteDatabase(name)
    }

    private fun openDatabase(): ClipSyncDatabase =
        Room.databaseBuilder(context, ClipSyncDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    private fun repository(database: ClipSyncDatabase): RoomLocalClipboardRepository {
        val keys = AndroidKeystoreLocalKeyMaterial()
        return RoomLocalClipboardRepository(
            database.localClipboardDao(),
            AesGcmLocalPayloadCipher(keys),
            HmacSha256LocalFingerprint(keys),
        )
    }

    private fun recovery(database: ClipSyncDatabase): LocalRecoveryCoordinator {
        val keys = AndroidKeystoreLocalKeyMaterial()
        return LocalRecoveryCoordinator(
            database.localClipboardDao(),
            keys,
            AesGcmLocalPayloadCipher(keys),
            HmacSha256LocalFingerprint(keys),
        )
    }
}
