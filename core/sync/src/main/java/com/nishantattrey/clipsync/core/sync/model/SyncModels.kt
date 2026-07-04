package com.nishantattrey.clipsync.core.sync.model

import com.nishantattrey.clipsync.core.protocol.model.ClipboardDeviceRecord
import com.nishantattrey.clipsync.core.protocol.model.ClipboardInsertResult
import com.nishantattrey.clipsync.core.protocol.model.ClipboardItemRecord
import com.nishantattrey.clipsync.core.protocol.model.FetchAfterParameters
import com.nishantattrey.clipsync.core.protocol.model.InsertClipboardItemParameters
import com.nishantattrey.clipsync.core.protocol.model.RegisterDeviceParameters

data class CloudEndpoint(val supabaseUrl: String, val publishableKey: String)

data class PermanentDeviceIdentity(val deviceId: String, val deviceSecret: String)

data class ChannelSession(
    val endpoint: CloudEndpoint,
    val channelId: String,
    val identity: PermanentDeviceIdentity,
)

data class SyncedDevice(
    val deviceId: String,
    val name: String,
    val platform: String,
)

sealed interface SyncFailure {
    data object Offline : SyncFailure
    data object TimedOut : SyncFailure
    data object Unauthorized : SyncFailure
    data object TemporarilyUnavailable : SyncFailure
    data class Rejected(val safeReason: String) : SyncFailure
    data class InvalidRemoteData(val safeReason: String) : SyncFailure
}

sealed interface TransportResult<out T> {
    data class Success<T>(val value: T) : TransportResult<T>
    data class Failure(val failure: SyncFailure) : TransportResult<Nothing>
}

interface ClipboardCloudTransport {
    suspend fun registerDevice(parameters: RegisterDeviceParameters): TransportResult<Unit>
    suspend fun insertText(parameters: InsertClipboardItemParameters): TransportResult<ClipboardInsertResult>
    suspend fun fetchAfter(parameters: FetchAfterParameters): TransportResult<List<ClipboardItemRecord>>
    suspend fun fetchDevices(channelId: String): TransportResult<List<ClipboardDeviceRecord>>
    suspend fun uploadEncryptedImage(path: String, exactBytes: ByteArray): TransportResult<StorageUploadResult> =
        TransportResult.Failure(SyncFailure.Rejected("Image Storage is unavailable."))
    suspend fun downloadEncryptedImage(path: String): TransportResult<ByteArray> =
        TransportResult.Failure(SyncFailure.Rejected("Image Storage is unavailable."))
}

enum class StorageUploadResult { UPLOADED, IDENTICAL_EXISTING, COLLISION }

fun interface ClipboardCloudTransportFactory {
    fun create(endpoint: CloudEndpoint): ClipboardCloudTransport
}

interface RealtimeWakeSource {
    suspend fun awaitWake(channelId: String)
}

fun interface RealtimeWakeSourceFactory {
    fun create(endpoint: CloudEndpoint): RealtimeWakeSource
}

interface CloudConfigurationStore {
    suspend fun load(): CloudConfiguration?
    suspend fun save(configuration: CloudConfiguration)
    suspend fun clear()
}

class CloudConfiguration(
    val endpoint: CloudEndpoint,
    val channelId: String,
    val channelPassword: CharArray,
    val deviceName: String,
) {
    fun clearSensitive() = channelPassword.fill('\u0000')

    override fun toString(): String =
        "CloudConfiguration(endpoint=$endpoint, channelId=$channelId, deviceName=$deviceName, channelPassword=***)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CloudConfiguration) return false
        return endpoint == other.endpoint &&
            channelId == other.channelId &&
            channelPassword.contentEquals(other.channelPassword) &&
            deviceName == other.deviceName
    }

    override fun hashCode(): Int {
        var result = endpoint.hashCode()
        result = 31 * result + channelId.hashCode()
        result = 31 * result + channelPassword.contentHashCode()
        result = 31 * result + deviceName.hashCode()
        return result
    }
}

interface DeviceIdentityStore {
    suspend fun loadOrCreate(): PermanentDeviceIdentity
    suspend fun clear()
}

fun interface Clock { fun epochMillis(): Long }
