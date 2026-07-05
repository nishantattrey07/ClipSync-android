package com.nishantattrey.clipsync.core.local.capture

import com.nishantattrey.clipsync.core.local.model.CaptureResult
import com.nishantattrey.clipsync.core.local.model.CaptureSource
import com.nishantattrey.clipsync.core.local.model.LocalDataResult
import com.nishantattrey.clipsync.core.local.model.AppNotFocusedException
import com.nishantattrey.clipsync.core.local.model.EmptyClipboardException
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
    /**
     * Imports the current plain text from the clipboard if the app is focused.
     * @throws AppNotFocusedException If the app does not have a visible and focused window.
     * @throws EmptyClipboardException If the clipboard is empty or does not contain plain text.
     * @return LocalDataResult containing the CaptureResult.
     */
    suspend operator fun invoke(): LocalDataResult<CaptureResult> {
        if (!focusState.isVisibleAndFocused()) throw AppNotFocusedException()
        val text = clipboard.readText() ?: throw EmptyClipboardException()
        return capture(text, CaptureSource.FOCUSED_IMPORT)
    }
}
