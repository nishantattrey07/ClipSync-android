package com.nishantattrey.clipsync.local

import com.nishantattrey.clipsync.sync.SyncUiState

internal enum class ConnectionVisualState { HEALTHY, SYNCING, RETRYING, ACTION_REQUIRED, OFFLINE, UNCONFIGURED }

internal data class ConnectionPresentation(
    val state: ConnectionVisualState,
    val title: String,
    val detail: String,
)

internal fun connectionPresentation(state: SyncUiState, nowEpochMillis: Long): ConnectionPresentation {
    val deviceLabel = "${state.devices.size} ${if (state.devices.size == 1) "device" else "devices"}"
    return when {
        !state.configured -> ConnectionPresentation(ConnectionVisualState.UNCONFIGURED, "Cloud not configured", "Set up ClipSync to share clips")
        state.status == "Action required" -> ConnectionPresentation(ConnectionVisualState.ACTION_REQUIRED, "Sync needs attention", state.error ?: "Check connection settings")
        state.status == "Retrying" -> ConnectionPresentation(ConnectionVisualState.RETRYING, "Reconnecting", state.error ?: "ClipSync will try again")
        state.status == "Offline" -> ConnectionPresentation(ConnectionVisualState.OFFLINE, "Offline", "Saved clips remain available")
        state.isBusy -> ConnectionPresentation(ConnectionVisualState.SYNCING, "Syncing", "Checking $deviceLabel")
        else -> ConnectionPresentation(ConnectionVisualState.HEALTHY, deviceLabel, lastSyncLabel(state.lastSuccessfulSyncAtEpochMillis, nowEpochMillis))
    }
}

internal fun lastSyncLabel(lastSuccessEpochMillis: Long?, nowEpochMillis: Long): String {
    if (lastSuccessEpochMillis == null) return "Not synced yet"
    val elapsedSeconds = ((nowEpochMillis - lastSuccessEpochMillis).coerceAtLeast(0)) / 1_000
    return when {
        elapsedSeconds < 60 -> "Updated just now"
        elapsedSeconds < 3_600 -> "Updated ${elapsedSeconds / 60}m ago"
        elapsedSeconds < 86_400 -> "Updated ${elapsedSeconds / 3_600}h ago"
        else -> "Updated ${elapsedSeconds / 86_400}d ago"
    }
}

internal fun sectionHeading(section: HistorySection): String = when (section) {
    HistorySection.LOCAL -> "Local clips"
    HistorySection.SHARED -> "Shared clips"
    HistorySection.BOOKMARKS -> "Bookmarks"
}
