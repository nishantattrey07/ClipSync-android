package com.nishantattrey.clipsync.core.sync.engine

import com.nishantattrey.clipsync.core.local.model.LocalDataResult
import com.nishantattrey.clipsync.core.local.persistence.ClipSyncDatabase
import com.nishantattrey.clipsync.core.local.persistence.DeviceDirectoryEntity
import com.nishantattrey.clipsync.core.local.repository.LocalClipboardRepository
import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import com.nishantattrey.clipsync.core.protocol.crypto.DerivedKeys
import com.nishantattrey.clipsync.core.protocol.crypto.KeyDeriver
import com.nishantattrey.clipsync.core.protocol.model.DeviceProfile
import com.nishantattrey.clipsync.core.protocol.model.RegisterDeviceParameters
import com.nishantattrey.clipsync.core.protocol.validation.DeviceRecordValidator
import com.nishantattrey.clipsync.core.sync.identity.DeviceProfileCodec
import com.nishantattrey.clipsync.core.sync.model.ChannelSession
import com.nishantattrey.clipsync.core.sync.model.ClipboardCloudTransportFactory
import com.nishantattrey.clipsync.core.sync.model.CloudConfigurationStore
import com.nishantattrey.clipsync.core.sync.model.DeviceIdentityStore
import com.nishantattrey.clipsync.core.sync.model.SyncFailure
import com.nishantattrey.clipsync.core.sync.model.SyncedDevice
import com.nishantattrey.clipsync.core.sync.model.TransportResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CloudSyncCoordinator(
    private val configurationStore: CloudConfigurationStore,
    private val identityStore: DeviceIdentityStore,
    private val transportFactory: ClipboardCloudTransportFactory,
    private val keyDeriver: KeyDeriver,
    private val profileCodec: DeviceProfileCodec,
    private val database: ClipSyncDatabase,
    private val local: LocalClipboardRepository,
    private val engineFactory: (com.nishantattrey.clipsync.core.sync.model.ClipboardCloudTransport) -> TextSyncEngine,
    private val appVersion: String,
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun synchronize(): SynchronizeResult {
        val configuration = configurationStore.load() ?: return SynchronizeResult.Unconfigured
        return try {
            val identity = identityStore.loadOrCreate()
            val keys = withContext(cryptoDispatcher) { keyDeriver.derive(configuration.channelPassword.concatToString()) }
            try {
                require(keys.channelId == configuration.channelId) { "Channel configuration does not match its secret." }
                val session = ChannelSession(configuration.endpoint, configuration.channelId, identity)
                val transport = transportFactory.create(configuration.endpoint)
                val registered = register(transport, session, keys, configuration.deviceName)
                if (registered != null) return registered
                val engine = engineFactory(transport)
                prepareUnsynced(engine, session, keys)
                val outbound = engine.drainOutbound(session)
                val inbound = engine.catchUp(session, keys)
                val directory = refreshDirectory(transport, session, keys)
                when {
                    outbound.stoppedBy != null -> outbound.stoppedBy.toSynchronizeResult()
                    inbound.stoppedBy != null -> inbound.stoppedBy.toSynchronizeResult()
                    directory.failure != null -> directory.failure.toSynchronizeResult()
                    else -> SynchronizeResult.Connected(outbound.uploaded, inbound.received, directory.devices)
                }
            } finally {
                keys.clear()
            }
        } catch (_: IllegalArgumentException) {
            SynchronizeResult.ActionableError("Cloud configuration is invalid.")
        } catch (_: java.security.GeneralSecurityException) {
            SynchronizeResult.Retrying(SyncFailure.TemporarilyUnavailable)
        } finally {
            configuration.clearSensitive()
        }
    }

    private suspend fun refreshDirectory(
        transport: com.nishantattrey.clipsync.core.sync.model.ClipboardCloudTransport,
        session: ChannelSession,
        keys: DerivedKeys,
    ): DirectoryRefresh = when (val result = transport.fetchDevices(session.channelId)) {
        is TransportResult.Failure -> DirectoryRefresh(failure = result.failure)
        is TransportResult.Success -> try {
            val devices = result.value.map { record ->
                    DeviceRecordValidator.validate(record, session.channelId)
                    val profile = profileCodec.decrypt(record.profileCiphertext, keys)
                    DeviceDirectoryEntity(
                        record.channelId, record.deviceId, record.profileCiphertext,
                        record.profileVersion, record.createdAt, record.updatedAt,
                    ) to SyncedDevice(
                        deviceId = record.deviceId,
                        name = profile.ownerName,
                        platform = profile.platform,
                    )
                }
            database.syncPersistenceDao().upsertDevices(devices.map { it.first })
            DirectoryRefresh(devices.map { it.second })
        } catch (_: SecurityException) {
            DirectoryRefresh(failure = SyncFailure.InvalidRemoteData("A device profile could not be authenticated."))
        } catch (_: IllegalArgumentException) {
            DirectoryRefresh(failure = SyncFailure.InvalidRemoteData("A device profile is invalid."))
        }
    }

    private suspend fun register(
        transport: com.nishantattrey.clipsync.core.sync.model.ClipboardCloudTransport,
        session: ChannelSession,
        keys: DerivedKeys,
        deviceName: String,
    ): SynchronizeResult? {
        val ciphertext = profileCodec.encrypt(
            DeviceProfile(deviceName, "Android", appVersion, ProtocolV1.PROFILE_PROTOCOL_VERSION),
            keys,
        )
        return when (val result = transport.registerDevice(
            RegisterDeviceParameters(
                session.channelId,
                session.identity.deviceId,
                ciphertext,
                ProtocolV1.PROFILE_VERSION,
                session.identity.deviceSecret,
            ),
        )) {
            is TransportResult.Success -> null
            is TransportResult.Failure -> result.failure.toSynchronizeResult()
        }
    }

    private suspend fun prepareUnsynced(engine: TextSyncEngine, session: ChannelSession, keys: DerivedKeys) {
        val dao = database.localClipboardDao()
        while (true) {
            val rows = dao.loadUnsynced(25)
            if (rows.isEmpty()) return
            for (row in rows) {
                when (val item = local.find(row.id)) {
                    is LocalDataResult.Success -> {
                        val value = item.value ?: continue
                        engine.prepareOutbound(value.id, value.text, value.createdAtEpochMillis, session, keys)
                        dao.setCloudSyncState(value.id, "queued")
                    }
                    is LocalDataResult.CorruptItem, is LocalDataResult.RecoveryRequired -> return
                }
            }
        }
    }

    private fun DerivedKeys.clear() {
        encryptionKey.fill(0)
        hmacKey.fill(0)
    }
}

sealed interface SynchronizeResult {
    data object Unconfigured : SynchronizeResult
    data class Connected(
        val uploaded: Int,
        val received: Int,
        val devices: List<SyncedDevice>,
    ) : SynchronizeResult
    data class Retrying(val failure: SyncFailure) : SynchronizeResult
    data class ActionableError(val safeMessage: String) : SynchronizeResult
}

private data class DirectoryRefresh(
    val devices: List<SyncedDevice> = emptyList(),
    val failure: SyncFailure? = null,
)

internal fun SyncFailure.toSynchronizeResult(): SynchronizeResult = when (this) {
    SyncFailure.Offline,
    SyncFailure.TimedOut,
    SyncFailure.TemporarilyUnavailable -> SynchronizeResult.Retrying(this)

    SyncFailure.Unauthorized -> SynchronizeResult.ActionableError(
        "Supabase rejected the public API credentials.",
    )
    is SyncFailure.Rejected -> SynchronizeResult.ActionableError(safeReason)
    is SyncFailure.InvalidRemoteData -> SynchronizeResult.ActionableError(safeReason)
}
