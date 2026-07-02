package com.nishantattrey.clipsync.core.protocol.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class JcaProtocolCryptoTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun aesGcmRoundTripsEmptyAndUnicodePayloads() {
        val crypto = JcaAesGcm(SequenceRandomBytes())
        listOf(byteArrayOf(), "generic Unicode: 😀 नमस्ते".toByteArray()).forEach { plaintext ->
            val encrypted = crypto.encrypt(plaintext, key)
            assertArrayEquals(plaintext, crypto.decrypt(encrypted, key))
        }
    }

    @Test
    fun freshEncryptionsUseDifferentNonces() {
        val crypto = JcaAesGcm(SequenceRandomBytes())
        val first = crypto.encrypt("same".toByteArray(), key)
        val second = crypto.encrypt("same".toByteArray(), key)
        assertFalse(first.copyOfRange(0, 12).contentEquals(second.copyOfRange(0, 12)))
    }

    @Test
    fun wrongKeyTamperingAndTruncationFailClosed() {
        val crypto = JcaAesGcm(SequenceRandomBytes())
        val encrypted = crypto.encrypt("payload".toByteArray(), key)
        assertThrows(AuthenticationFailedException::class.java) {
            crypto.decrypt(encrypted, ByteArray(32) { 7 })
        }
        encrypted[15] = (encrypted[15].toInt() xor 1).toByte()
        assertThrows(AuthenticationFailedException::class.java) { crypto.decrypt(encrypted, key) }
        assertThrows(IllegalArgumentException::class.java) { crypto.decrypt(ByteArray(27), key) }
    }

    private class SequenceRandomBytes : RandomBytes {
        private var call = 0
        override fun next(byteCount: Int): ByteArray = ByteArray(byteCount) { (it + call).toByte() }
            .also { call += 1 }
    }
}
