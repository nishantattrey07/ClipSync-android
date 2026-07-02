package com.nishantattrey.clipsync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import com.nishantattrey.clipsync.core.protocol.crypto.Argon2KeyDeriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MinifiedProtocolSmokeTest {
    @Test
    fun argon2NativeProviderLoadsThroughMinifiedApplication() {
        val keys = Argon2KeyDeriver().derive("generic-public-minified-smoke-input")

        assertEquals(64, keys.channelId.length)
        assertEquals(ProtocolV1.DERIVED_KEY_BYTES, keys.encryptionKey.size)
        assertEquals(ProtocolV1.DERIVED_KEY_BYTES, keys.hmacKey.size)
        assertTrue(keys.channelId.matches(Regex("[0-9a-f]{64}")))
    }
}
