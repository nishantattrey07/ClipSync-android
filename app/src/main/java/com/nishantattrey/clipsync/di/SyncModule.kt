package com.nishantattrey.clipsync.di

import android.content.Context
import com.nishantattrey.clipsync.core.local.repository.LocalClipboardRepository
import com.nishantattrey.clipsync.core.local.repository.LocalStore
import com.nishantattrey.clipsync.core.protocol.crypto.Argon2KeyDeriver
import com.nishantattrey.clipsync.core.protocol.crypto.JcaAesGcm
import com.nishantattrey.clipsync.core.protocol.crypto.KeyDeriver
import com.nishantattrey.clipsync.core.protocol.crypto.SecureRandomBytes
import com.nishantattrey.clipsync.core.sync.config.EncryptedCloudConfigurationStore
import com.nishantattrey.clipsync.core.sync.config.SecureBlobStore
import com.nishantattrey.clipsync.core.sync.engine.CloudSyncCoordinator
import com.nishantattrey.clipsync.core.sync.engine.TextSyncEngine
import com.nishantattrey.clipsync.core.sync.identity.DeviceProfileCodec
import com.nishantattrey.clipsync.core.sync.identity.EncryptedDeviceIdentityStore
import com.nishantattrey.clipsync.core.sync.image.AndroidImageProcessor
import com.nishantattrey.clipsync.core.sync.image.AndroidPreparedImageStore
import com.nishantattrey.clipsync.core.sync.image.ImageInboundHandler
import com.nishantattrey.clipsync.core.sync.image.ImageProcessor
import com.nishantattrey.clipsync.core.sync.image.ImageSyncEngine
import com.nishantattrey.clipsync.core.sync.image.PreparedImageStore
import com.nishantattrey.clipsync.core.sync.image.LocalImageKeyStore
import com.nishantattrey.clipsync.core.sync.model.ClipboardCloudTransportFactory
import com.nishantattrey.clipsync.core.sync.model.Clock
import com.nishantattrey.clipsync.core.sync.model.CloudConfigurationStore
import com.nishantattrey.clipsync.core.sync.model.DeviceIdentityStore
import com.nishantattrey.clipsync.core.sync.model.RealtimeWakeSourceFactory
import com.nishantattrey.clipsync.core.sync.network.SupabaseRestTransport
import com.nishantattrey.clipsync.core.sync.network.SupabaseRealtimeWakeSource
import com.nishantattrey.clipsync.core.sync.persistence.SyncQueueStore
import com.nishantattrey.clipsync.core.sync.platform.AndroidSecureBlobStore
import com.nishantattrey.clipsync.core.sync.realtime.RealtimeSyncController
import com.nishantattrey.clipsync.platform.AndroidImageUriReader
import com.nishantattrey.clipsync.platform.ImageUriReader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    @Provides @Singleton fun imageUriReader(@ApplicationContext context: Context): ImageUriReader =
        AndroidImageUriReader(context.contentResolver)
    @Provides @Singleton fun keyDeriver(): KeyDeriver = Argon2KeyDeriver()
    @Provides @Singleton fun secureBlobs(@ApplicationContext context: Context): SecureBlobStore =
        AndroidSecureBlobStore(context)

    @Provides @Singleton fun cloudConfiguration(blobs: SecureBlobStore): CloudConfigurationStore =
        EncryptedCloudConfigurationStore(blobs)

    @Provides @Singleton fun identity(blobs: SecureBlobStore): DeviceIdentityStore =
        EncryptedDeviceIdentityStore(blobs, SecureRandomBytes())

    @Provides @Singleton fun preparedImages(@ApplicationContext context: Context): PreparedImageStore =
        AndroidPreparedImageStore(context)

    @Provides @Singleton fun imageProcessor(): ImageProcessor = AndroidImageProcessor()
    @Provides @Singleton fun localImageKeys(blobs: SecureBlobStore): LocalImageKeyStore =
        LocalImageKeyStore(blobs, SecureRandomBytes())

    @Provides @Singleton fun transportFactory(): ClipboardCloudTransportFactory =
        ClipboardCloudTransportFactory(::SupabaseRestTransport)

    @Provides @Singleton fun realtimeFactory(): RealtimeWakeSourceFactory =
        RealtimeWakeSourceFactory(::SupabaseRealtimeWakeSource)

    @Provides @Singleton fun realtimeController(
        configuration: CloudConfigurationStore,
        sources: RealtimeWakeSourceFactory,
        coordinator: CloudSyncCoordinator,
    ) = RealtimeSyncController(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        configuration,
        sources,
        coordinator,
    )

    @Provides @Singleton fun coordinator(
        @ApplicationContext context: Context,
        configuration: CloudConfigurationStore,
        identity: DeviceIdentityStore,
        transportFactory: ClipboardCloudTransportFactory,
        keyDeriver: KeyDeriver,
        store: LocalStore,
        local: LocalClipboardRepository,
        preparedImages: PreparedImageStore,
        imageProcessor: ImageProcessor,
    ): CloudSyncCoordinator {
        val encryption = JcaAesGcm()
        val queue = SyncQueueStore(store.database)
        return CloudSyncCoordinator(
            configurationStore = configuration,
            identityStore = identity,
            transportFactory = transportFactory,
            keyDeriver = keyDeriver,
            profileCodec = DeviceProfileCodec(encryption),
            database = store.database,
            local = local,
            engineFactory = { transport ->
                TextSyncEngine(
                    transport, queue, local, encryption, Clock(System::currentTimeMillis),
                    ImageInboundHandler(store.database, transport, preparedImages, imageProcessor, encryption, queue),
                )
            },
            imageEngineFactory = { transport ->
                ImageSyncEngine(
                    store.database, transport, preparedImages, imageProcessor, encryption,
                    Clock(System::currentTimeMillis),
                )
            },
            appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown",
        )
    }
}
