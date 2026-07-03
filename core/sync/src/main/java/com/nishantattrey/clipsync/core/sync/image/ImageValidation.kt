package com.nishantattrey.clipsync.core.sync.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import java.io.ByteArrayOutputStream

enum class ImageFormat(val mimeType: String) { PNG("image/png"), JPEG("image/jpeg") }

data class ValidatedImage(
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val thumbnailJpeg: ByteArray,
)

class InvalidImageException(message: String) : IllegalArgumentException(message)

interface ImageProcessor {
    fun validateAndThumbnail(encoded: ByteArray, claimedMimeType: String): ValidatedImage
}

class AndroidImageProcessor : ImageProcessor {
    override fun validateAndThumbnail(encoded: ByteArray, claimedMimeType: String): ValidatedImage {
        if (encoded.isEmpty() || encoded.size > ProtocolV1.MAX_IMAGE_PLAINTEXT_BYTES) {
            throw InvalidImageException("Image exceeds the encoded-size limit.")
        }
        val format = detectFormat(encoded) ?: throw InvalidImageException("Unsupported or truncated image.")
        if (claimedMimeType != format.mimeType) throw InvalidImageException("Image MIME type does not match its bytes.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        validateDimensions(bounds.outWidth, bounds.outHeight)
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, ProtocolV1.MAX_THUMBNAIL_DIMENSION)
        val bitmap = try {
            BitmapFactory.decodeByteArray(encoded, 0, encoded.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw InvalidImageException("Image cannot be decoded.")
        } catch (_: OutOfMemoryError) {
            throw InvalidImageException("Image cannot be decoded within memory limits.")
        }
        return try {
            val scale = minOf(
                ProtocolV1.MAX_THUMBNAIL_DIMENSION.toFloat() / bitmap.width,
                ProtocolV1.MAX_THUMBNAIL_DIMENSION.toFloat() / bitmap.height,
                1f,
            )
            val thumbnail = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            } else bitmap
            try {
                val output = ByteArrayOutputStream()
                check(thumbnail.compress(Bitmap.CompressFormat.JPEG, 70, output)) { "Thumbnail encoding failed." }
                output.toByteArray().also {
                    require(it.size <= ProtocolV1.MAX_THUMBNAIL_CIPHERTEXT_BYTES) { "Thumbnail exceeds the V1 limit." }
                }
            } finally {
                if (thumbnail !== bitmap) thumbnail.recycle()
            }.let { ValidatedImage(format, bounds.outWidth, bounds.outHeight, it) }
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        fun detectFormat(bytes: ByteArray): ImageFormat? = when {
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> ImageFormat.PNG
            bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
                bytes[bytes.lastIndex - 1] == 0xFF.toByte() && bytes.last() == 0xD9.toByte() -> ImageFormat.JPEG
            else -> null
        }

        fun validateDimensions(width: Int, height: Int) {
            if (width <= 0 || height <= 0 || width > ProtocolV1.MAX_IMAGE_DIMENSION ||
                height > ProtocolV1.MAX_IMAGE_DIMENSION || width.toLong() * height > ProtocolV1.MAX_DECODED_PIXELS.toLong()
            ) throw InvalidImageException("Image dimensions exceed the V1 limit.")
        }

        private fun sampleSize(width: Int, height: Int, target: Int): Int {
            var sample = 1
            while (width / sample > target * 2 || height / sample > target * 2) sample *= 2
            return sample
        }

        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
