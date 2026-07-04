package com.nishantattrey.clipsync.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import com.nishantattrey.clipsync.core.local.persistence.LocalImageEntity
import com.nishantattrey.clipsync.core.protocol.crypto.DerivedKeys
import com.nishantattrey.clipsync.core.protocol.crypto.JcaAesGcm
import com.nishantattrey.clipsync.core.protocol.crypto.KeyDeriver
import com.nishantattrey.clipsync.core.sync.image.LocalImageKeyStore
import com.nishantattrey.clipsync.core.sync.image.PreparedImageStore
import com.nishantattrey.clipsync.core.sync.model.CloudConfigurationStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class AndroidImageContentService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val files: PreparedImageStore,
    private val configurations: CloudConfigurationStore,
    private val keyDeriver: KeyDeriver,
    private val localKeys: LocalImageKeyStore,
) {
    private val encryption = JcaAesGcm()
    private val channelKeyMutex = Mutex()
    private var cachedChannelKeys: DerivedKeys? = null
    private val shareRoot = File(context.cacheDir, "shared-images").apply {
        mkdirs()
        listFiles()?.forEach { file ->
            if (System.currentTimeMillis() - file.lastModified() >= SHARE_FILE_LIFETIME_MILLIS) file.delete()
        }
    }

    suspend fun preview(image: LocalImageEntity): Bitmap = withPlaintext(image) { bytes ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image preview is unavailable." }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("Image preview is unavailable.")
    }

    suspend fun copy(image: LocalImageEntity) {
        val uri = materialize(image)
        withContext(Dispatchers.Main) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newUri(context.contentResolver, image.displayName ?: "ClipSync image", uri)
            if (Build.VERSION.SDK_INT >= 33) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            clipboard.setPrimaryClip(clip)
        }
    }

    suspend fun share(image: LocalImageEntity) {
        val uri = materialize(image)
        withContext(Dispatchers.Main) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = image.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, "Share image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private suspend fun materialize(image: LocalImageEntity): Uri = withPlaintext(image) { bytes ->
        val extension = if (image.mimeType == "image/png") "png" else "jpg"
        val target = File(shareRoot, "${image.itemId}.$extension")
        target.delete()
        target.outputStream().use { it.write(bytes) }
        Handler(Looper.getMainLooper()).postDelayed({ target.delete() }, SHARE_FILE_LIFETIME_MILLIS)
        FileProvider.getUriForFile(context, "${context.packageName}.images", target)
    }

    private suspend fun <T> withPlaintext(image: LocalImageEntity, block: (ByteArray) -> T): T =
        withContext(Dispatchers.IO) {
            val encrypted = files.read(image.encryptedFileName)
            val keys = keysFor(image)
            try {
                val plaintext = encryption.decrypt(encrypted, keys.encryptionKey)
                try { block(plaintext) } finally { plaintext.fill(0) }
            } finally {
                encrypted.fill(0)
                keys.encryptionKey.fill(0)
                keys.hmacKey.fill(0)
            }
        }

    private suspend fun keysFor(image: LocalImageEntity): DerivedKeys {
        if (image.captureSource == "LOCAL_PICKER") return localKeys.loadOrCreate()
        val configuration = configurations.load() ?: throw IllegalStateException("Cloud configuration is unavailable.")
        return try {
            channelKeyMutex.withLock {
                val cached = cachedChannelKeys
                if (cached?.channelId == configuration.channelId) return@withLock cached.copyForUse()
                val derived = withContext(Dispatchers.Default) {
                    keyDeriver.derive(configuration.channelPassword.concatToString())
                }
                cached?.encryptionKey?.fill(0)
                cached?.hmacKey?.fill(0)
                cachedChannelKeys = derived
                derived.copyForUse()
            }
        } finally {
            configuration.clearSensitive()
        }
    }

    private fun DerivedKeys.copyForUse() = DerivedKeys(
        channelId = channelId,
        encryptionKey = encryptionKey.copyOf(),
        hmacKey = hmacKey.copyOf(),
    )

    private companion object {
        const val SHARE_FILE_LIFETIME_MILLIS = 10 * 60 * 1_000L
    }
}
