package com.nishantattrey.clipsync.local

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nishantattrey.clipsync.core.local.model.CaptureSource
import com.nishantattrey.clipsync.core.local.model.LocalClipboardItem
import com.nishantattrey.clipsync.core.local.model.LocalRecoveryState
import com.nishantattrey.clipsync.core.local.model.RetentionPeriod
import com.nishantattrey.clipsync.core.local.persistence.LocalImageEntity
import com.nishantattrey.clipsync.sync.ImageCaptureViewModel
import com.nishantattrey.clipsync.sync.SyncUiState
import com.nishantattrey.clipsync.sync.SyncViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalUtilityScreen(
    viewModel: LocalClipboardViewModel,
    syncViewModel: SyncViewModel,
    imageViewModel: ImageCaptureViewModel,
    openShared: Boolean = true,
    pickImage: (Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val syncState by syncViewModel.state.collectAsStateWithLifecycle()
    val imageState by imageViewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var section by remember { mutableStateOf(if (openShared) HistorySection.SHARED else HistorySection.LOCAL) }
    var showComposer by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var pendingTextDelete by remember { mutableStateOf<String?>(null) }
    var pendingImageDelete by remember { mutableStateOf<LocalImageEntity?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmRecoveryReset by remember { mutableStateOf(false) }
    var viewedImage by remember { mutableStateOf<Pair<String, Bitmap>?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }
    LaunchedEffect(imageState.message) {
        imageState.message?.let { snackbar.showSnackbar(it); imageViewModel.dismissMessage() }
    }
    LaunchedEffect(openShared) {
        section = if (openShared) HistorySection.SHARED else HistorySection.LOCAL
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("ClipSync", fontWeight = FontWeight.SemiBold) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SyncBar(syncState, syncViewModel::synchronize) { showOptions = true }

            when (state.recovery) {
                is LocalRecoveryState.TemporarilyUnavailable -> RecoveryPanel(
                    "Local encryption is temporarily unavailable.", "Retry", viewModel::retryRecovery,
                )
                is LocalRecoveryState.MissingKeys, is LocalRecoveryState.InvalidatedKeys -> RecoveryPanel(
                    "Local encryption recovery is required.", "Reset encrypted history",
                ) { confirmRecoveryReset = true }
                LocalRecoveryState.Ready -> {
                    SectionSelector(section) {
                        section = it
                        showComposer = false
                    }

                    if (!syncState.configured) {
                        ConnectionSetup(syncState, syncViewModel)
                    } else {
                        if (section != HistorySection.BOOKMARKS) {
                            CaptureActions(
                                shared = section == HistorySection.SHARED,
                                imageBusy = imageState.busy,
                                onText = { showComposer = !showComposer },
                                onImport = {
                                    if (section == HistorySection.SHARED) {
                                        viewModel.importFocusedClipboard { syncViewModel.synchronize() }
                                    } else viewModel.importFocusedClipboard()
                                },
                                onImage = { pickImage(section == HistorySection.SHARED) },
                            )
                        }
                        if (showComposer) {
                            TextComposer(
                                text = state.composerText,
                                shared = section == HistorySection.SHARED,
                                onText = viewModel::setComposerText,
                                onDismiss = { showComposer = false },
                                onSubmit = {
                                    if (section == HistorySection.SHARED) {
                                        viewModel.captureComposer { syncViewModel.synchronize() }
                                    } else viewModel.captureComposer()
                                    showComposer = false
                                },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search clips") },
                        singleLine = true,
                    )

                    val visibleText = state.items.filter { item -> section.includes(item) }
                    val visibleImages = imageState.images.filter { image ->
                        section.includes(image) && (
                            state.query.isBlank() || image.displayName.orEmpty().contains(state.query, ignoreCase = true)
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${section.title} · ${visibleText.size + visibleImages.size}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    ClipHistory(
                        modifier = Modifier.weight(1f),
                        section = section,
                        textItems = visibleText,
                        images = visibleImages,
                        previews = imageState.previews,
                        loadingPreviews = imageState.loadingPreviews,
                        deviceNameFor = { deviceId ->
                            syncState.devices.firstOrNull { it.deviceId == deviceId }?.name
                        },
                        canLoadMore = state.canLoadMore,
                        isLoadingMore = state.isLoadingMore,
                        onLoadMore = viewModel::loadMore,
                        onLoadPreview = imageViewModel::loadPreview,
                        onViewImage = { image ->
                            imageState.previews[image.itemId]?.let { viewedImage = (image.displayName ?: "Image") to it }
                        },
                        onCopyImage = imageViewModel::copy,
                        onShareImage = imageViewModel::share,
                        onUploadImage = imageViewModel::upload,
                        onRetryImage = { syncViewModel.synchronize() },
                        onBookmarkImage = imageViewModel::toggleBookmark,
                        onDeleteImage = { pendingImageDelete = it },
                        onCopyText = viewModel::copy,
                        onUploadText = { viewModel.upload(it, syncViewModel::synchronize) },
                        onBookmarkText = viewModel::toggleBookmark,
                        onDeleteText = { pendingTextDelete = it.id },
                    )
                }
            }
        }
    }

    if (showOptions) {
        OptionsSheet(
            syncState = syncState,
            sensitiveCopy = state.settings.markCopiedTextSensitive,
            retention = state.settings.retentionPeriod,
            onSensitiveCopy = viewModel::setSensitiveCopy,
            onRetention = viewModel::setRetention,
            onClear = {
                showOptions = false
                confirmClearHistory = true
            },
            onDismiss = { showOptions = false },
        )
    }
    viewedImage?.let { (name, bitmap) -> ImageViewer(name, bitmap) { viewedImage = null } }
    pendingTextDelete?.let { id ->
        ConfirmDialog("Delete this text clip?", { viewModel.deleteConfirmed(id); pendingTextDelete = null }) {
            pendingTextDelete = null
        }
    }
    pendingImageDelete?.let { image ->
        ConfirmDialog("Delete this image?", { imageViewModel.delete(image); pendingImageDelete = null }) {
            pendingImageDelete = null
        }
    }
    if (confirmClearHistory) {
        ConfirmDialog(
            "Clear every unbookmarked local and shared clip from this device?",
            { viewModel.clearUnbookmarkedConfirmed(); confirmClearHistory = false },
        ) { confirmClearHistory = false }
    }
    if (confirmRecoveryReset) {
        ConfirmDialog(
            "Permanently reset encrypted local history and bookmarks?",
            { viewModel.resetEncryptedHistoryConfirmed(); confirmRecoveryReset = false },
        ) { confirmRecoveryReset = false }
    }
}

@Composable
private fun SyncBar(state: SyncUiState, onSync: () -> Unit, onOptions: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (state.status == "Connected") MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.status, fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.configured) "${state.deviceName} · ${state.devices.size} devices" else "Cloud is not configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.configured) {
                TextButton(onClick = onSync, enabled = !state.isBusy) {
                    Text(if (state.isBusy) "Refreshing" else "Refresh")
                }
            }
            TextButton(onClick = onOptions) { Text("Options") }
        }
    }
}

@Composable
private fun ConnectionSetup(state: SyncUiState, viewModel: SyncViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connect to ClipSync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(state.supabaseUrl, viewModel::updateUrl, Modifier.fillMaxWidth(), label = { Text("Supabase URL") }, singleLine = true)
        OutlinedTextField(state.publishableKey, viewModel::updateKey, Modifier.fillMaxWidth(), label = { Text("Public anonymous key") }, singleLine = true)
        OutlinedTextField(
            state.channelSecret, viewModel::updateSecret, Modifier.fillMaxWidth(),
            label = { Text("Channel secret") }, visualTransformation = PasswordVisualTransformation(), singleLine = true,
        )
        OutlinedTextField(state.deviceName, viewModel::updateDeviceName, Modifier.fillMaxWidth(), label = { Text("Device name") }, singleLine = true)
        Button(onClick = viewModel::saveConfiguration, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
            ) { Text(section.title) }
        }
    }
}

