package com.nishantattrey.clipsync.core.protocol.validation

import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import com.nishantattrey.clipsync.core.protocol.encoding.WireEncoding
import com.nishantattrey.clipsync.core.protocol.model.ClipboardDeviceRecord
import com.nishantattrey.clipsync.core.protocol.model.ClipboardItemRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ProtocolValidationTest {
    @Test
    fun deviceNamesCountCodePointsAndRejectControls() {
        assertEquals("Pixel 😀", DeviceNameValidator.normalizeAndValidate("  Pixel 😀  "))
        DeviceNameValidator.normalizeAndValidate("😀".repeat(ProtocolV1.MAX_DEVICE_NAME_CODE_POINTS))
        assertThrows(IllegalArgumentException::class.java) {
            DeviceNameValidator.normalizeAndValidate("😀".repeat(ProtocolV1.MAX_DEVICE_NAME_CODE_POINTS + 1))
        }
        listOf("", "   ", "line\nbreak", "null\u0000byte", "line\u2028separator").forEach {
            assertThrows(IllegalArgumentException::class.java) { DeviceNameValidator.normalizeAndValidate(it) }
        }
    }

    @Test
    fun storagePathsAcceptUuidHexCaseOnly() {
        val id = UUID.fromString("abcdef12-3456-7890-abcd-ef1234567890")
        assertEquals(id, StoragePathValidator.validate(StoragePathValidator.make(id)))
        assertEquals(id, StoragePathValidator.validate("abcdef12-3456-7890-abcd-ef1234567890.enc"))
        listOf("../$id.enc", "$id.enc.extra", "$id/other.enc", "not-a-uuid.enc").forEach {
            assertThrows(IllegalArgumentException::class.java) { StoragePathValidator.validate(it) }
        }
    }

    @Test
    fun timestampPreservesSixFractionalDigits() {
        val value = "2027-01-15T08:00:00.123456Z"
        val micros = ServerTimestampCodec.parseMicroseconds(value)
        assertEquals(value, ServerTimestampCodec.formatMicroseconds(micros))
        assertThrows(Exception::class.java) {
            ServerTimestampCodec.parseMicroseconds("2027-01-15T08:00:00.1234567Z")
        }
        assertThrows(Exception::class.java) {
            ServerTimestampCodec.parseMicroseconds("2027-02-30T08:00:00.123456Z")
        }
    }

    @Test
    fun uuidOrderingUsesUnsignedPostgresCompatibleBytes() {
        val positive = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff")
        val negativeSignedLong = UUID.fromString("80000000-0000-0000-0000-000000000000")
        assertTrue(UuidOrdering.compare(positive, negativeSignedLong) < 0)
    }

    @Test
    fun rowValidationEnforcesKindVersionChannelAndMimeContracts() {
        val channel = "a".repeat(64)
        val text = ClipboardItemRecord(
            id = "10000000-0000-0000-0000-000000000001",
            channelId = channel,
            deviceId = "00000000-0000-0000-0000-000000000001",
            kind = "text",
            payloadVersion = 1,
            ciphertext = WireEncoding.standardBase64(ByteArray(28)),
            createdAt = "2026-01-01T00:00:00.123456Z",
        )
        ClipboardRowValidator.validate(text, channel)
        assertThrows(IllegalArgumentException::class.java) {
            ClipboardRowValidator.validate(text.copy(payloadVersion = 2), channel)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClipboardRowValidator.validate(text.copy(kind = "file"), channel)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClipboardRowValidator.validate(text.copy(mimeType = "text/plain"), channel)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClipboardRowValidator.validate(text.copy(ciphertext = "not-base64"), channel)
        }
    }

    @Test
    fun rowValidationUsesExactSqlBase64TextLimits() {
        val channel = "a".repeat(64)
        val base = ClipboardItemRecord(
            id = "10000000-0000-0000-0000-000000000001",
            channelId = channel,
            deviceId = "00000000-0000-0000-0000-000000000001",
            kind = "text",
            payloadVersion = 1,
            ciphertext = "A".repeat(ProtocolV1.MAX_TEXT_CIPHERTEXT_BYTES),
            createdAt = "2026-01-01T00:00:00.123456Z",
        )
        ClipboardRowValidator.validate(base, channel)
        assertThrows(IllegalArgumentException::class.java) {
            ClipboardRowValidator.validate(
                base.copy(ciphertext = "A".repeat(ProtocolV1.MAX_TEXT_CIPHERTEXT_BYTES + 4)),
                channel,
            )
        }
    }

    @Test
    fun deviceRowsRequireCanonicalBoundedProfiles() {
        val channel = "a".repeat(64)
        val record = ClipboardDeviceRecord(
            channelId = channel,
            deviceId = "00000000-0000-0000-0000-000000000001",
            profileCiphertext = WireEncoding.standardBase64(ByteArray(28)),
            profileVersion = 1,
            createdAt = "2026-01-01T00:00:00.123456Z",
            updatedAt = "2026-01-01T00:00:01.000001Z",
        )
        DeviceRecordValidator.validate(record, channel)
        assertThrows(IllegalArgumentException::class.java) {
            DeviceRecordValidator.validate(record.copy(profileVersion = 2), channel)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceRecordValidator.validate(record.copy(profileCiphertext = "YQ=="), channel)
        }
    }
}
