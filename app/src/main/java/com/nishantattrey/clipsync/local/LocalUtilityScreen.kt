package com.nishantattrey.clipsync.local

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.BiasAlignment
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nishantattrey.clipsync.core.local.model.ShareAction
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.layout.width
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.mutableStateMapOf
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
import com.nishantattrey.clipsync.ui.theme.SyncFailure
import com.nishantattrey.clipsync.ui.theme.SyncSuccess
import com.nishantattrey.clipsync.ui.theme.SyncWarning

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
    val urlPreviews by viewModel.urlPreviews.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var section by rememberSaveable { mutableStateOf(if (openShared) HistorySection.SHARED else HistorySection.LOCAL) }
    var showNewClip by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var pendingTextDelete by remember { mutableStateOf<String?>(null) }
    var pendingImageDelete by remember { mutableStateOf<LocalImageEntity?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var confirmRecoveryReset by remember { mutableStateOf(false) }
    var viewedImage by remember { mutableStateOf<Pair<String, Bitmap>?>(null) }
    val localListState = rememberLazyListState()
    val sharedListState = rememberLazyListState()
    val bookmarksListState = rememberLazyListState()

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }
    LaunchedEffect(imageState.message) {
        imageState.message?.let { snackbar.showSnackbar(it); imageViewModel.dismissMessage() }
    }
    LaunchedEffect(openShared) {
        section = if (openShared) HistorySection.SHARED else HistorySection.LOCAL
    }
    LaunchedEffect(state.settings.imageRetentionPeriod) {
        imageViewModel.applyRetention(state.settings.imageRetentionPeriod)
    }

    Scaffold(
        topBar = {
            ClipSyncTopBar(onSettings = { showOptions = true })
        },
        bottomBar = {
            if (syncState.configured && state.recovery == LocalRecoveryState.Ready) {
                ClipBottomNavigation(section = section, onSection = { section = it })
            }
        },
        floatingActionButton = {
            if (syncState.configured && state.recovery == LocalRecoveryState.Ready) {
                NewClipFab(onClick = { showNewClip = true })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val now = remember(syncState) { System.currentTimeMillis() }
            val presentation = remember(syncState, now) { connectionPresentation(syncState, now) }
            ConnectionUtility(
                presentation = presentation,
                syncing = syncState.isBusy,
                onSync = syncViewModel::synchronize,
                onSettings = { showOptions = true },
            )

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
                        PersistentClipSearch(query = state.query, onQueryChange = viewModel::setQuery)

                        val visibleText = state.items.filter { item -> section.includes(item) }
                        val visibleImages = imageState.images.filter { image ->
                            section.includes(image) && (
                                state.query.isBlank() || image.displayName.orEmpty().contains(state.query, ignoreCase = true)
                            )
                        }

                        val visibleCount = visibleText.size + visibleImages.size
                        ClipSectionHeader(section = section, count = visibleCount)
                        ClipHistory(
                            modifier = Modifier.weight(1f),
                            listState = when (section) {
                                HistorySection.LOCAL -> localListState
                                HistorySection.SHARED -> sharedListState
                                HistorySection.BOOKMARKS -> bookmarksListState
                            },
                            section = section,
                            textItems = visibleText,
                            images = visibleImages,
                            previews = imageState.previews,
                            loadingPreviews = imageState.loadingPreviews,
                            deviceNameFor = { deviceId ->
                                state.settings.deviceAliases[deviceId]
                                    ?: syncState.devices.firstOrNull { it.deviceId == deviceId }?.name
                            },
                            canLoadMore = state.canLoadMore,
                            isLoadingMore = state.isLoadingMore,
                            urlPreviews = urlPreviews,
                            urlPreviewsEnabled = state.settings.urlPreviewsEnabled,
                            onLoadUrlPreview = viewModel::loadUrlPreview,
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
            textRetention = state.settings.textRetentionPeriod,
            imageRetention = state.settings.imageRetentionPeriod,
            defaultShareAction = state.settings.defaultShareAction,
            autoSync = state.settings.autoSync,
            urlPreviewsEnabled = state.settings.urlPreviewsEnabled,
            onSensitiveCopy = viewModel::setSensitiveCopy,
            onTextRetention = viewModel::setTextRetention,
            onImageRetention = { period -> viewModel.setImageRetention(period); imageViewModel.applyRetention(period) },
            onDefaultShareAction = viewModel::setDefaultShareAction,
            onAutoSync = viewModel::setAutoSync,
            onUrlPreviewsEnabled = viewModel::setUrlPreviewsEnabled,
            onRenameDevice = syncViewModel::saveDeviceName,
            onDeviceNameChanged = syncViewModel::updateDeviceName,
            aliases = state.settings.deviceAliases,
            onAliasChanged = viewModel::setDeviceAlias,
            onClear = {
                showOptions = false
                confirmClearHistory = true
            },
            onDisconnect = {
                showOptions = false
                confirmDisconnect = true
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
            { viewModel.clearUnbookmarkedConfirmed(); imageViewModel.clearUnbookmarked(); confirmClearHistory = false },
        ) { confirmClearHistory = false }
    }
    if (confirmDisconnect) {
        ConfirmDialog(
            "Disconnect from the current ClipSync channel and clear configuration?",
            { syncViewModel.disconnect(); confirmDisconnect = false },
        ) { confirmDisconnect = false }
    }
    if (confirmRecoveryReset) {
        ConfirmDialog(
            "Permanently reset encrypted local history and bookmarks?",
            { viewModel.resetEncryptedHistoryConfirmed(); confirmRecoveryReset = false },
        ) { confirmRecoveryReset = false }
    }
}

@Composable
private fun ConnectionSetup(state: SyncUiState, viewModel: SyncViewModel) {
    val context = LocalContext.current
    val sqlText = remember {
        runCatching {
            context.assets.open("database_setup.sql").use { input ->
                input.bufferedReader().use { it.readText() }
            }
        }.getOrDefault("")
    }
    var showSqlDialog by remember { mutableStateOf(false) }

    if (showSqlDialog) {
        AlertDialog(
            onDismissRequest = { showSqlDialog = false },
            title = { Text("Database Setup SQL") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = sqlText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSqlDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.connect_to_clipsync), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(state.supabaseUrl, viewModel::updateUrl, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.supabase_url)) }, singleLine = true)
        OutlinedTextField(state.publishableKey, viewModel::updateKey, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.public_anon_key)) }, singleLine = true)
        OutlinedTextField(
            state.channelSecret, viewModel::updateSecret, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.channel_secret)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true,
        )
        OutlinedTextField(state.deviceName, viewModel::updateDeviceName, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.device_name)) }, singleLine = true)
        Button(onClick = viewModel::saveConfiguration, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.connect)) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Help & Setup",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Set up a dedicated Supabase project or revisit the connection requirements.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Setup",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        val steps = listOf(
            "Create a Supabase project" to "Create a dedicated project and wait for database provisioning to finish.",
            "Run the setup SQL" to "Quit older clients and run the bundled script in Supabase SQL Editor. The script resets incompatible cloud schemas.",
            "Collect the project credentials" to "Copy the project URL and anon or publishable key from Supabase Project Settings.",
            "Connect every trusted device" to "Enter the same generated E2E secret on each device that should share this clipboard."
        )

        steps.forEachIndexed { index, (title, desc) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${index + 1}",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(text = desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Database Script",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "The bundled script is the canonical ClipSync database contract.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    runCatching {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("database_setup.sql", sqlText)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "SQL copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Copy Setup SQL")
            }
            OutlinedButton(
                onClick = { showSqlDialog = true },
                modifier = Modifier.weight(1f)
            ) {
                Text("View SQL")
            }
        }
    }
}