@Composable
private fun CaptureActions(
    shared: Boolean,
    imageBusy: Boolean,
    onText: () -> Unit,
    onImport: () -> Unit,
    onImage: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onText, modifier = Modifier.weight(1f)) { Text("Text") }
        FilledTonalButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("Import") }
        FilledTonalButton(onClick = onImage, enabled = !imageBusy, modifier = Modifier.weight(1f)) {
            Text(if (shared) "Image" else "Photo")
        }
    }
}

@Composable
private fun TextComposer(
    text: String,
    shared: Boolean,
    onText: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (shared) "Send text" else "Save text locally", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = text, onValueChange = onText, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Type or paste text") }, minLines = 3, maxLines = 6,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = onSubmit, enabled = text.isNotEmpty()) { Text(if (shared) "Send" else "Save") }
            }
        }
    }
}

@Composable
private fun ClipHistory(
    modifier: Modifier = Modifier,
    section: HistorySection,
    textItems: List<LocalClipboardItem>,
    images: List<LocalImageEntity>,
    previews: Map<String, Bitmap>,
    loadingPreviews: Set<String>,
    deviceNameFor: (String) -> String?,
    canLoadMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onLoadPreview: (LocalImageEntity) -> Unit,
    onViewImage: (LocalImageEntity) -> Unit,
    onCopyImage: (LocalImageEntity) -> Unit,
    onShareImage: (LocalImageEntity) -> Unit,
    onUploadImage: (LocalImageEntity) -> Unit,
    onRetryImage: () -> Unit,
    onBookmarkImage: (LocalImageEntity) -> Unit,
    onDeleteImage: (LocalImageEntity) -> Unit,
    onCopyText: (LocalClipboardItem) -> Unit,
    onUploadText: (LocalClipboardItem) -> Unit,
    onBookmarkText: (LocalClipboardItem) -> Unit,
    onDeleteText: (LocalClipboardItem) -> Unit,
) {
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(images, key = { "image-${it.itemId}" }) { image ->
            ImageClip(
                image = image,
                preview = previews[image.itemId],
                loading = image.itemId in loadingPreviews,
                sourceLabel = imageSourceLabel(image, deviceNameFor),
                loadPreview = { onLoadPreview(image) },
                onView = { onViewImage(image) },
                onCopy = { onCopyImage(image) },
                onShare = { onShareImage(image) },
                onUpload = if (image.cloudSyncState == "local") ({ onUploadImage(image) }) else null,
                onRetry = if (image.cloudSyncState in setOf("queued", "retrying")) onRetryImage else null,
                onBookmark = { onBookmarkImage(image) },
                onDelete = { onDeleteImage(image) },
            )
        }
        items(textItems, key = { "text-${it.id}" }) { item ->
            TextClip(
                item = item,
                onCopy = { onCopyText(item) },
                onUpload = if (item.cloudSyncState == "local") ({ onUploadText(item) }) else null,
                onBookmark = { onBookmarkText(item) },
                onDelete = { onDeleteText(item) },
            )
        }
        if (images.isEmpty() && textItems.isEmpty()) {
            item {
                Text(
                    when (section) {
                        HistorySection.SHARED -> "Nothing shared yet. Use Text, Import, or Image above."
                        HistorySection.LOCAL -> "No local-only clips."
                        HistorySection.BOOKMARKS -> "No bookmarked clips."
                    },
                    modifier = Modifier.padding(vertical = 28.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (canLoadMore) {
            item {
                OutlinedButton(onClick = onLoadMore, enabled = !isLoadingMore, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isLoadingMore) "Loading" else "Load more")
                }
            }
        }
    }
}

@Composable
private fun ImageClip(
    image: LocalImageEntity,
    preview: Bitmap?,
    loading: Boolean,
    sourceLabel: String,
    loadPreview: () -> Unit,
    onView: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onUpload: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onBookmark: () -> Unit,
    onDelete: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    LaunchedEffect(image.itemId, image.encryptedFileName) { loadPreview() }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            when {
                preview != null -> Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = image.displayName ?: "Image preview",
                    modifier = Modifier.fillMaxWidth().height(210.dp).clickable(onClick = onView),
                    contentScale = ContentScale.Fit,
                )
                loading -> Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("Preview unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(image.displayName ?: "Image", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${image.width} × ${image.height} · ${image.mimeType.substringAfter('/').uppercase()} · $sourceLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCopy) { Text("Copy") }
                    TextButton(onClick = onShare) { Text("Share") }
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { more = true }) { Text("More") }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            onUpload?.let { DropdownMenuItem(text = { Text("Upload") }, onClick = { more = false; it() }) }
                            onRetry?.let { DropdownMenuItem(text = { Text("Retry upload") }, onClick = { more = false; it() }) }
                            DropdownMenuItem(text = { Text(if (image.isBookmarked) "Remove bookmark" else "Bookmark") }, onClick = { more = false; onBookmark() })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { more = false; onDelete() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextClip(
    item: LocalClipboardItem,
    onCopy: () -> Unit,
    onUpload: (() -> Unit)?,
    onBookmark: () -> Unit,
    onDelete: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(textStateLabel(item), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(relativeTime(item.createdAtEpochMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(item.text, maxLines = 7, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCopy) { Text("Copy") }
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { more = true }) { Text("More") }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        onUpload?.let { DropdownMenuItem(text = { Text("Upload") }, onClick = { more = false; it() }) }
                        DropdownMenuItem(text = { Text(if (item.isBookmarked) "Remove bookmark" else "Bookmark") }, onClick = { more = false; onBookmark() })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { more = false; onDelete() })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSheet(
    syncState: SyncUiState,
    sensitiveCopy: Boolean,
    retention: RetentionPeriod,
    onSensitiveCopy: (Boolean) -> Unit,
    onRetention: (RetentionPeriod) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Options", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sensitive clipboard copy")
                    Text("Hide previews from system clipboard UI", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = sensitiveCopy, onCheckedChange = onSensitiveCopy)
            }
            HorizontalDivider()
            Text("Local retention", fontWeight = FontWeight.SemiBold)
            RetentionPeriod.entries.forEach { period ->
                Row(
                    Modifier.fillMaxWidth().clickable { onRetention(period) }.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = retention == period, onClick = { onRetention(period) })
                    Text(period.displayName)
                }
            }
            HorizontalDivider()
            Text("Connected devices", fontWeight = FontWeight.SemiBold)
            if (syncState.devices.isEmpty()) Text("No device profiles loaded", color = MaterialTheme.colorScheme.onSurfaceVariant)
            syncState.devices.forEach { Text("${it.name} · ${it.platform}") }
            OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("Clear unbookmarked local history") }
        }
    }
}

