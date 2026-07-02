package com.nishantattrey.clipsync.core.protocol.validation

import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import com.nishantattrey.clipsync.core.protocol.encoding.WireEncoding
import java.util.UUID

object ProtocolValueValidator {
    private val channelPattern = Regex("[0-9a-f]{64}")
    private val deviceSecretPattern = Regex("[A-Za-z0-9_-]{43}")

    fun requireChannelId(value: String) {
        require(channelPattern.matches(value)) { "Invalid channel identifier." }
    }

    fun requireUuid(value: String, field: String) {
        try {
            UUID.fromString(value)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid $field UUID.", error)
        }
    }

    fun requireDeviceSecret(value: String) {
        require(deviceSecretPattern.matches(value)) { "Invalid device credential." }
        require(WireEncoding.decodeBase64UrlWithoutPadding(value).size == ProtocolV1.GENERATED_SECRET_BYTES) {
            "Invalid device credential length."
        }
    }

    fun requireProfileCiphertext(value: String) {
        require(value.isNotEmpty() && value.length <= ProtocolV1.MAX_PROFILE_CIPHERTEXT_BYTES) {
            "Encrypted device profile exceeds the V1 limit."
        }
        requireCompleteEnvelope(value, "device profile")
    }

    fun requireCursorPair(timestamp: String?, id: String?, fieldPrefix: String) {
        require((timestamp == null) == (id == null)) {
            "$fieldPrefix cursor timestamp and UUID must both be null or both be present."
        }
        if (timestamp != null && id != null) {
            ServerTimestampCodec.parseMicroseconds(timestamp)
            requireUuid(id, "$fieldPrefix cursor")
        }
    }

    fun requirePayload(
        kind: String,
        payloadVersion: Int,
        ciphertext: String?,
        imagePath: String?,
        thumbnailCiphertext: String?,
        mimeType: String?,
    ) {
        require(payloadVersion == ProtocolV1.PAYLOAD_VERSION) {
            "Unsupported clipboard payload version."
        }
        when (kind) {
            "text" -> {
                require(ciphertext != null && imagePath == null &&
                    thumbnailCiphertext == null && mimeType == null
                ) {
                    "Invalid text payload fields."
                }
                require(ciphertext.length <= ProtocolV1.MAX_TEXT_CIPHERTEXT_BYTES) {
                    "Encrypted text exceeds the V1 limit."
                }
                requireCompleteEnvelope(ciphertext, "text")
            }
            "image" -> {
                require(ciphertext == null && imagePath != null) {
                    "Invalid image payload fields."
                }
                StoragePathValidator.validate(imagePath)
                require(mimeType == "image/png" || mimeType == "image/jpeg") {
                    "Unsupported image MIME type."
                }
                require(thumbnailCiphertext?.let {
                    it.length <= ProtocolV1.MAX_THUMBNAIL_CIPHERTEXT_BYTES
                } != false) {
                    "Encrypted thumbnail exceeds the V1 limit."
                }
                thumbnailCiphertext?.let { requireCompleteEnvelope(it, "thumbnail") }
            }
            else -> throw IllegalArgumentException("Unsupported clipboard kind.")
        }
    }

    private fun requireCompleteEnvelope(value: String, field: String) {
        require(WireEncoding.standardBase64DecodedByteCount(value) >=
            ProtocolV1.AES_GCM_NONCE_BYTES + ProtocolV1.AES_GCM_TAG_BYTES
        ) {
            "Encrypted $field is not a complete canonical AES-GCM envelope."
        }
    }
}
