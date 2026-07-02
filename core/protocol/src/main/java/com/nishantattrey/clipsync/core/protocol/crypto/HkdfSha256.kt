package com.nishantattrey.clipsync.core.protocol.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object HkdfSha256 {
    fun derive(inputKeyMaterial: ByteArray, salt: ByteArray, info: ByteArray, outputBytes: Int): ByteArray {
        require(outputBytes in 1..(255 * 32)) { "Invalid HKDF output length." }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val pseudoRandomKey = mac.doFinal(inputKeyMaterial)
        var previous = ByteArray(0)
        return try {
            val output = ByteArray(outputBytes)
            var offset = 0
            var counter = 1
            val spec = SecretKeySpec(pseudoRandomKey, "HmacSHA256")
            while (offset < outputBytes) {
                mac.init(spec)
                mac.update(previous)
                mac.update(info)
                mac.update(counter.toByte())
                previous.fill(0)
                previous = mac.doFinal()
                val count = minOf(previous.size, outputBytes - offset)
                previous.copyInto(output, offset, 0, count)
                offset += count
                counter += 1
            }
            output
        } finally {
            previous.fill(0)
            pseudoRandomKey.fill(0)
        }
    }
}
