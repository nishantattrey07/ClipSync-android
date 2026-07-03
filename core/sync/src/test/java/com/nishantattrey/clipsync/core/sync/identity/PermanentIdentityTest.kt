package com.nishantattrey.clipsync.core.sync.identity

import com.nishantattrey.clipsync.core.protocol.crypto.RandomBytes
import com.nishantattrey.clipsync.core.sync.config.MemoryBlobStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PermanentIdentityTest {
    @Test fun identityIsStableAcrossStoreRestart() = runTest {
        val blobs = MemoryBlobStore()
        val random = RandomBytes { count -> ByteArray(count) { it.toByte() } }
        val first = EncryptedDeviceIdentityStore(
            blobs,
            random,
            uuid = { "00000000-0000-0000-0000-000000000001" },
        ).loadOrCreate()
        val second = EncryptedDeviceIdentityStore(blobs, RandomBytes { error("must not regenerate") }).loadOrCreate()
        assertEquals(first, second)
    }
}
