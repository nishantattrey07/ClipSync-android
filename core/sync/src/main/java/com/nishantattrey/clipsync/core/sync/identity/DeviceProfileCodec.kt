package com.nishantattrey.clipsync.core.sync.identity

import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import com.nishantattrey.clipsync.core.protocol.crypto.AuthenticatedEncryption
import com.nishantattrey.clipsync.core.protocol.crypto.DerivedKeys
import com.nishantattrey.clipsync.core.protocol.encoding.WireEncoding
import com.nishantattrey.clipsync.core.protocol.model.DeviceProfile
import com.nishantattrey.clipsync.core.protocol.validation.DeviceNameValidator
import kotlinx.serialization.json.Json

class DeviceProfileCodec(
    private val encryption: AuthenticatedEncryption,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) {
    fun encrypt(profile: DeviceProfile, keys: DerivedKeys): String {
        validate(profile)
        val plaintext = json.encodeToString(DeviceProfile.serializer(), profile).encodeToByteArray()
        return try {
            val encrypted = encryption.encrypt(plaintext, keys.encryptionKey)
            try { WireEncoding.standardBase64(encrypted) } finally { encrypted.fill(0) }
        } finally {
            plaintext.fill(0)
        }
    }

    fun decrypt(ciphertext: String, keys: DerivedKeys): DeviceProfile {
        val encrypted = WireEncoding.decodeStandardBase64(ciphertext)
        val plaintext = try { encryption.decrypt(encrypted, keys.encryptionKey) } finally { encrypted.fill(0) }
        return try {
            json.decodeFromString(DeviceProfile.serializer(), plaintext.decodeToString()).also(::validate)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun validate(profile: DeviceProfile) {
        require(DeviceNameValidator.normalizeAndValidate(profile.ownerName) == profile.ownerName) {
            "Device name is not in canonical form."
        }
        require(profile.platform == "Android" || profile.platform == "macOS") {
            "Unsupported device platform."
        }
        require(profile.appVersion.isNotBlank()) { "Device app version is required." }
        require(profile.protocolVersion == ProtocolV1.PROFILE_PROTOCOL_VERSION) { "Unsupported profile protocol version." }
    }
}
