package com.nishantattrey.clipsync.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nishantattrey.clipsync.core.protocol.crypto.KeyDeriver
import com.nishantattrey.clipsync.core.sync.config.validateConfiguration
import com.nishantattrey.clipsync.core.sync.engine.CloudSyncCoordinator
import com.nishantattrey.clipsync.core.sync.engine.SynchronizeResult
import com.nishantattrey.clipsync.core.sync.model.CloudConfigurationStore
import com.nishantattrey.clipsync.core.sync.model.SyncedDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SyncUiState(
    val configured: Boolean = false,
    val status: String = "Unconfigured",
    val isBusy: Boolean = false,
    val supabaseUrl: String = "",
    val publishableKey: String = "",
    val channelSecret: String = "",
    val deviceName: String = "Android",
    val devices: List<SyncedDevice> = emptyList(),
    val lastUploaded: Int = 0,
    val lastReceived: Int = 0,
    val error: String? = null,
)

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val configurations: CloudConfigurationStore,
    private val keyDeriver: KeyDeriver,
    private val coordinator: CloudSyncCoordinator,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = mutableState.asStateFlow()
    private var syncJob: Job? = null

    init {
        viewModelScope.launch {
            val loaded = runCatching { configurations.load() }.getOrNull()
            val configured = loaded != null
            val deviceName = loaded?.deviceName ?: state.value.deviceName
            loaded?.clearSensitive()
            mutableState.update {
                it.copy(
                    configured = configured,
                    status = if (configured) "Offline" else "Unconfigured",
                    deviceName = deviceName,
                )
            }
            if (configured) synchronize()
        }
    }

    fun updateUrl(value: String) = mutableState.update { it.copy(supabaseUrl = value) }
    fun updateKey(value: String) = mutableState.update { it.copy(publishableKey = value) }
    fun updateSecret(value: String) = mutableState.update { it.copy(channelSecret = value) }
    fun updateDeviceName(value: String) = mutableState.update { it.copy(deviceName = value) }

    fun saveDeviceName() = viewModelScope.launch {
        val existing = configurations.load() ?: return@launch
        try {
            val renamed = validateConfiguration(
                existing.endpoint.supabaseUrl,
                existing.endpoint.publishableKey,
                existing.channelId,
                existing.channelPassword.concatToString(),
                state.value.deviceName.trim(),
            )
            configurations.save(renamed)
            renamed.clearSensitive()
            mutableState.update { it.copy(deviceName = state.value.deviceName.trim(), error = null) }
            synchronize()
        } catch (_: IllegalArgumentException) {
            mutableState.update { it.copy(error = "Device name must be valid and no longer than 80 characters.") }
        } finally {
            existing.clearSensitive()
        }
    }

    fun saveConfiguration() = viewModelScope.launch {
        val snapshot = state.value
        mutableState.update { it.copy(isBusy = true, error = null, status = "Connecting") }
        try {
            val keys = withContext(Dispatchers.Default) { keyDeriver.derive(snapshot.channelSecret) }
            val configuration = try {
                validateConfiguration(
                    snapshot.supabaseUrl,
                    snapshot.publishableKey,
                    keys.channelId,
                    snapshot.channelSecret,
                    snapshot.deviceName,
                )
            } finally {
                keys.encryptionKey.fill(0)
                keys.hmacKey.fill(0)
            }
            configurations.save(configuration)
            configuration.clearSensitive()
            mutableState.update {
                it.copy(
                    configured = true,
                    channelSecret = "",
                    publishableKey = "",
                    deviceName = configuration.deviceName,
                    isBusy = false,
                )
            }
            synchronize()
        } catch (_: IllegalArgumentException) {
            mutableState.update { it.copy(isBusy = false, status = "Action required", error = "Configuration is invalid.") }
        } catch (_: java.security.GeneralSecurityException) {
            mutableState.update { it.copy(isBusy = false, status = "Retrying", error = "Secure storage is temporarily unavailable.") }
        }
    }

    fun synchronize() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            mutableState.update { it.copy(isBusy = true, status = "Connecting", error = null) }
            when (val result = coordinator.synchronize()) {
                SynchronizeResult.Unconfigured -> mutableState.update { it.copy(configured = false, isBusy = false, status = "Unconfigured") }
                is SynchronizeResult.Connected -> mutableState.update {
                    it.copy(
                        isBusy = false,
                        status = "Connected",
                        devices = result.devices,
                        lastUploaded = result.uploaded,
                        lastReceived = result.received,
                    )
                }
                is SynchronizeResult.Retrying -> mutableState.update { it.copy(isBusy = false, status = "Retrying", error = "Sync is temporarily unavailable.") }
                is SynchronizeResult.ActionableError -> mutableState.update { it.copy(isBusy = false, status = "Action required", error = result.safeMessage) }
            }
        }
    }
}
