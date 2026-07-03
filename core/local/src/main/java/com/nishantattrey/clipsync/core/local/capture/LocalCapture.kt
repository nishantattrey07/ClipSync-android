package com.nishantattrey.clipsync.core.local.capture

import com.nishantattrey.clipsync.core.local.model.CaptureResult
import com.nishantattrey.clipsync.core.local.model.CaptureSource
import com.nishantattrey.clipsync.core.local.model.LocalDataResult
import com.nishantattrey.clipsync.core.local.repository.LocalClipboardRepository

interface ClipboardGateway {
    fun readText(): String?
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
