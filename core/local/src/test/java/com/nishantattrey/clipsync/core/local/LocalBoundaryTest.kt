package com.nishantattrey.clipsync.core.local

import com.nishantattrey.clipsync.core.local.capture.ClipboardGateway
import androidx.datastore.core.CorruptionException
import com.nishantattrey.clipsync.core.local.capture.FocusedClipboardImportUseCase
import com.nishantattrey.clipsync.core.local.capture.TextCaptureUseCase
import com.nishantattrey.clipsync.core.local.model.CaptureSource
import com.nishantattrey.clipsync.core.local.model.LocalClipboardItem
import com.nishantattrey.clipsync.core.local.model.LocalDataResult
import com.nishantattrey.clipsync.core.local.model.RetentionPeriod
import com.nishantattrey.clipsync.core.local.repository.LocalClipboardRepository
import com.nishantattrey.clipsync.core.local.settings.LocalSettingsSerializer
import com.nishantattrey.clipsync.core.local.proto.ClipboardSensitivityProto
import com.nishantattrey.clipsync.core.local.proto.LocalSettingsProto
import com.nishantattrey.clipsync.core.local.proto.RetentionPeriodProto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBoundaryTest {
    @Test fun `focused import never reads clipboard when focus gate denies`() = runTest {
        val clipboard = RecordingClipboard()
        val useCase = FocusedClipboardImportUseCase(clipboard, { false }, TextCaptureUseCase(NoOpRepository()))

        assertNull(useCase())
        assertFalse(clipboard.wasRead)
    }

    @Test fun `settings proto round trip preserves explicit non-default values`() = runTest {
        val expected = LocalSettingsProto.newBuilder()
            .setTextRetention(RetentionPeriodProto.RETENTION_PERIOD_SEVEN_DAYS)
            .setImageRetention(RetentionPeriodProto.RETENTION_PERIOD_THIRTY_DAYS)
            .setCopyBackSensitivity(ClipboardSensitivityProto.CLIPBOARD_SENSITIVITY_STANDARD)
            .putDeviceAliases("00000000-0000-0000-0000-000000000001", "Work Mac")
            .build()
        val output = ByteArrayOutputStream()
        LocalSettingsSerializer.writeTo(expected, output)

        assertEquals(expected, LocalSettingsSerializer.readFrom(ByteArrayInputStream(output.toByteArray())))
    }

    @Test fun `malformed settings are classified as recoverable corruption`() = runTest {
        var corruption: Throwable? = null
        try {
            LocalSettingsSerializer.readFrom(ByteArrayInputStream(byteArrayOf(0x0a, 0x05, 0x01)))
        } catch (error: Throwable) {
            corruption = error
        }
        assertTrue(corruption is CorruptionException)
    }
}

private class RecordingClipboard : ClipboardGateway {
    var wasRead = false
    override fun readText(): String? { wasRead = true; return "content" }
    override fun writeText(text: String, sensitive: Boolean) = Unit
}

private class NoOpRepository : LocalClipboardRepository {
    override val changes: Flow<Int> = flowOf(0)
    override suspend fun capture(text: String, source: CaptureSource) = error("must not capture")
    override suspend fun storeInbound(id: String, text: String, createdAtEpochMillis: Long): LocalDataResult<Boolean> =
        error("must not store inbound")
    override suspend fun page(bookmarksOnly: Boolean, before: LocalClipboardItem?, limit: Int): LocalDataResult<List<LocalClipboardItem>> = LocalDataResult.Success(emptyList())
    override suspend fun search(query: String, bookmarksOnly: Boolean): LocalDataResult<List<LocalClipboardItem>> = LocalDataResult.Success(emptyList())
    override suspend fun find(id: String): LocalDataResult<LocalClipboardItem?> = LocalDataResult.Success(null)
    override suspend fun setBookmarked(id: String, bookmarked: Boolean) = false
    override suspend fun delete(id: String) = false
    override suspend fun clearUnbookmarked() = 0
    override suspend fun applyRetention(period: RetentionPeriod) = 0
}
