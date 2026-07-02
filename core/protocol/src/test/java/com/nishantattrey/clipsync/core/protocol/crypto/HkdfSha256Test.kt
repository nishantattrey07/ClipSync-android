package com.nishantattrey.clipsync.core.protocol.crypto

import com.nishantattrey.clipsync.core.protocol.encoding.WireEncoding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HkdfSha256Test {
    @Test
    fun matchesRfc5869Sha256TestCaseOne() {
        val output = HkdfSha256.derive(
            inputKeyMaterial = ByteArray(22) { 0x0b },
            salt = WireEncoding.decodeLowercaseHex("000102030405060708090a0b0c"),
            info = WireEncoding.decodeLowercaseHex("f0f1f2f3f4f5f6f7f8f9"),
            outputBytes = 42,
        )
        assertArrayEquals(
            WireEncoding.decodeLowercaseHex(
                "3cb25f25faacd57a90434f64d0362f2a" +
                    "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                    "34007208d5b887185865",
            ),
            output,
        )
    }

    @Test
    fun differentInfoProducesSeparatedKeys() {
        val key = ByteArray(32) { it.toByte() }
        val salt = "salt".toByteArray()
        val first = HkdfSha256.derive(key, salt, "first".toByteArray(), 32)
        val second = HkdfSha256.derive(key, salt, "second".toByteArray(), 32)
        assertFalse(first.contentEquals(second))
    }
}
