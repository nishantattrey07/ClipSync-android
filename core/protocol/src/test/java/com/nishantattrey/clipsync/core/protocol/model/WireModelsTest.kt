package com.nishantattrey.clipsync.core.protocol.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.nishantattrey.clipsync.core.protocol.encoding.WireEncoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WireModelsTest {
    private val json = Json {
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    @Test
    fun rpcModelsUseExactSnakeCaseKeys() {
        val encoded = json.encodeToString(
            FetchAfterParameters(
                channelId = "a".repeat(64),
                limit = 50,
                afterTimestamp = "2026-01-01T00:00:00.123456Z",
                afterId = "10000000-0000-0000-0000-000000000001",
            ),
        )
        assertTrue(encoded.contains("\"p_channel_id\""))
        assertTrue(encoded.contains("\"p_after_timestamp\""))
        assertTrue(encoded.contains("\"p_after_id\""))
        assertTrue(encoded.contains("\"p_limit\":50"))
    }

    @Test
    fun insertStatusRejectsUnknownValues() {
        assertEquals(ClipboardInsertResult.INSERTED, ClipboardInsertResult.parse("inserted"))
        assertEquals(
            ClipboardInsertResult.ALREADY_PRESENT,
            ClipboardInsertResult.parse("already_present"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ClipboardInsertResult.parse("future_status")
        }
    }

    @Test
    fun malformedRowsFailStrictJsonDecoding() {
        assertThrows(Exception::class.java) {
            json.decodeFromString<ClipboardItemRecord>("{\"id\":1}")
        }
    }

    @Test
    fun outboundModelsRejectMalformedCredentialsCursorsAndPayloads() {
        val channel = "a".repeat(64)
        val deviceId = "00000000-0000-0000-0000-000000000001"
        val itemId = "10000000-0000-0000-0000-000000000001"
        val secret = "A".repeat(43)
        val envelope = WireEncoding.standardBase64(ByteArray(28))

        assertThrows(IllegalArgumentException::class.java) {
            FetchAfterParameters(channel, 50, "2026-01-01T00:00:00.123456Z", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FetchPageParameters(channel, 101)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegisterDeviceParameters(channel, deviceId, envelope, 1, "not-a-secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            InsertClipboardItemParameters(
                itemId,
                channel,
                deviceId,
                secret,
                "text",
                1,
                ciphertext = envelope,
                imagePath = "$itemId.enc",
            )
        }
    }
}
