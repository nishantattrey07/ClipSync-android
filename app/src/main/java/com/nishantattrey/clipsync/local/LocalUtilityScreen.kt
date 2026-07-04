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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nishantattrey.clipsync.R
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
    var showNewClip by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
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
        topBar = {
            TopAppBar(
                title = { Text(if (syncState.configured) section.title else "ClipSync") },
                actions = {
                    if (syncState.configured) {
                        IconButton(onClick = { searchExpanded = !searchExpanded }) {
                            Icon(Icons.Default.Search, contentDescription = if (searchExpanded) "Close search" else "Search clips")
                        }
                    }
                    IconButton(onClick = { showOptions = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Open settings")
                    }
                },
            )
        },
        bottomBar = {
            if (syncState.configured && state.recovery == LocalRecoveryState.Ready) {
                NavigationBar {
                    HistorySection.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = section == destination,
                            onClick = { section = destination },
                            icon = {
                                Icon(
                                    imageVector = when (destination) {
                                        HistorySection.LOCAL -> Icons.Default.Home
                                        HistorySection.SHARED -> Icons.Default.Share
                                        HistorySection.BOOKMARKS -> Icons.Default.Favorite
                                    },
                                    contentDescription = null,
                                )
                            },
                            label = { Text(destination.title) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (syncState.configured && state.recovery == LocalRecoveryState.Ready) {
                FloatingActionButton(onClick = { showNewClip = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New clip")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SyncBar(syncState, syncViewModel::synchronize)

            when (state.recovery) {
                is LocalRecoveryState.TemporarilyUnavailable -> RecoveryPanel(
                    "Local encryption is temporarily unavailable.", "Retry", viewModel::retryRecovery,
                )
                is LocalRecoveryState.MissingKeys, is LocalRecoveryState.InvalidatedKeys -> RecoveryPanel(
                    "Local encryption recovery is required.", "Reset encrypted history",
                ) { confirmRecoveryReset = true }
                LocalRecoveryState.Ready -> {
                    if (!syncState.configured) {
                        ConnectionSetup(syncState, syncViewModel)
                    }

                    if (syncState.configured) {
                        if (searchExpanded) {
                            OutlinedTextField(
                                value = state.query,
                                onValueChange = viewModel::setQuery,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Search clips") },
                                singleLine = true,
                            )
                        }

                        val visibleText = state.items.filter { item -> section.includes(item) }
                        val visibleImages = imageState.images.filter { image ->
                            section.includes(image) && (
                                state.query.isBlank() || image.displayName.orEmpty().contains(state.query, ignoreCase = true)
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
    }

    if (showNewClip) {
        NewClipSheet(
            text = state.composerText,
            imageBusy = imageState.busy,
            onTextChanged = viewModel::setComposerText,
            onSaveText = {
                viewModel.captureComposer()
                showNewClip = false
                section = HistorySection.LOCAL
            },
            onShareText = {
                viewModel.captureComposer { syncViewModel.synchronize() }
                showNewClip = false
                section = HistorySection.SHARED
            },
            onImportLocal = {
                viewModel.importFocusedClipboard()
                showNewClip = false
                section = HistorySection.LOCAL
            },
            onImportShared = {
                viewModel.importFocusedClipboard { syncViewModel.synchronize() }
                showNewClip = false
                section = HistorySection.SHARED
            },
            onImageLocal = {
                showNewClip = false
                pickImage(false)
                section = HistorySection.LOCAL
            },
            onImageShared = {
                showNewClip = false
                pickImage(true)
                section = HistorySection.SHARED
            },
            onDismiss = { showNewClip = false },
        )
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
private fun SyncBar(state: SyncUiState, onSync: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (state.status == "Connected") MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.configured) "${state.status} · ${state.devices.size} devices" else "Cloud is not configured",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (state.configured) {
                TextButton(onClick = onSync, enabled = !state.isBusy) {
                    Text(if (state.isBusy) "Refreshing" else "Sync now")
                }
            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewClipSheet(
    text: String,
    imageBusy: Boolean,
    onTextChanged: (String) -> Unit,
    onSaveText: () -> Unit,
    onShareText: () -> Unit,
    onImportLocal: () -> Unit,
    onImportShared: () -> Unit,
    onImageLocal: () -> Unit,
    onImageShared: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("New clip", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = text, onValueChange = onTextChanged, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Type or paste text") }, minLines = 3, maxLines = 6,
            )
            DestinationActions(
                localLabel = "Save locally",
                sharedLabel = "Encrypt & share",
                localEnabled = text.isNotEmpty(),
                sharedEnabled = text.isNotEmpty(),
                onLocal = onSaveText,
                onShared = onShareText,
            )
            HorizontalDivider()
            Text("Clipboard", style = MaterialTheme.typography.titleSmall)
            DestinationActions("Import locally", "Import & share", onLocal = onImportLocal, onShared = onImportShared)
            HorizontalDivider()
            Text("Image", style = MaterialTheme.typography.titleSmall)
            DestinationActions(
                "Save image", "Share image",
                localEnabled = !imageBusy,
                sharedEnabled = !imageBusy,
                onLocal = onImageLocal,
                onShared = onImageShared,
            )
        }
    }
}

@Composable
private fun DestinationActions(
    localLabel: String,
    sharedLabel: String,
    localEnabled: Boolean = true,
    sharedEnabled: Boolean = true,
    onLocal: () -> Unit,
    onShared: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onLocal, enabled = localEnabled, modifier = Modifier.weight(1f)) {
            Text(localLabel)
        }
        Button(onClick = onShared, enabled = sharedEnabled, modifier = Modifier.weight(1f)) {
            Text(sharedLabel)
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
    val entries = (images.map(HistoryEntry::Image) + textItems.map(HistoryEntry::Text))
        .sortedByDescending(HistoryEntry::createdAtEpochMillis)
    LazyColumn(modifier.fillMaxWidth()) {
        items(entries, key = HistoryEntry::key) { entry ->
            when (entry) {
                is HistoryEntry.Image -> {
                    val image = entry.value
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
                is HistoryEntry.Text -> {
                    val item = entry.value
                    TextClip(
                        item = item,
                        onCopy = { onCopyText(item) },
                        onUpload = if (item.cloudSyncState == "local") ({ onUploadText(item) }) else null,
                        onBookmark = { onBookmarkText(item) },
                        onDelete = { onDeleteText(item) },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (entries.isEmpty()) {
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
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onView).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                preview != null -> Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = image.displayName ?: "Image preview",
                    modifier = Modifier.size(64.dp),
                    contentScale = ContentScale.Crop,
                )
                loading -> Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                else -> Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    Text("Image", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(image.displayName ?: "Image", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${image.width} × ${image.height} · $sourceLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCopy) {
                Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy image")
            }
            Box {
                IconButton(onClick = { more = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Image actions")
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("Share to another app") }, onClick = { more = false; onShare() })
                    onUpload?.let { DropdownMenuItem(text = { Text("Encrypt & share") }, onClick = { more = false; it() }) }
                    onRetry?.let { DropdownMenuItem(text = { Text("Retry upload") }, onClick = { more = false; it() }) }
                    DropdownMenuItem(text = { Text(if (image.isBookmarked) "Remove bookmark" else "Bookmark") }, onClick = { more = false; onBookmark() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { more = false; onDelete() })
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
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${textStateLabel(item)} · ${relativeTime(item.createdAtEpochMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCopy) {
                Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy text")
            }
            Box {
                IconButton(onClick = { more = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Text actions")
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    onUpload?.let { DropdownMenuItem(text = { Text("Encrypt & share") }, onClick = { more = false; it() }) }
                    DropdownMenuItem(text = { Text(if (item.isBookmarked) "Remove bookmark" else "Bookmark") }, onClick = { more = false; onBookmark() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { more = false; onDelete() })
                }
            }
        }
    }
}

private sealed interface HistoryEntry {
    val key: String
    val createdAtEpochMillis: Long

    data class Image(val value: LocalImageEntity) : HistoryEntry {
        override val key = "image-${value.itemId}"
        override val createdAtEpochMillis = value.createdAtEpochMillis
    }

    data class Text(val value: LocalClipboardItem) : HistoryEntry {
        override val key = "text-${value.id}"
        override val createdAtEpochMillis = value.createdAtEpochMillis
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
