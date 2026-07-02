package com.nishantattrey.clipsync.core.protocol.validation

import com.nishantattrey.clipsync.core.protocol.ProtocolV1

object DeviceNameValidator {
    fun normalizeAndValidate(value: String): String {
        val normalized = value.trim { it.isWhitespace() }
        require(normalized.isNotEmpty()) { "Device name must not be empty." }
        require(normalized.codePointCount(0, normalized.length) <= ProtocolV1.MAX_DEVICE_NAME_CODE_POINTS) {
            "Device name exceeds the V1 code-point limit."
        }
        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            require(!isForbidden(codePoint)) { "Device name contains a forbidden control character." }
            offset += Character.charCount(codePoint)
        }
        return normalized
    }

    private fun isForbidden(codePoint: Int): Boolean =
        codePoint in 0x0000..0x001F ||
            codePoint in 0x007F..0x009F ||
            codePoint == 0x2028 ||
            codePoint == 0x2029
}
