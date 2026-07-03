package com.nishantattrey.clipsync.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nishantattrey.clipsync.core.local.capture.ClipboardGateway
import com.nishantattrey.clipsync.core.local.capture.FocusedClipboardImportUseCase
import com.nishantattrey.clipsync.core.local.capture.TextCaptureUseCase
import com.nishantattrey.clipsync.core.local.model.CaptureSource
import com.nishantattrey.clipsync.core.local.model.EmptyCaptureException
import com.nishantattrey.clipsync.core.local.model.LocalClipboardItem
import com.nishantattrey.clipsync.core.local.model.LocalDataResult
import com.nishantattrey.clipsync.core.local.model.LocalRecoveryState
import com.nishantattrey.clipsync.core.local.model.LocalSettings
import com.nishantattrey.clipsync.core.local.model.OversizedCaptureException
import com.nishantattrey.clipsync.core.local.model.RetentionPeriod
import com.nishantattrey.clipsync.core.local.repository.LocalClipboardRepository
import com.nishantattrey.clipsync.core.local.repository.LocalRecoveryCoordinator
import com.nishantattrey.clipsync.core.local.settings.LocalSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocalClipboardUiState(
    val items: List<LocalClipboardItem> = emptyList(),
    val query: String = "",
    val composerText: String = "",
    val bookmarksOnly: Boolean = false,
    val settings: LocalSettings = LocalSettings(),
    val recovery: LocalRecoveryState = LocalRecoveryState.Ready,
    val message: String? = null,
)

@HiltViewModel
class LocalClipboardViewModel @Inject constructor(
    private val repository: LocalClipboardRepository,
    private val capture: TextCaptureUseCase,
    private val importClipboard: FocusedClipboardImportUseCase,
    private val clipboard: ClipboardGateway,
    private val settingsRepository: LocalSettingsRepository,
    private val recoveryCoordinator: LocalRecoveryCoordinator,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LocalClipboardUiState())
    val state: StateFlow<LocalClipboardUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collectLatest { settings ->
                mutableState.update { it.copy(settings = settings) }
                repository.applyRetention(settings.retentionPeriod)
            }
        }
        viewModelScope.launch {
            mutableState.update { it.copy(recovery = recoveryCoordinator.state()) }
            repository.changes.collectLatest { refresh() }
        }
    }

    fun setComposerText(value: String) = mutableState.update { it.copy(composerText = value) }
    fun setQuery(value: String) { mutableState.update { it.copy(query = value) }; refreshAsync() }
    fun setBookmarksOnly(value: Boolean) { mutableState.update { it.copy(bookmarksOnly = value) }; refreshAsync() }
    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    fun captureComposer() = viewModelScope.launch {
        try {
            handle(capture(state.value.composerText, CaptureSource.COMPOSER), "Saved")
            mutableState.update { it.copy(composerText = "") }
        } catch (_: EmptyCaptureException) {
            mutableState.update { it.copy(message = "Text cannot be empty") }
        } catch (_: OversizedCaptureException) {
            mutableState.update { it.copy(message = "Text exceeds the 5,000,000-byte limit") }
        }
    }

    fun importFocusedClipboard() = viewModelScope.launch {
        try {
            val result = importClipboard()
            if (result == null) mutableState.update { it.copy(message = "Clipboard import requires a focused app window") }
            else handle(result, "Imported")
        } catch (_: EmptyCaptureException) {
            mutableState.update { it.copy(message = "Clipboard text is empty") }
        } catch (_: OversizedCaptureException) {
            mutableState.update { it.copy(message = "Clipboard text exceeds the 5,000,000-byte limit") }
        }
    }

    fun toggleBookmark(item: LocalClipboardItem) = viewModelScope.launch {
        repository.setBookmarked(item.id, !item.isBookmarked)
    }

    fun copy(item: LocalClipboardItem) {
        clipboard.writeText(item.text, state.value.settings.markCopiedTextSensitive)
        mutableState.update { it.copy(message = "Copied") }
    }

    fun deleteConfirmed(id: String) = viewModelScope.launch { repository.delete(id) }
    fun clearUnbookmarkedConfirmed() = viewModelScope.launch { repository.clearUnbookmarked() }

    fun resetEncryptedHistoryConfirmed() = viewModelScope.launch {
        recoveryCoordinator.resetEncryptedHistory(confirmed = true)
        mutableState.update { it.copy(recovery = LocalRecoveryState.Ready, message = "Local history reset") }
    }

    fun setSensitiveCopy(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setMarkCopiedTextSensitive(enabled)
    }

    fun setRetention(period: RetentionPeriod) = viewModelScope.launch {
        settingsRepository.setRetentionPeriod(period)
        repository.applyRetention(period)
    }

    private fun refreshAsync() { viewModelScope.launch { refresh() } }
    private suspend fun refresh() {
        val snapshot = state.value
        val result = if (snapshot.query.isEmpty()) {
            repository.page(snapshot.bookmarksOnly)
        } else {
            repository.search(snapshot.query, snapshot.bookmarksOnly)
        }
        when (result) {
            is LocalDataResult.Success -> mutableState.update { it.copy(items = result.value) }
            is LocalDataResult.RecoveryRequired -> mutableState.update { it.copy(recovery = result.state) }
            is LocalDataResult.CorruptItem -> mutableState.update { it.copy(message = "A local item is corrupt") }
        }
    }

    private fun handle(result: LocalDataResult<*>, success: String) {
        when (result) {
            is LocalDataResult.Success -> mutableState.update { it.copy(message = success) }
            is LocalDataResult.RecoveryRequired -> mutableState.update { it.copy(recovery = result.state) }
            is LocalDataResult.CorruptItem -> mutableState.update { it.copy(message = "A local item is corrupt") }
        }
    }
}