@Composable
private fun ImageViewer(name: String, bitmap: Bitmap, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(
                    bitmap = bitmap.asImageBitmap(), contentDescription = name,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp), contentScale = ContentScale.Fit,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun RecoveryPanel(message: String, action: String, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            Button(onClick = onClick) { Text(action) }
        }
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

private enum class HistorySection(val title: String) {
    LOCAL("Local"), SHARED("Shared"), BOOKMARKS("Bookmarks");

    fun includes(item: LocalClipboardItem): Boolean = when (this) {
        LOCAL -> item.captureSource != CaptureSource.CLOUD
        SHARED -> item.cloudSyncState != "local"
        BOOKMARKS -> item.isBookmarked
    }

    fun includes(image: LocalImageEntity): Boolean = when (this) {
        LOCAL -> image.captureSource != "CLOUD"
        SHARED -> image.cloudSyncState != "local"
        BOOKMARKS -> image.isBookmarked
    }
}

private val RetentionPeriod.displayName: String
    get() = when (this) {
        RetentionPeriod.NEVER -> "Never"
        RetentionPeriod.ONE_HOUR -> "1 hour"
        RetentionPeriod.SIX_HOURS -> "6 hours"
        RetentionPeriod.ONE_DAY -> "1 day"
        RetentionPeriod.SEVEN_DAYS -> "7 days"
        RetentionPeriod.THIRTY_DAYS -> "30 days"
    }

private fun imageStateLabel(state: String): String = when (state) {
    "local" -> "Local only"
    "queued", "prepared" -> "Waiting to upload"
    "retrying", "retry" -> "Upload retrying"
    "received" -> "From cloud"
    "synced" -> "Shared"
    "failed" -> "Upload failed"
    else -> state.replace('_', ' ')
}

private fun imageSourceLabel(image: LocalImageEntity, deviceNameFor: (String) -> String?): String {
    if (image.cloudSyncState != "received") return imageStateLabel(image.cloudSyncState)
    val sender = image.senderDeviceId?.let(deviceNameFor)
    return if (sender.isNullOrBlank()) "From another device" else "From $sender"
}

private fun textStateLabel(item: LocalClipboardItem): String = when {
    item.captureSource == CaptureSource.CLOUD -> "From cloud"
    item.cloudSyncState == "synced" -> "Shared from this device"
    item.cloudSyncState == "queued" -> "Waiting to upload"
    item.cloudSyncState == "failed" -> "Upload needs attention"
    else -> "Local only"
}

private fun relativeTime(epochMillis: Long): String = DateUtils.getRelativeTimeSpanString(
    epochMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
).toString()
