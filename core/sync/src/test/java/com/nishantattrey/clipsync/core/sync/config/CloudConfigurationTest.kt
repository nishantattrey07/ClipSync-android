package com.nishantattrey.clipsync.core.sync.config

import com.nishantattrey.clipsync.core.sync.model.CloudConfiguration
import com.nishantattrey.clipsync.core.sync.model.CloudEndpoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudConfigurationTest {
    @Test fun supabaseUrlMustBeAnOriginWithoutRestPath() {
        assertThrows(IllegalArgumentException::class.java) {
            validateConfiguration("https://example.test/rest/v1", "public", channelId, "secret", "Android")
        }
    }
    private val channelId = "ab".repeat(32)

    @Test fun unicodeDeviceNameLimitCountsCodePoints() {
        val eightyEmoji = buildString { repeat(80) { appendCodePoint(0x1F680) } }
        assertEquals(eightyEmoji, valid(deviceName = eightyEmoji).deviceName)
        assertThrows(IllegalArgumentException::class.java) { valid(deviceName = eightyEmoji + "x") }
    }

    @Test fun rejectsNonHttpsAndDoesNotEchoSecrets() {
        val secret = "should-never-appear"
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateConfiguration("http://example.test", "public", channelId, secret, "Android")
        }
        assertFalse(error.message.orEmpty().contains(secret))
    }

    @Test fun encryptedStoreSurvivesRepositoryRestart() = runTest {
        val blobs = MemoryBlobStore()
        EncryptedCloudConfigurationStore(blobs).save(
            CloudConfiguration(CloudEndpoint("https://example.test", "public"), channelId, "secret".toCharArray(), "Android"),
        )
        val loaded = EncryptedCloudConfigurationStore(blobs).load()!!
        assertEquals(channelId, loaded.channelId)
        assertEquals("secret", loaded.channelPassword.concatToString())
        loaded.clearSensitive()
    }

    private fun valid(deviceName: String) =
        validateConfiguration("https://example.test", "public", channelId, "secret", deviceName)
}

internal class MemoryBlobStore : SecureBlobStore {
    private val values = mutableMapOf<String, ByteArray>()
    override suspend fun read(name: String): ByteArray? = values[name]?.copyOf()
    override suspend fun write(name: String, value: ByteArray) { values[name] = value.copyOf() }
    override suspend fun delete(name: String) { values.remove(name)?.fill(0) }
}
