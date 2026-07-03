package com.nishantattrey.clipsync.core.sync.realtime

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Coalesces any number of Realtime/lifecycle wakes into one non-overlapping durable catch-up. */
class RealtimeCatchUpCoalescer(
    scope: CoroutineScope,
    private val catchUp: suspend () -> Unit,
) {
    private val wakes = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)
    private var worker: Job? = null

    init {
        worker = scope.launch {
            started.set(true)
            for (ignored in wakes) catchUp()
        }
    }

    fun wake() { wakes.trySend(Unit) }

    fun close() {
        wakes.close()
        worker?.cancel()
    }

    fun isStarted(): Boolean = started.get()
}
