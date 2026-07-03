package com.nishantattrey.clipsync.sync

import android.net.Uri
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nishantattrey.clipsync.core.local.persistence.LocalImageEntity
import com.nishantattrey.clipsync.core.local.repository.LocalStore
import com.nishantattrey.clipsync.core.sync.image.PreparedImageStore
import com.nishantattrey.clipsync.platform.AndroidImageContentService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImageCaptureUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val images: List<LocalImageEntity> = emptyList(),
    val previews: Map<String, Bitmap> = emptyMap(),
    val loadingPreviews: Set<String> = emptySet(),
)

@HiltViewModel
class ImageCaptureViewModel @Inject constructor(
    private val coordinator: ImageCaptureCoordinator,
    private val store: LocalStore,
    private val files: PreparedImageStore,
    private val content: AndroidImageContentService,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ImageCaptureUiState())
    val state: StateFlow<ImageCaptureUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            store.database.syncPersistenceDao().observeLocalImages().collect { images ->
                mutableState.update { it.copy(images = images) }
            }
        }
    }

    fun capture(uri: Uri, upload: Boolean = true) = viewModelScope.launch {
        if (state.value.busy) return@launch
        mutableState.update { it.copy(busy = true, message = null) }
        try {
            mutableState.update { it.copy(busy = false, message = coordinator.capture(uri, upload)) }
        } catch (_: Exception) {
            mutableState.update { it.copy(busy = false, message = "Image is unavailable, unsupported, or not configured") }
        }
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    fun loadPreview(image: LocalImageEntity) {
        if (state.value.previews.containsKey(image.itemId) || state.value.loadingPreviews.contains(image.itemId)) return
        viewModelScope.launch {
            mutableState.update { it.copy(loadingPreviews = it.loadingPreviews + image.itemId) }
            runCatching { content.preview(image) }
                .onSuccess { preview ->
                    mutableState.update { current ->
                        val retained = (current.previews + (image.itemId to preview)).entries.toList().takeLast(12)
                            .associate { it.key to it.value }
                        current.copy(previews = retained, loadingPreviews = current.loadingPreviews - image.itemId)
                    }
                }
                .onFailure {
                    mutableState.update { current ->
                        current.copy(
                            loadingPreviews = current.loadingPreviews - image.itemId,
                            message = "Image preview is unavailable",
                        )
                    }
                }
        }
    }

    fun copy(image: LocalImageEntity) = imageAction("Image copied") { content.copy(image) }

    fun share(image: LocalImageEntity) = imageAction(null) { content.share(image) }

    fun upload(image: LocalImageEntity) = viewModelScope.launch {
        mutableState.update { it.copy(busy = true, message = null) }
        runCatching { coordinator.shareExisting(image) }
            .onSuccess { message -> mutableState.update { it.copy(busy = false, message = message) } }
            .onFailure { mutableState.update { it.copy(busy = false, message = "Image could not be shared") } }
    }

    private fun imageAction(success: String?, action: suspend () -> Unit) = viewModelScope.launch {
        runCatching { action() }
            .onSuccess { if (success != null) mutableState.update { it.copy(message = success) } }
            .onFailure { mutableState.update { it.copy(message = "Image action could not be completed") } }
    }

    fun toggleBookmark(image: LocalImageEntity) = viewModelScope.launch {
        store.database.syncPersistenceDao().setLocalImageBookmarked(image.itemId, !image.isBookmarked)
    }

    fun delete(image: LocalImageEntity) = viewModelScope.launch {
        if (store.database.syncPersistenceDao().deleteLocalImage(image.itemId) == 1) {
            files.delete(image.encryptedFileName)
        }
    }
}
