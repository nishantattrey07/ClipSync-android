package com.nishantattrey.clipsync.core.protocol.crypto

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import com.nishantattrey.clipsync.core.protocol.encoding.WireEncoding

class Argon2KeyDeriver(
    private val argon2: Argon2Kt = Argon2Kt(),
) : KeyDeriver {
    override fun derive(secret: String): DerivedKeys {
        val secretBytes = secret.toByteArray(Charsets.UTF_8)
        val salt = try {
            Hashes.sha256(secretBytes).copyOf(ProtocolV1.ARGON2_SALT_BYTES)
        } catch (error: Throwable) {
            secretBytes.fill(0)
            throw error
        }

        val masterKey = try {
            argon2.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = secretBytes,
                salt = salt,
                tCostInIterations = ProtocolV1.ARGON2_ITERATIONS,
                mCostInKibibyte = ProtocolV1.ARGON2_MEMORY_KIB,
                parallelism = ProtocolV1.ARGON2_PARALLELISM,
            ).rawHashAsByteArray()
        } finally {
            secretBytes.fill(0)
            salt.fill(0)
        }

        return try {
            require(masterKey.size == ProtocolV1.DERIVED_KEY_BYTES) { "Argon2Kt returned an invalid key length." }
            val hkdfSalt = ProtocolV1.HKDF_SALT.toByteArray(Charsets.UTF_8)
            val channelBytes = HkdfSha256.derive(
                masterKey,
                hkdfSalt,
                ProtocolV1.CHANNEL_INFO.toByteArray(Charsets.UTF_8),
                ProtocolV1.DERIVED_KEY_BYTES,
            )
            DerivedKeys(
                channelId = WireEncoding.lowercaseHex(channelBytes),
                encryptionKey = HkdfSha256.derive(
                    masterKey,
                    hkdfSalt,
                    ProtocolV1.ENCRYPTION_INFO.toByteArray(Charsets.UTF_8),
                    ProtocolV1.DERIVED_KEY_BYTES,
                ),
                hmacKey = HkdfSha256.derive(
                    masterKey,
                    hkdfSalt,
                    ProtocolV1.HMAC_INFO.toByteArray(Charsets.UTF_8),
                    ProtocolV1.DERIVED_KEY_BYTES,
                ),
            )
        } finally {
            masterKey.fill(0)
        }
    }
}
