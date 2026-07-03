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
import com.nishantattrey.clipsync.core.local.repository.LocalRecoveryManager
import com.nishantattrey.clipsync.core.local.settings.LocalSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val canLoadMore: Boolean = false,
    val isLoadingMore: Boolean = false,
)

@HiltViewModel
class LocalClipboardViewModel @Inject constructor(
    private val repository: LocalClipboardRepository,
    private val capture: TextCaptureUseCase,
    private val importClipboard: FocusedClipboardImportUseCase,
    private val clipboard: ClipboardGateway,
    private val settingsRepository: LocalSettingsRepository,
    private val recoveryCoordinator: LocalRecoveryManager,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LocalClipboardUiState())
    val state: StateFlow<LocalClipboardUiState> = mutableState.asStateFlow()
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collectLatest { settings ->
                mutableState.update { it.copy(settings = settings) }
                repository.applyRetention(settings.retentionPeriod)
            }
        }
        viewModelScope.launch {
            mutableState.update { it.copy(recovery = recoveryCoordinator.state()) }
            repository.changes.collectLatest { scheduleRefresh() }
        }
    }

    fun setComposerText(value: String) = mutableState.update { it.copy(composerText = value) }
    fun setQuery(value: String) { mutableState.update { it.copy(query = value) }; scheduleRefresh() }
    fun setBookmarksOnly(value: Boolean) { mutableState.update { it.copy(bookmarksOnly = value) }; scheduleRefresh() }
    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    fun captureComposer(onStored: ((String) -> Unit)? = null) = viewModelScope.launch {
        try {
            val result = capture(state.value.composerText, CaptureSource.COMPOSER)
            handle(result, "Saved")
            mutableState.update { it.copy(composerText = "") }
            captureId(result)?.let { id ->
                if (onStored != null && repository.queueForUpload(id)) onStored(id)
            }
        } catch (_: EmptyCaptureException) {
            mutableState.update { it.copy(message = "Text cannot be empty") }
        } catch (_: OversizedCaptureException) {
            mutableState.update { it.copy(message = "Text exceeds the 5,000,000-byte limit") }
        }
    }

    fun importFocusedClipboard(onStored: ((String) -> Unit)? = null) = viewModelScope.launch {
        try {
            val result = importClipboard()
            if (result == null) mutableState.update { it.copy(message = "Clipboard import requires a focused app window") }
            else {
                handle(result, "Imported")
                captureId(result)?.let { id ->
                    if (onStored != null && repository.queueForUpload(id)) onStored(id)
                }
            }
        } catch (_: EmptyCaptureException) {
            mutableState.update { it.copy(message = "Clipboard text is empty") }
        } catch (_: OversizedCaptureException) {
            mutableState.update { it.copy(message = "Clipboard text exceeds the 5,000,000-byte limit") }
        }
    }

    fun toggleBookmark(item: LocalClipboardItem) = viewModelScope.launch {
        repository.setBookmarked(item.id, !item.isBookmarked)
    }

    fun upload(item: LocalClipboardItem, synchronize: () -> Unit) = viewModelScope.launch {
        if (repository.queueForUpload(item.id)) synchronize()
    }

    fun copy(item: LocalClipboardItem) {
        clipboard.writeText(item.text, state.value.settings.markCopiedTextSensitive)
        mutableState.update { it.copy(message = "Copied") }
    }

    fun deleteConfirmed(id: String) = viewModelScope.launch { repository.delete(id) }
    fun clearUnbookmarkedConfirmed() = viewModelScope.launch { repository.clearUnbookmarked() }

    fun resetEncryptedHistoryConfirmed() = viewModelScope.launch {
        try {
            val recovery = recoveryCoordinator.resetEncryptedHistory(confirmed = true)
            mutableState.update {
                it.copy(
                    recovery = recovery,
                    message = if (recovery == LocalRecoveryState.Ready) "Local history reset" else null,
                )
            }
        } catch (_: IllegalArgumentException) {
            mutableState.update { it.copy(recovery = recoveryCoordinator.state()) }
        }
    }

    fun retryRecovery() = viewModelScope.launch {
        val recovery = recoveryCoordinator.state()
        mutableState.update { it.copy(recovery = recovery) }
        if (recovery == LocalRecoveryState.Ready) scheduleRefresh()
    }

    fun setSensitiveCopy(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setMarkCopiedTextSensitive(enabled)
    }

    fun setRetention(period: RetentionPeriod) = viewModelScope.launch {
        settingsRepository.setRetentionPeriod(period)
        repository.applyRetention(period)
    }

    fun loadMore() {
        val snapshot = state.value
        if (snapshot.query.isNotEmpty() || !snapshot.canLoadMore || snapshot.isLoadingMore) return
        val before = snapshot.items.lastOrNull() ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            mutableState.update { it.copy(isLoadingMore = true) }
            val result = repository.page(snapshot.bookmarksOnly, before)
            if (state.value.query != snapshot.query || state.value.bookmarksOnly != snapshot.bookmarksOnly) return@launch
            when (result) {
                is LocalDataResult.Success -> mutableState.update {
                    it.copy(
                        items = it.items + result.value,
                        canLoadMore = result.value.size == com.nishantattrey.clipsync.core.local.repository.MAX_PAGE_SIZE,
                        isLoadingMore = false,
                    )
                }
                is LocalDataResult.RecoveryRequired -> mutableState.update {
                    it.copy(recovery = result.state, isLoadingMore = false)
                }
                is LocalDataResult.CorruptItem -> mutableState.update {
                    it.copy(message = "A local item is corrupt", isLoadingMore = false)
                }
            }
        }
    }

    private fun scheduleRefresh() {
        refreshJob?.cancel()
        val snapshot = state.value
        refreshJob = viewModelScope.launch {
            if (snapshot.query.isNotEmpty()) delay(150)
            val result = if (snapshot.query.isEmpty()) {
                repository.page(snapshot.bookmarksOnly)
            } else {
                repository.search(snapshot.query, snapshot.bookmarksOnly)
            }
            if (state.value.query != snapshot.query || state.value.bookmarksOnly != snapshot.bookmarksOnly) return@launch
            when (result) {
                is LocalDataResult.Success -> mutableState.update {
                    it.copy(
                        items = result.value,
                        canLoadMore = snapshot.query.isEmpty() &&
                            result.value.size == com.nishantattrey.clipsync.core.local.repository.MAX_PAGE_SIZE,
                        isLoadingMore = false,
                    )
                }
                is LocalDataResult.RecoveryRequired -> mutableState.update { it.copy(recovery = result.state) }
                is LocalDataResult.CorruptItem -> mutableState.update { it.copy(message = "A local item is corrupt") }
            }
        }
    }

    private fun handle(result: LocalDataResult<*>, success: String) {
        when (result) {
            is LocalDataResult.Success -> mutableState.update { it.copy(message = success) }
            is LocalDataResult.RecoveryRequired -> mutableState.update { it.copy(recovery = result.state) }
            is LocalDataResult.CorruptItem -> mutableState.update { it.copy(message = "A local item is corrupt") }
        }
    }

    private fun captureId(result: LocalDataResult<*>): String? {
        val capture = (result as? LocalDataResult.Success)?.value as? com.nishantattrey.clipsync.core.local.model.CaptureResult
            ?: return null
        return when (capture) {
            is com.nishantattrey.clipsync.core.local.model.CaptureResult.Stored -> capture.id
            is com.nishantattrey.clipsync.core.local.model.CaptureResult.Duplicate -> capture.id
        }
    }
}
