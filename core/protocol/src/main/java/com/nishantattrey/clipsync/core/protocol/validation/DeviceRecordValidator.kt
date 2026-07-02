package com.nishantattrey.clipsync.core.protocol.validation

import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import com.nishantattrey.clipsync.core.protocol.model.ClipboardDeviceRecord
import java.util.UUID

object DeviceRecordValidator {
    private val channelPattern = Regex("[0-9a-f]{64}")

    fun validate(record: ClipboardDeviceRecord, activeChannelId: String) {
        require(channelPattern.matches(record.channelId) && record.channelId == activeChannelId) {
            "Device row belongs to an invalid or inactive channel."
        }
        UUID.fromString(record.deviceId)
        require(record.profileVersion == ProtocolV1.PROFILE_VERSION) {
            "Unsupported device profile version."
        }
        ProtocolValueValidator.requireProfileCiphertext(record.profileCiphertext)
        ServerTimestampCodec.parseMicroseconds(record.createdAt)
        ServerTimestampCodec.parseMicroseconds(record.updatedAt)
    }
}
