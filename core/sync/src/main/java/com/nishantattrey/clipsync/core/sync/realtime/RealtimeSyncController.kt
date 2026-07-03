package com.nishantattrey.clipsync.core.sync.realtime

import com.nishantattrey.clipsync.core.sync.engine.CloudSyncCoordinator
import com.nishantattrey.clipsync.core.sync.model.CloudConfigurationStore
import com.nishantattrey.clipsync.core.sync.model.RealtimeWakeSourceFactory
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RealtimeSyncController(
    private val scope: CoroutineScope,
    private val configurations: CloudConfigurationStore,
    private val sources: RealtimeWakeSourceFactory,
    private val coordinator: CloudSyncCoordinator,
    private val jitterMillis: (Long) -> Long = { ceiling -> kotlin.random.Random.nextLong(0, ceiling + 1) },
) {
    private var job: Job? = null

    @Synchronized fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var attempt = 0
            while (true) {
                val configuration = configurations.load() ?: return@launch
                try {
                    val source = sources.create(configuration.endpoint)
                    val channelId = configuration.channelId
                    configuration.clearSensitive()
                    source.awaitWake(channelId)
                    attempt = 0
                    coordinator.synchronize()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    configuration.clearSensitive()
                    val base = min(1_000L shl attempt.coerceIn(0, 8), 300_000L)
                    delay(base + jitterMillis(base / 4))
                    attempt++
                }
            }
        }
    }

    @Synchronized fun stop() {
        job?.cancel()
        job = null
    }
}
