package com.nishantattrey.clipsync.core.sync.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.nishantattrey.clipsync.core.sync.config.SecureBlobStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidSecureBlobStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SecureBlobStore {
    private val preferences = context.getSharedPreferences("clipsync_cloud_secure_v1", Context.MODE_PRIVATE)

    override suspend fun read(name: String): ByteArray? = withContext(ioDispatcher) {
        val encoded = preferences.getString(name, null) ?: return@withContext null
        val envelope = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        require(envelope.size >= NONCE_BYTES + TAG_BYTES) { "Secure configuration is corrupt." }
        val nonce = envelope.copyOfRange(0, NONCE_BYTES)
        val body = envelope.copyOfRange(NONCE_BYTES, envelope.size)
        try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(name.encodeToByteArray())
                doFinal(body)
            }
        } finally {
            nonce.fill(0)
            body.fill(0)
            envelope.fill(0)
        }
    }

    override suspend fun write(name: String, value: ByteArray) = withContext(ioDispatcher) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.encodeToByteArray())
        val body = cipher.doFinal(value)
        val envelope = cipher.iv + body
        try {
            check(preferences.edit().putString(name, android.util.Base64.encodeToString(envelope, android.util.Base64.NO_WRAP)).commit()) {
                "Secure configuration could not be persisted."
            }
        } finally {
            body.fill(0)
            envelope.fill(0)
        }
    }

    override suspend fun delete(name: String) = withContext(ioDispatcher) {
        check(preferences.edit().remove(name).commit()) { "Secure configuration could not be removed." }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "clipsync.cloud.configuration.aes.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        const val TAG_BITS = 128
    }
}
