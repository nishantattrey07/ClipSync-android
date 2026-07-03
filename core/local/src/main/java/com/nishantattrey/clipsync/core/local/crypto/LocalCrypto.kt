package com.nishantattrey.clipsync.core.local.crypto

import com.nishantattrey.clipsync.core.local.model.LocalKeyPurpose
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties

const val LOCAL_ENVELOPE_VERSION: Int = 1
const val LOCAL_PAYLOAD_KEY_ALIAS = "clipsync.local.payload.aes.v1"
const val LOCAL_FINGERPRINT_KEY_ALIAS = "clipsync.local.fingerprint.hmac.v1"

data class LocalEncryptionEnvelope(
    val version: Int,
    val nonce: ByteArray,
    val ciphertextAndTag: ByteArray,
)

interface LocalPayloadCipher {
    fun encrypt(id: String, plaintext: ByteArray): ByteArray
    fun decrypt(id: String, envelope: ByteArray): ByteArray
    fun verifyUsable()
}

interface LocalFingerprint {
    fun compute(plaintext: ByteArray): ByteArray
    fun verifyUsable()
}

interface LocalKeyMaterial {
    fun get(purpose: LocalKeyPurpose): SecretKey?
    fun getOrCreate(purpose: LocalKeyPurpose): SecretKey
    fun deleteAll()
}

class MissingLocalKeyException(val purpose: LocalKeyPurpose) : GeneralSecurityException()
class InvalidatedLocalKeyException(val purpose: LocalKeyPurpose, cause: Throwable) : GeneralSecurityException(cause)
class CorruptLocalPayloadException(cause: Throwable? = null) : GeneralSecurityException(cause)

object LocalEnvelopeCodec {
    private val magic = byteArrayOf('C'.code.toByte(), 'S'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte())
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16

    fun encode(envelope: LocalEncryptionEnvelope): ByteArray {
        require(envelope.version == LOCAL_ENVELOPE_VERSION)
        require(envelope.nonce.size == NONCE_SIZE)
        require(envelope.ciphertextAndTag.size >= TAG_SIZE)
        return ByteBuffer.allocate(magic.size + 2 + NONCE_SIZE + envelope.ciphertextAndTag.size)
            .put(magic)
            .put(envelope.version.toByte())
            .put(NONCE_SIZE.toByte())
            .put(envelope.nonce)
            .put(envelope.ciphertextAndTag)
            .array()
    }

    fun decode(encoded: ByteArray): LocalEncryptionEnvelope {
        if (encoded.size < magic.size + 2 + NONCE_SIZE + TAG_SIZE) throw CorruptLocalPayloadException()
        val buffer = ByteBuffer.wrap(encoded)
        val actualMagic = ByteArray(magic.size).also(buffer::get)
        val version = buffer.get().toInt() and 0xff
        val nonceSize = buffer.get().toInt() and 0xff
        if (!actualMagic.contentEquals(magic) || version != LOCAL_ENVELOPE_VERSION || nonceSize != NONCE_SIZE) {
            throw CorruptLocalPayloadException()
        }
        val nonce = ByteArray(nonceSize).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        if (ciphertext.size < TAG_SIZE) throw CorruptLocalPayloadException()
        return LocalEncryptionEnvelope(version, nonce, ciphertext)
    }
}

class AesGcmLocalPayloadCipher(private val keys: LocalKeyMaterial) : LocalPayloadCipher {
    override fun encrypt(id: String, plaintext: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val key = keys.get(LocalKeyPurpose.PAYLOAD_ENCRYPTION)
                ?: throw MissingLocalKeyException(LocalKeyPurpose.PAYLOAD_ENCRYPTION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad(id))
            LocalEnvelopeCodec.encode(
                LocalEncryptionEnvelope(LOCAL_ENVELOPE_VERSION, cipher.iv.copyOf(), cipher.doFinal(plaintext)),
            )
        } catch (error: KeyPermanentlyInvalidatedException) {
            throw InvalidatedLocalKeyException(LocalKeyPurpose.PAYLOAD_ENCRYPTION, error)
        } catch (error: InvalidKeyException) {
            throw InvalidatedLocalKeyException(LocalKeyPurpose.PAYLOAD_ENCRYPTION, error)
        } catch (error: ProviderException) {
            throw GeneralSecurityException(error)
        }
    }

    override fun decrypt(id: String, envelope: ByteArray): ByteArray {
        val key = keys.get(LocalKeyPurpose.PAYLOAD_ENCRYPTION)
            ?: throw MissingLocalKeyException(LocalKeyPurpose.PAYLOAD_ENCRYPTION)
        val decoded = LocalEnvelopeCodec.decode(envelope)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decoded.nonce))
                updateAAD(aad(id))
                doFinal(decoded.ciphertextAndTag)
            }
        } catch (error: AEADBadTagException) {
            throw CorruptLocalPayloadException(error)
        } catch (error: KeyPermanentlyInvalidatedException) {
            throw InvalidatedLocalKeyException(LocalKeyPurpose.PAYLOAD_ENCRYPTION, error)
        } catch (error: InvalidKeyException) {
            throw InvalidatedLocalKeyException(LocalKeyPurpose.PAYLOAD_ENCRYPTION, error)
        } catch (error: ProviderException) {
            throw GeneralSecurityException(error)
        }
    }

    override fun verifyUsable() {
        val encrypted = encrypt("00000000-0000-0000-0000-000000000000", ByteArray(0))
        try {
            val decrypted = decrypt("00000000-0000-0000-0000-000000000000", encrypted)
            try {
                if (decrypted.isNotEmpty()) throw GeneralSecurityException("Payload key probe failed.")
            } finally {
                decrypted.fill(0)
            }
        } finally {
            encrypted.fill(0)
        }
    }

    private fun aad(id: String): ByteArray =
        "clipsync-local-envelope:$LOCAL_ENVELOPE_VERSION:text:$id".toByteArray(StandardCharsets.UTF_8)
}

