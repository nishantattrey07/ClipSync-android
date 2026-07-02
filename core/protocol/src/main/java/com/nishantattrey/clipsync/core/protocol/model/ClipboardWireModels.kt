package com.nishantattrey.clipsync.core.protocol.model

import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import com.nishantattrey.clipsync.core.protocol.validation.ProtocolValueValidator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ClipboardItemRecord(
    val id: String,
    @SerialName("channel_id") val channelId: String,
    @SerialName("device_id") val deviceId: String,
    val kind: String,
    @SerialName("payload_version") val payloadVersion: Int,
    val ciphertext: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("thumbnail_ciphertext") val thumbnailCiphertext: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class ClipboardDeviceRecord(
    @SerialName("channel_id") val channelId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("profile_ciphertext") val profileCiphertext: String,
    @SerialName("profile_version") val profileVersion: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class RegisterDeviceParameters(
    @SerialName("p_channel_id") val channelId: String,
    @SerialName("p_device_id") val deviceId: String,
    @SerialName("p_profile_ciphertext") val profileCiphertext: String,
    @SerialName("p_profile_version") val profileVersion: Int,
    @SerialName("p_device_secret") val deviceSecret: String,
) {
    init {
        ProtocolValueValidator.requireChannelId(channelId)
        ProtocolValueValidator.requireUuid(deviceId, "device")
        ProtocolValueValidator.requireProfileCiphertext(profileCiphertext)
        require(profileVersion == ProtocolV1.PROFILE_VERSION) { "Unsupported device profile version." }
        ProtocolValueValidator.requireDeviceSecret(deviceSecret)
    }
}

@Serializable
data class InsertClipboardItemParameters(
    @SerialName("p_id") val id: String,
    @SerialName("p_channel_id") val channelId: String,
    @SerialName("p_device_id") val deviceId: String,
    @SerialName("p_device_secret") val deviceSecret: String,
    @SerialName("p_kind") val kind: String,
    @SerialName("p_payload_version") val payloadVersion: Int,
    @SerialName("p_ciphertext") val ciphertext: String? = null,
    @SerialName("p_image_path") val imagePath: String? = null,
    @SerialName("p_thumbnail_ciphertext") val thumbnailCiphertext: String? = null,
    @SerialName("p_mime_type") val mimeType: String? = null,
) {
    init {
        ProtocolValueValidator.requireUuid(id, "item")
        ProtocolValueValidator.requireChannelId(channelId)
        ProtocolValueValidator.requireUuid(deviceId, "device")
        ProtocolValueValidator.requireDeviceSecret(deviceSecret)
        ProtocolValueValidator.requirePayload(
            kind,
            payloadVersion,
            ciphertext,
            imagePath,
            thumbnailCiphertext,
            mimeType,
        )
    }
}

@Serializable
data class FetchPageParameters(
    @SerialName("p_channel_id") val channelId: String,
    @SerialName("p_limit") val limit: Int,
    @SerialName("p_before_timestamp") val beforeTimestamp: String? = null,
    @SerialName("p_before_id") val beforeId: String? = null,
    @SerialName("p_device_id") val deviceId: String? = null,
) {
    init {
        ProtocolValueValidator.requireChannelId(channelId)
        require(limit in 1..ProtocolV1.SERVER_MAX_PAGE_SIZE) { "Invalid page size." }
        ProtocolValueValidator.requireCursorPair(beforeTimestamp, beforeId, "Before")
        deviceId?.let { ProtocolValueValidator.requireUuid(it, "device filter") }
    }
}

@Serializable
data class FetchAfterParameters(
    @SerialName("p_channel_id") val channelId: String,
    @SerialName("p_limit") val limit: Int,
    @SerialName("p_after_timestamp") val afterTimestamp: String? = null,
    @SerialName("p_after_id") val afterId: String? = null,
) {
    init {
        ProtocolValueValidator.requireChannelId(channelId)
        require(limit in 1..ProtocolV1.SERVER_MAX_PAGE_SIZE) { "Invalid page size." }
        ProtocolValueValidator.requireCursorPair(afterTimestamp, afterId, "After")
    }
}

@Serializable
data class InsertItemResponse(val status: String)

enum class ClipboardInsertResult(val wireValue: String) {
    INSERTED("inserted"),
    ALREADY_PRESENT("already_present");

    companion object {
        fun parse(value: String): ClipboardInsertResult = entries.firstOrNull {
            it.wireValue == value
        } ?: throw IllegalArgumentException("Unknown clipboard insert result.")
    }
}
