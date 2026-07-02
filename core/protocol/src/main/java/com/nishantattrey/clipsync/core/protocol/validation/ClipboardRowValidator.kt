package com.nishantattrey.clipsync.core.protocol.validation

import com.nishantattrey.clipsync.core.protocol.model.ClipboardItemRecord
import java.util.UUID

object ClipboardRowValidator {
    private val channelPattern = Regex("[0-9a-f]{64}")

    fun validate(record: ClipboardItemRecord, activeChannelId: String) {
        require(channelPattern.matches(record.channelId) && record.channelId == activeChannelId) {
            "Clipboard row belongs to an invalid or inactive channel."
        }
        UUID.fromString(record.id)
        UUID.fromString(record.deviceId)
        ServerTimestampCodec.parseMicroseconds(record.createdAt)
        ProtocolValueValidator.requirePayload(
            record.kind,
            record.payloadVersion,
            record.ciphertext,
            record.imagePath,
            record.thumbnailCiphertext,
            record.mimeType,
        )
    }
}