class HmacSha256LocalFingerprint(private val keys: LocalKeyMaterial) : LocalFingerprint {
    override fun compute(plaintext: ByteArray): ByteArray = try {
        Mac.getInstance("HmacSHA256").run {
            val key = keys.get(LocalKeyPurpose.DEDUP_FINGERPRINT)
                ?: throw MissingLocalKeyException(LocalKeyPurpose.DEDUP_FINGERPRINT)
            init(key)
            doFinal(plaintext)
        }
    } catch (error: KeyPermanentlyInvalidatedException) {
        throw InvalidatedLocalKeyException(LocalKeyPurpose.DEDUP_FINGERPRINT, error)
    } catch (error: InvalidKeyException) {
        throw InvalidatedLocalKeyException(LocalKeyPurpose.DEDUP_FINGERPRINT, error)
    } catch (error: ProviderException) {
        throw GeneralSecurityException(error)
    }

    override fun verifyUsable() {
        compute(ByteArray(0)).fill(0)
    }
}

class AndroidKeystoreLocalKeyMaterial : LocalKeyMaterial {
    private val keyStore: KeyStore
        get() = try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        } catch (error: GeneralSecurityException) {
            throw error
        } catch (error: Exception) {
            throw GeneralSecurityException(error)
        }

    override fun get(purpose: LocalKeyPurpose): SecretKey? = try {
        keyStore.getKey(alias(purpose), null) as? SecretKey
    } catch (error: UnrecoverableKeyException) {
        throw InvalidatedLocalKeyException(purpose, error)
    }

    override fun getOrCreate(purpose: LocalKeyPurpose): SecretKey = get(purpose) ?: when (purpose) {
        LocalKeyPurpose.PAYLOAD_ENCRYPTION -> generate(purpose, ::generateAes)
        LocalKeyPurpose.DEDUP_FINGERPRINT -> generate(purpose, ::generateHmac)
    }

    override fun deleteAll() {
        try {
            val store = keyStore
            LocalKeyPurpose.entries.forEach { store.deleteEntry(alias(it)) }
        } catch (error: ProviderException) {
            throw GeneralSecurityException(error)
        }
    }

    private fun generate(purpose: LocalKeyPurpose, block: () -> SecretKey): SecretKey = try {
        block()
    } catch (error: KeyPermanentlyInvalidatedException) {
        throw InvalidatedLocalKeyException(purpose, error)
    } catch (error: InvalidKeyException) {
        throw InvalidatedLocalKeyException(purpose, error)
    } catch (error: ProviderException) {
        throw GeneralSecurityException(error)
    }

    private fun generateAes(): SecretKey = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        "AndroidKeyStore",
    ).run {
        init(
            KeyGenParameterSpec.Builder(
                LOCAL_PAYLOAD_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generateKey()
    }

    private fun generateHmac(): SecretKey = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
        "AndroidKeyStore",
    ).run {
        init(
            KeyGenParameterSpec.Builder(
                LOCAL_FINGERPRINT_KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            ).setKeySize(256).setDigests(KeyProperties.DIGEST_SHA256).build(),
        )
        generateKey()
    }

    private fun alias(purpose: LocalKeyPurpose): String = when (purpose) {
        LocalKeyPurpose.PAYLOAD_ENCRYPTION -> LOCAL_PAYLOAD_KEY_ALIAS
        LocalKeyPurpose.DEDUP_FINGERPRINT -> LOCAL_FINGERPRINT_KEY_ALIAS
    }
}
