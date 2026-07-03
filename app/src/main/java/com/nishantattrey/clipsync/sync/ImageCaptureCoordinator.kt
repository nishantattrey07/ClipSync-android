package com.nishantattrey.clipsync.sync

import android.net.Uri
import com.nishantattrey.clipsync.core.local.repository.LocalStore
import com.nishantattrey.clipsync.core.protocol.crypto.JcaAesGcm
import com.nishantattrey.clipsync.core.protocol.crypto.KeyDeriver
import com.nishantattrey.clipsync.core.sync.image.ImageProcessor
import com.nishantattrey.clipsync.core.sync.image.ImageSyncEngine
import com.nishantattrey.clipsync.core.sync.image.PreparedImageStore
import com.nishantattrey.clipsync.core.sync.image.LocalImageKeyStore
import com.nishantattrey.clipsync.core.sync.model.ChannelSession
import com.nishantattrey.clipsync.core.sync.model.ClipboardCloudTransportFactory
import com.nishantattrey.clipsync.core.sync.model.Clock
import com.nishantattrey.clipsync.core.sync.model.CloudConfigurationStore
import com.nishantattrey.clipsync.core.sync.model.DeviceIdentityStore
import com.nishantattrey.clipsync.core.sync.model.CloudEndpoint
import com.nishantattrey.clipsync.core.sync.model.PermanentDeviceIdentity
import com.nishantattrey.clipsync.platform.ImageUriReader
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.nishantattrey.clipsync.core.sync.engine.CloudSyncCoordinator
import com.nishantattrey.clipsync.core.sync.engine.SynchronizeResult

class ImageCaptureCoordinator @Inject constructor(
    private val reader: ImageUriReader,
    private val configurations: CloudConfigurationStore,
    private val identities: DeviceIdentityStore,
    private val keyDeriver: KeyDeriver,
    private val transports: ClipboardCloudTransportFactory,
    private val store: LocalStore,
    private val files: PreparedImageStore,
    private val processor: ImageProcessor,
    private val localKeys: LocalImageKeyStore,
    private val cloudSync: CloudSyncCoordinator,
) {
    private val captureMutex = Mutex()

    suspend fun capture(uri: Uri, upload: Boolean): String = captureMutex.withLock {
        val image = reader.read(uri)
        val fingerprint = store.fingerprint.compute(image.bytes)
        val duplicate = store.database.syncPersistenceDao().findLocalImageByFingerprint(fingerprint)
        if (duplicate != null) {
            try {
                if (!upload) return@withLock "Already saved locally"
                return@withLock when (duplicate.cloudSyncState) {
                    "synced", "received" -> {
                        cloudSync.synchronize()
                        "Already shared"
                    }
                    "queued", "retrying" -> {
                        cloudSync.synchronize()
                        "Upload resumed"
                    }
                    "local" -> promoteLocal(duplicate, image.bytes)
                    else -> "Upload needs attention"
                }
            } finally {
                image.bytes.fill(0)
                fingerprint.fill(0)
            }
        }
        if (!upload) return@withLock captureLocal(image.bytes, image.claimedMimeType, fingerprint, image.displayName)
        val configuration = configurations.load()
            ?: throw IllegalStateException("Cloud configuration is required for sharing images.")
        try {
            val identity = identities.loadOrCreate()
            val keys = withContext(Dispatchers.Default) { keyDeriver.derive(configuration.channelPassword.concatToString()) }
            try {
                require(keys.channelId == configuration.channelId)
                val transport = transports.create(configuration.endpoint)
                ImageSyncEngine(store.database, transport, files, processor, JcaAesGcm(), Clock(System::currentTimeMillis))
                    .prepareCapture(
                        UUID.randomUUID().toString(), image.bytes, image.claimedMimeType,
                        if (upload) "SHARE_OR_PICKER" else "LOCAL_PICKER",
                        ChannelSession(configuration.endpoint, configuration.channelId, identity), keys, upload,
                        fingerprint, image.displayName,
                    )
                val result = cloudSync.synchronize()
                return when (result) {
                    is SynchronizeResult.Connected -> "Image uploaded"
                    is SynchronizeResult.Retrying -> "Image saved; upload will retry"
                    is SynchronizeResult.ActionableError -> "Image saved; upload needs attention"
                    SynchronizeResult.Unconfigured -> "Image saved; cloud is not configured"
                }
            } finally {
                keys.encryptionKey.fill(0)
                keys.hmacKey.fill(0)
            }
        } finally {
            image.bytes.fill(0)
            fingerprint.fill(0)
            configuration.clearSensitive()
        }
    }

    suspend fun shareExisting(image: com.nishantattrey.clipsync.core.local.persistence.LocalImageEntity): String =
        captureMutex.withLock {
            require(image.cloudSyncState == "local") { "Only local images can be shared." }
            val encrypted = files.read(image.encryptedFileName)
            val keys = localKeys.loadOrCreate()
            try {
                val plaintext = JcaAesGcm().decrypt(encrypted, keys.encryptionKey)
                try { promoteLocal(image, plaintext) } finally { plaintext.fill(0) }
            } finally {
                encrypted.fill(0)
                keys.encryptionKey.fill(0)
                keys.hmacKey.fill(0)
            }
        }

    private suspend fun promoteLocal(image: com.nishantattrey.clipsync.core.local.persistence.LocalImageEntity, bytes: ByteArray): String {
        val configuration = configurations.load()
            ?: throw IllegalStateException("Cloud configuration is required for sharing images.")
        return try {
            val identity = identities.loadOrCreate()
            val keys = withContext(Dispatchers.Default) { keyDeriver.derive(configuration.channelPassword.concatToString()) }
            try {
                val transport = transports.create(configuration.endpoint)
                ImageSyncEngine(store.database, transport, files, processor, JcaAesGcm(), Clock(System::currentTimeMillis))
                    .promoteLocalCapture(
                        image,
                        bytes,
                        ChannelSession(configuration.endpoint, configuration.channelId, identity),
                        keys,
                    )
                when (cloudSync.synchronize()) {
                    is SynchronizeResult.Connected -> "Image shared"
                    is SynchronizeResult.Retrying -> "Image queued; upload will retry"
                    is SynchronizeResult.ActionableError -> "Image queued; upload needs attention"
                    SynchronizeResult.Unconfigured -> "Image remains local; cloud is not configured"
                }
            } finally {
                keys.encryptionKey.fill(0)
                keys.hmacKey.fill(0)
            }
        } finally {
            configuration.clearSensitive()
        }
    }

    private suspend fun captureLocal(bytes: ByteArray, mimeType: String, fingerprint: ByteArray, displayName: String?): String {
        val keys = localKeys.loadOrCreate()
        return try {
            val identity = PermanentDeviceIdentity(
                "00000000-0000-0000-0000-000000000000",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            )
            ImageSyncEngine(
                store.database,
                transports.create(CloudEndpoint("https://local.invalid", "local")),
                files,
                processor,
                JcaAesGcm(),
                Clock(System::currentTimeMillis),
            ).prepareCapture(
                UUID.randomUUID().toString(), bytes, mimeType, "LOCAL_PICKER",
                ChannelSession(CloudEndpoint("https://local.invalid", "local"), keys.channelId, identity),
                keys,
                upload = false,
                fingerprint = fingerprint,
                displayName = displayName,
            )
            "Image saved locally"
        } finally {
            bytes.fill(0)
            fingerprint.fill(0)
            keys.encryptionKey.fill(0)
            keys.hmacKey.fill(0)
        }
    }
}
