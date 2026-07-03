package com.nishantattrey.clipsync.core.sync.image

import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import java.io.ByteArrayOutputStream
import java.io.InputStream

object BoundedImageReader {
    fun read(input: InputStream, maximum: Int = ProtocolV1.MAX_IMAGE_PLAINTEXT_BYTES): ByteArray {
        require(maximum in 1..ProtocolV1.MAX_STORAGE_OBJECT_BYTES)
        val output = ByteArrayOutputStream(minOf(maximum, 64 * 1024))
        val buffer = ByteArray(32 * 1024)
        var total = 0
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total = Math.addExact(total, count)
                if (total > maximum) throw InvalidImageException("Image exceeds the encoded-size limit.")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }
}
