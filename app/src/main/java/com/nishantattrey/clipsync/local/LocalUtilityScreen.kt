package com.nishantattrey.clipsync.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nishantattrey.clipsync.core.local.model.LocalClipboardItem
import com.nishantattrey.clipsync.core.local.model.LocalRecoveryState
import com.nishantattrey.clipsync.core.local.model.RetentionPeriod

@Composable
fun LocalUtilityScreen(viewModel: LocalClipboardViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRecoveryReset by remember { mutableStateOf(false) }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.recovery) {
                is LocalRecoveryState.TemporarilyUnavailable -> {
                    Text("Local encryption is temporarily unavailable")
                    Button(onClick = viewModel::retryRecovery) { Text("Retry") }
                }
                is LocalRecoveryState.MissingKeys, is LocalRecoveryState.InvalidatedKeys -> {
                    Text("Local encryption recovery is required")
                    Button(onClick = { confirmRecoveryReset = true }) { Text("Reset encrypted history") }
                }
                LocalRecoveryState.Ready -> {
                    OutlinedTextField(state.composerText, viewModel::setComposerText, Modifier.fillMaxWidth(), label = { Text("Text") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::captureComposer) { Text("Save") }
                        Button(onClick = viewModel::importFocusedClipboard) { Text("Import clipboard") }
                        TextButton(onClick = { confirmClear = true }) { Text("Clear history") }
                    }
                    OutlinedTextField(state.query, viewModel::setQuery, Modifier.fillMaxWidth(), label = { Text("Search locally") })
                    Row {
                        Checkbox(state.bookmarksOnly, viewModel::setBookmarksOnly)
                        Text("Bookmarks only", Modifier.padding(top = 12.dp))
                        Checkbox(state.settings.markCopiedTextSensitive, viewModel::setSensitiveCopy)
                        Text("Sensitive copy", Modifier.padding(top = 12.dp))
                    }
                    TextButton(onClick = {
                        val values = RetentionPeriod.entries
                        viewModel.setRetention(values[(state.settings.retentionPeriod.ordinal + 1) % values.size])
                    }) { Text("Retention: ${state.settings.retentionPeriod.name}") }
                    LazyColumn(Modifier.weight(1f)) {
                        items(state.items, key = LocalClipboardItem::id) { item ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(item.text)
                                Row {
                                    TextButton(onClick = { viewModel.copy(item) }) { Text("Copy") }
                                    TextButton(onClick = { viewModel.toggleBookmark(item) }) { Text(if (item.isBookmarked) "Unbookmark" else "Bookmark") }
                                    TextButton(onClick = { pendingDelete = item.id }) { Text("Delete") }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    if (state.canLoadMore) {
                        Button(onClick = viewModel::loadMore, enabled = !state.isLoadingMore) {
                            Text(if (state.isLoadingMore) "Loading…" else "Load more")
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
private fun ConfirmDialog(text: String, confirm: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = confirm) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}
