package com.nishantattrey.clipsync.core.sync.config

import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import com.nishantattrey.clipsync.core.protocol.validation.ProtocolValueValidator
import com.nishantattrey.clipsync.core.sync.model.CloudConfiguration
import com.nishantattrey.clipsync.core.sync.model.CloudConfigurationStore
import com.nishantattrey.clipsync.core.sync.model.CloudEndpoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val CONFIG_RECORD = "cloud_configuration"

interface SecureBlobStore {
    suspend fun read(name: String): ByteArray?
    suspend fun write(name: String, value: ByteArray)
    suspend fun delete(name: String)
}

class EncryptedCloudConfigurationStore(
    private val blobs: SecureBlobStore,
    private val json: Json = Json { ignoreUnknownKeys = false },
) : CloudConfigurationStore {
    override suspend fun load(): CloudConfiguration? {
        val bytes = blobs.read(CONFIG_RECORD) ?: return null
        return try {
            val stored = json.decodeFromString<StoredCloudConfiguration>(bytes.decodeToString())
            validateConfiguration(stored.supabaseUrl, stored.publishableKey, stored.channelId, stored.channelSecret, stored.deviceName)
        } finally {
            bytes.fill(0)
        }
    }

    override suspend fun save(configuration: CloudConfiguration) {
        val validated = validateConfiguration(
            configuration.endpoint.supabaseUrl,
            configuration.endpoint.publishableKey,
            configuration.channelId,
            configuration.channelPassword.concatToString(),
            configuration.deviceName,
        )
        val encoded = json.encodeToString(
            StoredCloudConfiguration(
                validated.endpoint.supabaseUrl,
                validated.endpoint.publishableKey,
                validated.channelId,
                validated.channelPassword.concatToString(),
                validated.deviceName,
            ),
        ).encodeToByteArray()
        try {
            blobs.write(CONFIG_RECORD, encoded)
        } finally {
            encoded.fill(0)
            validated.clearSensitive()
        }
    }

    override suspend fun clear() = blobs.delete(CONFIG_RECORD)
}

fun validateConfiguration(
    supabaseUrl: String,
    publishableKey: String,
    channelId: String,
    channelSecret: String,
    deviceName: String,
): CloudConfiguration {
    val endpoint = runCatching { java.net.URI(supabaseUrl) }.getOrNull()
    require(
        endpoint?.scheme == "https" &&
            endpoint.host != null &&
            (endpoint.rawPath.isNullOrEmpty() || endpoint.rawPath == "/") &&
            endpoint.rawQuery == null &&
            endpoint.rawFragment == null,
    ) {
        "Supabase URL must be an HTTPS origin."
    }
    require(publishableKey.isNotBlank() && publishableKey.length <= 8_192) { "Invalid Supabase public key." }
    ProtocolValueValidator.requireChannelId(channelId)
    require(channelSecret.isNotEmpty()) { "Channel secret is required." }
    require(deviceName.isNotEmpty()) { "Device name is required." }
    require(deviceName.codePointCount(0, deviceName.length) <= ProtocolV1.MAX_DEVICE_NAME_CODE_POINTS) {
        "Device name exceeds the V1 Unicode limit."
    }
    return CloudConfiguration(
        CloudEndpoint(supabaseUrl.removeSuffix("/"), publishableKey),
        channelId,
        channelSecret.toCharArray(),
        deviceName,
    )
}

@Serializable
private data class StoredCloudConfiguration(
    val supabaseUrl: String,
    val publishableKey: String,
    val channelId: String,
    val channelSecret: String,
    val deviceName: String,
)
