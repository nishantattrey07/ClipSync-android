package com.nishantattrey.clipsync.core.protocol.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Argon2KtSmokeTest {
    @Test
    fun nativeProviderLoadsAndProducesDeterministicSizedKeys() {
        val deriver: KeyDeriver = Argon2KeyDeriver()
        val first = deriver.derive("generic-public-smoke-input")
        val second = deriver.derive("generic-public-smoke-input")

        assertEquals(first, second)
        assertEquals(64, first.channelId.length)
        assertEquals(ProtocolV1.DERIVED_KEY_BYTES, first.encryptionKey.size)
        assertEquals(ProtocolV1.DERIVED_KEY_BYTES, first.hmacKey.size)
        assertTrue(first.channelId.matches(Regex("[0-9a-f]{64}")))
    }
}
