package com.nishantattrey.clipsync.core.sync.identity

import com.nishantattrey.clipsync.core.protocol.crypto.DerivedKeys
import com.nishantattrey.clipsync.core.protocol.crypto.JcaAesGcm
import com.nishantattrey.clipsync.core.protocol.model.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceProfileCodecTest {
    private val keys = DerivedKeys("ab".repeat(32), ByteArray(32) { 3 }, ByteArray(32) { 4 })

    @Test fun profileRoundTripPreservesCanonicalFields() {
        val codec = DeviceProfileCodec(JcaAesGcm { ByteArray(it) { 9 } })
        val profile = DeviceProfile("Android 🚀", "Android", "1.0", 1)
        assertEquals(profile, codec.decrypt(codec.encrypt(profile, keys), keys))
    }

    @Test fun wrongKeyFailsAuthenticationWithoutLeakingProfile() {
        val codec = DeviceProfileCodec(JcaAesGcm { ByteArray(it) { 8 } })
        val ciphertext = codec.encrypt(DeviceProfile("Private device", "Android", "1.0", 1), keys)
        val error = assertThrows(SecurityException::class.java) {
            codec.decrypt(ciphertext, DerivedKeys(keys.channelId, ByteArray(32) { 7 }, keys.hmacKey))
        }
        require(!error.message.orEmpty().contains("Private device"))
    }

    @Test fun androidAcceptsValidMacOsDeviceProfile() {
        val codec = DeviceProfileCodec(JcaAesGcm { ByteArray(it) { 7 } })
        val profile = DeviceProfile("Nishant's Mac", "macOS", "1.0", 1)

        assertEquals(profile, codec.decrypt(codec.encrypt(profile, keys), keys))
    }
}