@Composable
private fun DestinationToggle(
    shared: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(4.dp)
    ) {
        val targetBias = if (shared) 1f else -1f
        val animatedBias by animateFloatAsState(targetBias, label = "toggleBias")

        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .fillMaxHeight()
                .align(BiasAlignment(animatedBias, 0f))
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                .shadow(elevation = 1.dp, shape = RoundedCornerShape(8.dp))
        )

        Row(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onToggle(false) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.local_only),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (!shared) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (!shared) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onToggle(true) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.shared_cloud),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (shared) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (shared) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun QuickImportCard(
    onClick: () -> Unit,
    iconResId: Int,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier.height(96.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(iconResId),
                contentDescription = title,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
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
    var shareDestination by rememberSaveable { mutableStateOf(true) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "New Clip",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            DestinationToggle(
                shared = shareDestination,
                onToggle = { shareDestination = it }
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickImportCard(
                    onClick = {
                        if (shareDestination) onImportShared() else onImportLocal()
                    },
                    iconResId = R.drawable.ic_clipboard,
                    title = stringResource(R.string.from_clipboard),
                    subtitle = stringResource(R.string.paste_copied_text),
                    modifier = Modifier.weight(1f)
                )
                QuickImportCard(
                    onClick = {
                        if (shareDestination) onImageShared() else onImageLocal()
                    },
                    enabled = !imageBusy,
                    iconResId = R.drawable.ic_image,
                    title = stringResource(R.string.from_gallery),
                    subtitle = stringResource(R.string.select_photos),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(R.string.or_compose),
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            }

            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        stringResource(R.string.type_paste_placeholder),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                },
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(16.dp),
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { onTextChanged("") }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear_text))
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
                )
            )

            Button(
                onClick = {
                    onDismiss()
                    if (shareDestination) onShareText() else onSaveText()
                },
                enabled = text.isNotEmpty(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                ),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(
                    text = if (shareDestination) stringResource(R.string.encrypt_and_share) else stringResource(R.string.save_locally),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ClipHistory(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    section: HistorySection,
    textItems: List<LocalClipboardItem>,
    images: List<LocalImageEntity>,
    previews: Map<String, Bitmap>,
    loadingPreviews: Set<String>,
    deviceNameFor: (String) -> String?,
    canLoadMore: Boolean,
    isLoadingMore: Boolean,
    urlPreviews: Map<String, String>,
    urlPreviewsEnabled: Boolean,
    onLoadUrlPreview: (String) -> Unit,
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
    val entries = remember(images, textItems) {
        (images.map(HistoryEntry::Image) + textItems.map(HistoryEntry::Text))
            .sortedByDescending(HistoryEntry::createdAtEpochMillis)
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState) {
        items(entries, key = HistoryEntry::key) { entry ->
            when (entry) {
                is HistoryEntry.Image -> {
                    val image = entry.value
                    val onLoad = remember(image) { { onLoadPreview(image) } }
                    val onView = remember(image) { { onViewImage(image) } }
                    val onCopy = remember(image) { { onCopyImage(image) } }
                    val onShare = remember(image) { { onShareImage(image) } }
                    val onUpload = remember(image) { if (image.cloudSyncState == "local") ({ onUploadImage(image) }) else null }
                    val onBookmark = remember(image) { { onBookmarkImage(image) } }
                    val onDelete = remember(image) { { onDeleteImage(image) } }
                    ImageClip(
                        image = image,
                        preview = previews[image.itemId],
                        loading = image.itemId in loadingPreviews,
                        sourceLabel = imageSourceLabel(image, deviceNameFor),
                        loadPreview = onLoad,
                        onView = onView,
                        onCopy = onCopy,
                        onShare = onShare,
                        onUpload = onUpload,
                        onRetry = if (image.cloudSyncState in setOf("queued", "retrying")) onRetryImage else null,
                        onBookmark = onBookmark,
                        onDelete = onDelete,
                    )
                }
                is HistoryEntry.Text -> {
                    val item = entry.value
                    val onCopy = remember(item) { { onCopyText(item) } }
                    val onUpload = remember(item) { if (item.cloudSyncState in setOf("local", "failed")) ({ onUploadText(item) }) else null }
                    val onBookmark = remember(item) { { onBookmarkText(item) } }
                    val onDelete = remember(item) { { onDeleteText(item) } }
                    TextClip(
                        item = item,
                        urlPreviews = urlPreviews,
                        urlPreviewsEnabled = urlPreviewsEnabled,
                        onLoadUrlPreview = onLoadUrlPreview,
                        onCopy = onCopy,
                        onUpload = onUpload,
                        onBookmark = onBookmark,
                        onDelete = onDelete,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    when (section) {
                        HistorySection.SHARED -> "Nothing shared yet. Tap + to send a clip."
                        HistorySection.LOCAL -> "No clips saved on this phone."
                        HistorySection.BOOKMARKS -> "No bookmarks yet."
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
            Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onView).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                preview != null -> Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = image.displayName ?: "Image preview",
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                loading -> Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                else -> Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    Text("Image", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(image.displayName ?: "Image", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${image.width} × ${image.height}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    imageSourceAndTimeLabel(image, sourceLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ClipStatusIcon(image.cloudSyncState)
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
    urlPreviews: Map<String, String>,
    urlPreviewsEnabled: Boolean,
    onLoadUrlPreview: (String) -> Unit,
    onCopy: () -> Unit,
    onUpload: (() -> Unit)?,
    onBookmark: () -> Unit,
    onDelete: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                
                val isUrl = remember(item.text) { UrlPreviewFetcher.isValidUrl(item.text) }
                if (isUrl && urlPreviewsEnabled) {
                    LaunchedEffect(item.text) {
                        onLoadUrlPreview(item.text)
                    }
                    val previewTitle = urlPreviews[item.text.trim()]
                    val uri = remember(item.text) { runCatching { android.net.Uri.parse(item.text.trim()) }.getOrNull() }
                    val domain = uri?.host ?: ""
                    val context = LocalContext.current

                    Card(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_sync),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(
                                    text = previewTitle ?: item.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (domain.isNotEmpty()) {
                                    Text(
                                        text = domain,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Text(
                    "${textStateLabel(item)} · ${relativeTime(item.createdAtEpochMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ClipStatusIcon(item.cloudSyncState)
            IconButton(onClick = onCopy) {
                Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy text")
            }
            Box {
                IconButton(onClick = { more = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Text actions")
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    onUpload?.let {
                        val label = if (item.cloudSyncState == "failed") "Retry upload" else "Encrypt & share"
                        DropdownMenuItem(text = { Text(label) }, onClick = { more = false; it() })
                    }
                    DropdownMenuItem(text = { Text(if (item.isBookmarked) "Remove bookmark" else "Bookmark") }, onClick = { more = false; onBookmark() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { more = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun ClipStatusIcon(state: String) {
    val presentation = when (state) {
        "synced", "received" -> Triple(R.drawable.ic_status_synced, SyncSuccess, "Synced")
        "queued", "prepared", "retrying", "retry", "upload_pending", "object_uploaded" -> Triple(R.drawable.ic_status_queued, SyncWarning, "Waiting to upload")
        "failed" -> Triple(R.drawable.ic_status_failed, SyncFailure, "Upload failed")
        else -> null
    }
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        presentation?.let { (icon, color, description) ->
            Icon(
                painter = painterResource(icon),
                contentDescription = description,
                modifier = Modifier.size(20.dp),
                tint = color,
            )
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

@Composable
private fun OptionsSheet(
    syncState: SyncUiState,
    sensitiveCopy: Boolean,
    textRetention: RetentionPeriod,
    imageRetention: RetentionPeriod,
    defaultShareAction: ShareAction,
    autoSync: Boolean,
    urlPreviewsEnabled: Boolean,
    onSensitiveCopy: (Boolean) -> Unit,
    onTextRetention: (RetentionPeriod) -> Unit,
    onImageRetention: (RetentionPeriod) -> Unit,
    onDefaultShareAction: (ShareAction) -> Unit,
    onAutoSync: (Boolean) -> Unit,
    onUrlPreviewsEnabled: (Boolean) -> Unit,
    onRenameDevice: () -> Unit,
    onDeviceNameChanged: (String) -> Unit,
    aliases: Map<String, String>,
    onAliasChanged: (String, String?) -> Unit,
    onClear: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val localAliases = remember(aliases) { mutableStateMapOf<String, String>().apply { putAll(aliases) } }
    val onDismissWithSave = {
        localAliases.forEach { (deviceId, alias) ->
            if (aliases[deviceId] != alias) {
                onAliasChanged(deviceId, alias.ifBlank { null })
            }
        }
        onDismiss()
    }
    Dialog(
        onDismissRequest = onDismissWithSave,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = onDismissWithSave) { Text(stringResource(R.string.done)) }
                    }
                }

                // General Settings Card
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.general), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SettingsRow(
                                    title = stringResource(R.string.sensitive_copy),
                                    subtitle = stringResource(R.string.sensitive_copy_sub)
                                ) {
                                    Switch(checked = sensitiveCopy, onCheckedChange = onSensitiveCopy)
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                SettingsRow(
                                    title = stringResource(R.string.auto_sync_title),
                                    subtitle = stringResource(R.string.auto_sync_subtitle)
                                ) {
                                    Switch(checked = autoSync, onCheckedChange = onAutoSync)
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                SettingsRow(
                                    title = stringResource(R.string.url_previews_title),
                                    subtitle = stringResource(R.string.url_previews_subtitle)
                                ) {
                                    Switch(checked = urlPreviewsEnabled, onCheckedChange = onUrlPreviewsEnabled)
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                SettingsDropDownItem(
                                    title = stringResource(R.string.default_share_action),
                                    subtitle = stringResource(R.string.default_share_action_sub),
                                    selectedText = defaultShareAction.displayName,
                                    entries = ShareAction.entries.toList(),
                                    entryLabel = { it.displayName },
                                    onSelected = onDefaultShareAction
                                )
                            }
                        }
                    }
                }

                // Devices Card
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.devices), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                OutlinedTextField(
                                    value = syncState.deviceName,
                                    onValueChange = onDeviceNameChanged,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.this_device_published_name)) },
                                    singleLine = true
                                )
                                Button(
                                    onClick = onRenameDevice,
                                    enabled = syncState.deviceName.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.save_device_name))
                                }
                                
                                val otherDevices = syncState.devices.filter { it.deviceId != syncState.currentDeviceId }
                                if (otherDevices.isNotEmpty()) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    Text(stringResource(R.string.device_aliases), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    otherDevices.forEach { device ->
                                        val localValue = localAliases[device.deviceId].orEmpty()
                                        OutlinedTextField(
                                            value = localValue,
                                            onValueChange = { newValue ->
                                                localAliases[device.deviceId] = newValue
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .onFocusChanged { focusState ->
                                                    if (!focusState.isFocused) {
                                                        onAliasChanged(device.deviceId, localAliases[device.deviceId]?.ifBlank { null })
                                                    }
                                                },
                                            label = { Text(stringResource(R.string.device_alias_label, device.name, device.platform)) },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                            keyboardActions = KeyboardActions(
                                                onDone = {
                                                    onAliasChanged(device.deviceId, localAliases[device.deviceId]?.ifBlank { null })
                                                }
                                            )
                                        )
                                    }
                                } else {
                                    Text(stringResource(R.string.no_other_devices), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }

                // Data on this phone Card
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.data_on_phone), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = stringResource(R.string.retention_warning),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                SettingsDropDownItem(
                                    title = stringResource(R.string.text_retention),
                                    selectedText = textRetention.displayName,
                                    entries = RetentionPeriod.entries.toList(),
                                    entryLabel = { it.displayName },
                                    onSelected = onTextRetention
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                SettingsDropDownItem(
                                    title = stringResource(R.string.image_retention),
                                    selectedText = imageRetention.displayName,
                                    entries = RetentionPeriod.entries.toList(),
                                    entryLabel = { it.displayName },
                                    onSelected = onImageRetention
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedButton(
                                    onClick = onClear,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.clear_history))
                                }
                            }
                        }
                    }
                }

                // Connection Card
                if (syncState.configured) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.connection), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(stringResource(R.string.supabase_url), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                                        Text(syncState.supabaseUrl, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    OutlinedButton(
                                        onClick = onDisconnect,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Text(stringResource(R.string.disconnect_channel))
                                    }
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    action: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        action()
    }
}

@Composable
private fun <T> SettingsDropDownItem(
    title: String,
    subtitle: String? = null,
    selectedText: String,
    entries: List<T>,
    entryLabel: (T) -> String,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = selectedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                entries.forEach { entry ->
                    DropdownMenuItem(
                        text = { Text(entryLabel(entry)) },
                        onClick = {
                            expanded = false
                            onSelected(entry)
                        }
                    )
                }
            }
        }
    }
}

private val ShareAction.displayName: String
    get() = when (this) {
        ShareAction.ASK_EVERY_TIME -> "Ask every time"
        ShareAction.SHARE_ONLINE -> "Share online"
        ShareAction.SAVE_LOCAL -> "Save locally"
    }

@Composable
private fun ImageViewer(name: String, bitmap: Bitmap, onDismiss: () -> Unit) {
    var scale by rememberSaveable { mutableStateOf(1f) }
    var offsetX by rememberSaveable { mutableStateOf(0f) }
    var offsetY by rememberSaveable { mutableStateOf(0f) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), color = MaterialTheme.colorScheme.inverseOnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Image(
                    bitmap = bitmap.asImageBitmap(), contentDescription = name,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                if (scale == 1f) {
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                }
                            }
                        },
                    contentScale = ContentScale.Fit,
                )
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

internal enum class HistorySection(val title: String) {
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
        RetentionPeriod.NEVER -> "Always"
        RetentionPeriod.ONE_HOUR -> "1 hour"
        RetentionPeriod.SIX_HOURS -> "6 hours"
        RetentionPeriod.ONE_DAY -> "1 day"
        RetentionPeriod.SEVEN_DAYS -> "7 days"
        RetentionPeriod.THIRTY_DAYS -> "30 days"
    }

private fun imageStateLabel(state: String): String = when (state) {
    "local" -> "Local only"
    "queued", "prepared", "upload_pending" -> "Waiting to upload"
    "retrying", "retry" -> "Upload retrying"
    "received" -> "From cloud"
    "synced" -> "Shared"
    "failed" -> "Upload failed"
    "object_uploaded" -> "Upload finishing"
    else -> state.replace('_', ' ')
}

private fun imageSourceLabel(image: LocalImageEntity, deviceNameFor: (String) -> String?): String {
    if (image.cloudSyncState != "received") return imageStateLabel(image.cloudSyncState)
    val sender = image.senderDeviceId?.let(deviceNameFor)
    return if (sender.isNullOrBlank()) "From another device" else "From $sender"
}

private fun imageSourceAndTimeLabel(image: LocalImageEntity, sourceLabel: String): String {
    val time = relativeTime(image.createdAtEpochMillis)
    return when (image.cloudSyncState) {
        "received", "local" -> "$sourceLabel · $time"
        else -> time
    }
}

private fun textStateLabel(item: LocalClipboardItem): String = when {
    item.captureSource == CaptureSource.CLOUD -> "From cloud"
    item.cloudSyncState == "synced" -> "Shared from this device"
    item.cloudSyncState in setOf("queued", "upload_pending", "prepared") -> "Waiting to upload"
    item.cloudSyncState == "retrying" -> "Retrying upload"
    item.cloudSyncState == "failed" -> "Upload needs attention"
    else -> "Local only"
}

private fun relativeTime(epochMillis: Long): String = DateUtils.getRelativeTimeSpanString(
    epochMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
).toString()
