package com.nishantattrey.clipsync.local

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nishantattrey.clipsync.core.local.model.CaptureSource
import com.nishantattrey.clipsync.core.local.model.LocalClipboardItem
import com.nishantattrey.clipsync.core.local.model.LocalRecoveryState
import com.nishantattrey.clipsync.core.local.model.RetentionPeriod
import com.nishantattrey.clipsync.sync.SyncViewModel

@Composable
fun LocalUtilityScreen(viewModel: LocalClipboardViewModel, syncViewModel: SyncViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRecoveryReset by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(HistorySection.SHARED) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ClipSync", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            SyncPanel(syncViewModel)

            when (state.recovery) {
                is LocalRecoveryState.TemporarilyUnavailable -> RecoveryPanel(
                    "Local encryption is temporarily unavailable.",
                    "Retry",
                    viewModel::retryRecovery,
                )
                is LocalRecoveryState.MissingKeys, is LocalRecoveryState.InvalidatedKeys -> RecoveryPanel(
                    "Local encryption recovery is required.",
                    "Reset encrypted history",
                    { confirmRecoveryReset = true },
                )
                LocalRecoveryState.Ready -> {
                    SectionSelector(section) { section = it }
                    CapturePanel(state.composerText, section, viewModel, syncViewModel)
                    val visibleItems = state.items.filter { item ->
                        when (section) {
                            HistorySection.SHARED -> item.cloudSyncState != "local"
                            HistorySection.LOCAL -> item.cloudSyncState == "local"
                        }
                    }
                    HistoryControls(
                        query = state.query,
                        bookmarksOnly = state.bookmarksOnly,
                        sensitiveCopy = state.settings.markCopiedTextSensitive,
                        retention = state.settings.retentionPeriod,
                        itemCount = visibleItems.size,
                        onQuery = viewModel::setQuery,
                        onBookmarksOnly = viewModel::setBookmarksOnly,
                        onSensitiveCopy = viewModel::setSensitiveCopy,
                        onRetention = viewModel::setRetention,
                        onClear = { confirmClear = true },
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visibleItems, key = LocalClipboardItem::id) { item ->
                            HistoryItem(
                                item = item,
                                onCopy = { viewModel.copy(item) },
                                onBookmark = { viewModel.toggleBookmark(item) },
                                onDelete = { pendingDelete = item.id },
                            )
                        }
                        if (visibleItems.isEmpty()) {
                            item {
                                Text(
                                    if (section == HistorySection.SHARED) {
                                        "No shared clips yet. Send text above or sync another device."
                                    } else {
                                        "No local-only clips."
                                    },
                                    modifier = Modifier.padding(vertical = 24.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (state.canLoadMore) {
                            item {
                                FilledTonalButton(
                                    onClick = viewModel::loadMore,
                                    enabled = !state.isLoadingMore,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(if (state.isLoadingMore) "Loading" else "Load more")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { id ->
        ConfirmDialog("Delete this item?", { viewModel.deleteConfirmed(id); pendingDelete = null }, { pendingDelete = null })
    }
    if (confirmClear) {
        ConfirmDialog("Clear all unbookmarked history?", { viewModel.clearUnbookmarkedConfirmed(); confirmClear = false }, { confirmClear = false })
    }
    if (confirmRecoveryReset) {
        ConfirmDialog(
            "Permanently reset encrypted local history and bookmarks?",
            { viewModel.resetEncryptedHistoryConfirmed(); confirmRecoveryReset = false },
            { confirmRecoveryReset = false },
        )
    }
}

@Composable
private fun SyncPanel(viewModel: SyncViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Cloud ${state.status.lowercase()}", fontWeight = FontWeight.SemiBold)
                    if (state.configured) {
                        Text(
                            state.deviceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                if (state.configured) {
                    FilledTonalButton(onClick = viewModel::synchronize, enabled = !state.isBusy) {
                        if (state.isBusy) {
                            CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                        }
                        Text("Sync now")
                    }
                }
            }

            if (!state.configured) {
                OutlinedTextField(state.supabaseUrl, viewModel::updateUrl, Modifier.fillMaxWidth(), label = { Text("Supabase URL") }, singleLine = true)
                OutlinedTextField(state.publishableKey, viewModel::updateKey, Modifier.fillMaxWidth(), label = { Text("Public anonymous key") }, singleLine = true)
                OutlinedTextField(
                    state.channelSecret,
                    viewModel::updateSecret,
                    Modifier.fillMaxWidth(),
                    label = { Text("Channel secret") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(state.deviceName, viewModel::updateDeviceName, Modifier.fillMaxWidth(), label = { Text("Device name") }, singleLine = true)
                Button(onClick = viewModel::saveConfiguration, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
            } else {
                val names = state.devices.joinToString { "${it.name} (${it.platform})" }
                Text(
                    if (names.isEmpty()) "No device profiles loaded" else "Devices: $names",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.lastUploaded > 0 || state.lastReceived > 0) {
                    Text(
                        "Last sync: ${state.lastUploaded} uploaded, ${state.lastReceived} received",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun CapturePanel(
    text: String,
    section: HistorySection,
    viewModel: LocalClipboardViewModel,
    syncViewModel: SyncViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (section == HistorySection.SHARED) "Send text" else "Save locally",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = text,
            onValueChange = viewModel::setComposerText,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Type or paste text") },
            minLines = 2,
            maxLines = 4,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (section == HistorySection.SHARED) viewModel.captureComposer(syncViewModel::synchronize)
                    else viewModel.captureComposer()
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (section == HistorySection.SHARED) "Send to devices" else "Save locally") }
            FilledTonalButton(
                onClick = {
                    if (section == HistorySection.SHARED) viewModel.importFocusedClipboard(syncViewModel::synchronize)
                    else viewModel.importFocusedClipboard()
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (section == HistorySection.SHARED) "Import & send" else "Import locally") }
        }
    }
}

@Composable
private fun SectionSelector(selected: HistorySection, onSelected: (HistorySection) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        HistorySection.entries.forEachIndexed { index, section ->
            SegmentedButton(
                selected = selected == section,
                onClick = { onSelected(section) },
                shape = SegmentedButtonDefaults.itemShape(index, HistorySection.entries.size),
            ) {
                Text(if (section == HistorySection.SHARED) "Shared" else "Local")
            }
        }
    }
}

@Composable
private fun HistoryControls(
    query: String,
    bookmarksOnly: Boolean,
    sensitiveCopy: Boolean,
    retention: RetentionPeriod,
    itemCount: Int,
    onQuery: (String) -> Unit,
    onBookmarksOnly: (Boolean) -> Unit,
    onSensitiveCopy: (Boolean) -> Unit,
    onRetention: (RetentionPeriod) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("History ($itemCount)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onClear) { Text("Clear") }
        }
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), placeholder = { Text("Search history") }, singleLine = true)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(bookmarksOnly, onBookmarksOnly)
            Text("Bookmarks", style = MaterialTheme.typography.bodyMedium)
            Checkbox(sensitiveCopy, onSensitiveCopy)
            Text("Sensitive copy", style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = {
            val values = RetentionPeriod.entries
            onRetention(values[(retention.ordinal + 1) % values.size])
        }) { Text("Retention: ${retention.name.replace('_', ' ').lowercase()}") }
        HorizontalDivider()
    }
}

@Composable
private fun HistoryItem(item: LocalClipboardItem, onCopy: () -> Unit, onBookmark: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    when {
                        item.captureSource == CaptureSource.CLOUD -> "From cloud"
                        item.cloudSyncState == "synced" -> "Shared from this device"
                        item.cloudSyncState == "queued" -> "Waiting to upload"
                        item.cloudSyncState == "failed" -> "Upload needs attention"
                        else -> "Local only"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(relativeTime(item.createdAtEpochMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(item.text, maxLines = 6, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCopy) { Text("Copy") }
                TextButton(onClick = onBookmark) { Text(if (item.isBookmarked) "Unbookmark" else "Bookmark") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

private enum class HistorySection { SHARED, LOCAL }

@Composable
private fun RecoveryPanel(message: String, action: String, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            Button(onClick = onClick) { Text(action) }
        }
    }
}

private fun relativeTime(epochMillis: Long): String = DateUtils.getRelativeTimeSpanString(
    epochMillis,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
).toString()

@Composable
private fun ConfirmDialog(text: String, confirm: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = confirm) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}
