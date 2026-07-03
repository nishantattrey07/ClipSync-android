package com.nishantattrey.clipsync.core.sync.image

import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import com.nishantattrey.clipsync.core.protocol.crypto.DerivedKeys
import com.nishantattrey.clipsync.core.protocol.crypto.Hashes
import com.nishantattrey.clipsync.core.protocol.crypto.RandomBytes
import com.nishantattrey.clipsync.core.protocol.encoding.WireEncoding
import com.nishantattrey.clipsync.core.sync.config.SecureBlobStore

class LocalImageKeyStore(
    private val blobs: SecureBlobStore,
    private val random: RandomBytes,
) {
    suspend fun loadOrCreate(): DerivedKeys {
        val existing = blobs.read(RECORD)
        val key = existing ?: random.next(ProtocolV1.DERIVED_KEY_BYTES).also {
            require(it.size == ProtocolV1.DERIVED_KEY_BYTES)
            blobs.write(RECORD, it)
        }
        require(key.size == ProtocolV1.DERIVED_KEY_BYTES) { "Local image key is corrupt." }
        val channel = Hashes.sha256(key)
        return try {
            DerivedKeys(WireEncoding.lowercaseHex(channel), key.copyOf(), ByteArray(ProtocolV1.DERIVED_KEY_BYTES))
        } finally {
            channel.fill(0)
            key.fill(0)
        }
    }

    private companion object { const val RECORD = "local_image_encryption_key" }
}
