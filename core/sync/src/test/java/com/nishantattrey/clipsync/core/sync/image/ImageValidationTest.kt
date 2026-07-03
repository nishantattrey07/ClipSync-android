package com.nishantattrey.clipsync.core.sync.image

import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import com.nishantattrey.clipsync.core.protocol.crypto.JcaAesGcm
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageValidationTest {
    @Test fun detectsPngAndJpegSignaturesAndRejectsTruncation() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
        assertEquals(ImageFormat.PNG, AndroidImageProcessor.detectFormat(png))
        assertEquals(ImageFormat.JPEG, AndroidImageProcessor.detectFormat(jpeg))
        assertEquals(null, AndroidImageProcessor.detectFormat(byteArrayOf(0xff.toByte(), 0xd8.toByte())))
    }

    @Test fun dimensionAndPixelLimitsAreEnforcedWithoutDecoding() {
        AndroidImageProcessor.validateDimensions(8_000, 5_000)
        assertThrows(InvalidImageException::class.java) { AndroidImageProcessor.validateDimensions(8_000, 5_001) }
        assertThrows(InvalidImageException::class.java) { AndroidImageProcessor.validateDimensions(16_385, 1) }
        assertThrows(InvalidImageException::class.java) { AndroidImageProcessor.validateDimensions(0, 1) }
    }

    @Test fun boundedReaderStopsBeforeUnboundedAllocation() {
        assertEquals(4, BoundedImageReader.read(ByteArrayInputStream(ByteArray(4)), 4).size)
        assertThrows(InvalidImageException::class.java) {
            BoundedImageReader.read(ByteArrayInputStream(ByteArray(5)), 4)
        }
    }

    @Test fun fullImageAndThumbnailEncryptionUseIndependentNonces() {
        var nonce = 0
        val cipher = JcaAesGcm { count -> ByteArray(count) { nonce.toByte() }.also { nonce++ } }
        val key = ByteArray(ProtocolV1.DERIVED_KEY_BYTES) { 7 }
        val full = cipher.encrypt(byteArrayOf(1), key)
        val thumbnail = cipher.encrypt(byteArrayOf(1), key)
        assertFalse(full.copyOf(ProtocolV1.AES_GCM_NONCE_BYTES).contentEquals(
            thumbnail.copyOf(ProtocolV1.AES_GCM_NONCE_BYTES),
        ))
    }
}
