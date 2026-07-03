package com.nishantattrey.clipsync.core.sync.image

import com.nishantattrey.clipsync.core.protocol.crypto.RandomBytes
import com.nishantattrey.clipsync.core.sync.config.MemoryBlobStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalImageKeyStoreTest {
    @Test fun localImageKeySurvivesRestartWithoutPlaintextPersistence() = runTest {
        val blobs = MemoryBlobStore()
        val first = LocalImageKeyStore(blobs, RandomBytes { ByteArray(it) { 7 } }).loadOrCreate()
        val second = LocalImageKeyStore(blobs, RandomBytes { error("must not regenerate") }).loadOrCreate()
        assertEquals(first.channelId, second.channelId)
        assertArrayEquals(first.encryptionKey, second.encryptionKey)
        first.encryptionKey.fill(0)
        second.encryptionKey.fill(0)
    }
}
