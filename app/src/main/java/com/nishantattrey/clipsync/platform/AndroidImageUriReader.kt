package com.nishantattrey.clipsync.platform

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import com.nishantattrey.clipsync.core.sync.image.AndroidImageProcessor
import com.nishantattrey.clipsync.core.sync.image.BoundedImageReader
import com.nishantattrey.clipsync.core.sync.image.ImageFormat
import com.nishantattrey.clipsync.core.sync.image.InvalidImageException
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ImageUriReader {
    suspend fun read(uri: Uri): UriImage
}

data class UriImage(val bytes: ByteArray, val claimedMimeType: String, val displayName: String?)

class AndroidImageUriReader(
    private val resolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImageUriReader {
    override suspend fun read(uri: Uri): UriImage = withContext(ioDispatcher) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Only content URIs are supported." }
        val mime = resolver.getType(uri)?.lowercase() ?: "image/unknown"
        val bytes = resolver.openInputStream(uri)?.use(BoundedImageReader::read)
            ?: throw IllegalArgumentException("Image URI is unavailable.")
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0)?.take(255)?.takeIf { value ->
                value.isNotBlank() && value.none { it.code < 0x20 || it.code == 0x7f }
            }
        }
        canonicalize(bytes, mime, name)
    }

    private fun canonicalize(bytes: ByteArray, sourceMime: String, name: String?): UriImage {
        val existing = AndroidImageProcessor.detectFormat(bytes)
        if (existing != null) return UriImage(bytes, existing.mimeType, name)
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            AndroidImageProcessor.validateDimensions(bounds.outWidth, bounds.outHeight)
            val bitmap = try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw InvalidImageException("Android cannot decode $sourceMime.")
            } catch (_: OutOfMemoryError) {
                throw InvalidImageException("Image cannot be decoded within memory limits.")
            }
            return try {
                val format = if (bitmap.hasAlpha()) ImageFormat.PNG else ImageFormat.JPEG
                val output = ByteArrayOutputStream()
                val compression = if (format == ImageFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                if (!bitmap.compress(compression, if (format == ImageFormat.PNG) 100 else 92, output)) {
                    throw InvalidImageException("Image conversion failed.")
                }
                val canonical = output.toByteArray()
                if (canonical.size > ProtocolV1.MAX_IMAGE_PLAINTEXT_BYTES) {
                    canonical.fill(0)
                    throw InvalidImageException("Converted image exceeds the encoded-size limit.")
                }
                UriImage(canonical, format.mimeType, canonicalDisplayName(name, format))
            } finally {
                bitmap.recycle()
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun canonicalDisplayName(name: String?, format: ImageFormat): String? {
        val base = name?.substringBeforeLast('.')?.takeIf(String::isNotBlank) ?: return null
        return "$base.${if (format == ImageFormat.PNG) "png" else "jpg"}"
    }
}
