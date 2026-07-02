package com.nishantattrey.clipsync.core.protocol.encoding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WireEncodingTest {
    @Test
    fun standardBase64AndUnpaddedBase64UrlRemainDistinct() {
        val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xef.toByte())

        assertEquals("+//v", WireEncoding.standardBase64(bytes))
        assertEquals("-__v", WireEncoding.base64UrlWithoutPadding(bytes))
        assertArrayEquals(bytes, WireEncoding.decodeStandardBase64("+//v"))
        assertArrayEquals(bytes, WireEncoding.decodeBase64UrlWithoutPadding("-__v"))
    }

    @Test
    fun malformedOrPaddedBase64UrlFailsClosed() {
        assertThrows(ProtocolEncodingException::class.java) {
            WireEncoding.decodeBase64UrlWithoutPadding("YQ==")
        }
        assertThrows(ProtocolEncodingException::class.java) {
            WireEncoding.decodeStandardBase64("not base64")
        }
        assertThrows(ProtocolEncodingException::class.java) {
            WireEncoding.decodeStandardBase64("YQ")
        }
        assertThrows(ProtocolEncodingException::class.java) {
            WireEncoding.decodeStandardBase64("YR==")
        }
        assertThrows(ProtocolEncodingException::class.java) {
            WireEncoding.decodeBase64UrlWithoutPadding("YR")
        }
    }

    @Test
    fun canonicalBase64ReportsDecodedSizeWithoutAllocatingDecodedBytes() {
        assertEquals(0, WireEncoding.standardBase64DecodedByteCount(""))
        assertEquals(1, WireEncoding.standardBase64DecodedByteCount("YQ=="))
        assertEquals(2, WireEncoding.standardBase64DecodedByteCount("YWI="))
        assertEquals(3, WireEncoding.standardBase64DecodedByteCount("YWJj"))
    }

    @Test
    fun lowercaseHexRejectsUppercaseAndOddLengths() {
        val bytes = byteArrayOf(0, 15, 16, 0xff.toByte())
        assertEquals("000f10ff", WireEncoding.lowercaseHex(bytes))
        assertArrayEquals(bytes, WireEncoding.decodeLowercaseHex("000f10ff"))
        assertThrows(ProtocolEncodingException::class.java) { WireEncoding.decodeLowercaseHex("0F") }
        assertThrows(ProtocolEncodingException::class.java) { WireEncoding.decodeLowercaseHex("f") }
    }
}
