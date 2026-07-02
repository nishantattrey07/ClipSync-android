package com.nishantattrey.clipsync.core.protocol.crypto

import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecureRandomBytes(private val secureRandom: SecureRandom = SecureRandom()) : RandomBytes {
    override fun next(byteCount: Int): ByteArray = ByteArray(byteCount).also(secureRandom::nextBytes)
}

class JcaAesGcm(private val randomBytes: RandomBytes = SecureRandomBytes()) : AuthenticatedEncryption {
    override fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == ProtocolV1.DERIVED_KEY_BYTES) { "AES-256 requires a 32-byte key." }
        val nonce = randomBytes.next(ProtocolV1.AES_GCM_NONCE_BYTES)
        require(nonce.size == ProtocolV1.AES_GCM_NONCE_BYTES) { "Invalid nonce source output." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return nonce + cipher.doFinal(plaintext)
    }

    override fun decrypt(combined: ByteArray, key: ByteArray): ByteArray {
        require(key.size == ProtocolV1.DERIVED_KEY_BYTES) { "AES-256 requires a 32-byte key." }
        require(combined.size >= ProtocolV1.AES_GCM_NONCE_BYTES + ProtocolV1.AES_GCM_TAG_BYTES) {
            "Truncated AES-GCM envelope."
        }
        val nonce = combined.copyOfRange(0, ProtocolV1.AES_GCM_NONCE_BYTES)
        val ciphertextAndTag = combined.copyOfRange(ProtocolV1.AES_GCM_NONCE_BYTES, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        try {
            return cipher.doFinal(ciphertextAndTag)
        } catch (error: AEADBadTagException) {
            throw AuthenticationFailedException(error)
        }
    }
}

object Hashes {
    fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    fun hmacSha256(key: ByteArray, value: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value)
    }
}

class AuthenticationFailedException(cause: Throwable) : SecurityException(
    "AES-GCM authentication failed.",
    cause,
)
