package com.nishantattrey.clipsync.core.local.capture

import com.nishantattrey.clipsync.core.local.model.CaptureResult
import com.nishantattrey.clipsync.core.local.model.CaptureSource
import com.nishantattrey.clipsync.core.local.model.LocalDataResult
import com.nishantattrey.clipsync.core.local.repository.LocalClipboardRepository

/**
 * Interface representing the platform clipboard.
 * Provides abstraction for reading from and writing to the Android system clipboard.
 */
interface ClipboardGateway {
    /**
     * Reads plain text from the clipboard.
     * @return The plain text string currently stored in the clipboard, or null if empty or non-text.
     */
    fun readText(): String?

    /**
     * Writes plain text to the clipboard.
     * @param text The text string to write.
     * @param sensitive Whether this clip should be marked as sensitive to prevent system UI leakage.
     */
    fun writeText(text: String, sensitive: Boolean)
}

fun interface ForegroundFocusState { fun isVisibleAndFocused(): Boolean }

class TextCaptureUseCase(private val repository: LocalClipboardRepository) {
    suspend operator fun invoke(text: String, source: CaptureSource): LocalDataResult<CaptureResult> =
        repository.capture(text, source)
}

class FocusedClipboardImportUseCase(
    private val clipboard: ClipboardGateway,
    private val focusState: ForegroundFocusState,
    private val capture: TextCaptureUseCase,
) {
    suspend operator fun invoke(): LocalDataResult<CaptureResult>? {
        if (!focusState.isVisibleAndFocused()) return null
        val text = clipboard.readText() ?: return null
        return capture(text, CaptureSource.FOCUSED_IMPORT)
    }
}
