package com.nishantattrey.clipsync.core.sync.realtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeCatchUpCoalescerTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun burstIsCoalescedAndCatchUpsNeverOverlap() = runTest {
        var calls = 0
        var active = 0
        var maximumActive = 0
        val release = CompletableDeferred<Unit>()
        val coalescer = RealtimeCatchUpCoalescer(this) {
            calls++
            active++
            maximumActive = maxOf(maximumActive, active)
            release.await()
            active--
        }
        coalescer.wake()
        testScheduler.runCurrent()
        repeat(20) { coalescer.wake() }
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, maximumActive)
        assertEquals(2, calls) // one active wake plus one conflated wake while it was active
        coalescer.close()
    }
}
