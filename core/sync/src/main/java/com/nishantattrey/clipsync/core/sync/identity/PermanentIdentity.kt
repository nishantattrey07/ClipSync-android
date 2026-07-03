package com.nishantattrey.clipsync.core.sync.identity

import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import com.nishantattrey.clipsync.core.protocol.crypto.RandomBytes
import com.nishantattrey.clipsync.core.protocol.encoding.WireEncoding
import com.nishantattrey.clipsync.core.protocol.validation.ProtocolValueValidator
import com.nishantattrey.clipsync.core.sync.config.SecureBlobStore
import com.nishantattrey.clipsync.core.sync.model.DeviceIdentityStore
import com.nishantattrey.clipsync.core.sync.model.PermanentDeviceIdentity
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class EncryptedDeviceIdentityStore(
    private val blobs: SecureBlobStore,
    private val random: RandomBytes,
    private val uuid: () -> String = { UUID.randomUUID().toString() },
    private val json: Json = Json,
) : DeviceIdentityStore {
    override suspend fun loadOrCreate(): PermanentDeviceIdentity {
        val existing = blobs.read(RECORD)
        if (existing != null) {
            return try {
                json.decodeFromString<StoredIdentity>(existing.decodeToString()).validated()
            } finally {
                existing.fill(0)
            }
        }
        val secretBytes = random.next(ProtocolV1.GENERATED_SECRET_BYTES)
        require(secretBytes.size == ProtocolV1.GENERATED_SECRET_BYTES) { "Invalid secure random output." }
        val created = try {
            PermanentDeviceIdentity(uuid(), WireEncoding.base64UrlWithoutPadding(secretBytes)).validated()
        } finally {
            secretBytes.fill(0)
        }
        val encoded = json.encodeToString(StoredIdentity(created.deviceId, created.deviceSecret)).encodeToByteArray()
        try {
            blobs.write(RECORD, encoded)
        } finally {
            encoded.fill(0)
        }
        return created
    }

    override suspend fun clear() = blobs.delete(RECORD)

    private fun StoredIdentity.validated() = PermanentDeviceIdentity(deviceId, deviceSecret).validated()
    private fun PermanentDeviceIdentity.validated() = apply {
        ProtocolValueValidator.requireUuid(deviceId, "device")
        ProtocolValueValidator.requireDeviceSecret(deviceSecret)
    }

    private companion object { const val RECORD = "permanent_device_identity" }
}

@Serializable
private data class StoredIdentity(val deviceId: String, val deviceSecret: String)
