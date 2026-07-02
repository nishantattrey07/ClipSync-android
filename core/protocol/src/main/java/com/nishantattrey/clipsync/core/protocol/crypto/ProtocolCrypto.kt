package com.nishantattrey.clipsync.core.protocol.crypto

data class DerivedKeys(
    val channelId: String,
    val encryptionKey: ByteArray,
    val hmacKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is DerivedKeys &&
        channelId == other.channelId &&
        encryptionKey.contentEquals(other.encryptionKey) &&
        hmacKey.contentEquals(other.hmacKey)

    override fun hashCode(): Int = 31 * (31 * channelId.hashCode() + encryptionKey.contentHashCode()) +
        hmacKey.contentHashCode()
}

fun interface KeyDeriver {
    fun derive(secret: String): DerivedKeys
}

fun interface RandomBytes {
    fun next(byteCount: Int): ByteArray
}

interface AuthenticatedEncryption {
    fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray
    fun decrypt(combined: ByteArray, key: ByteArray): ByteArray
}
