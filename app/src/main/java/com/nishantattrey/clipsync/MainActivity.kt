package com.nishantattrey.clipsync

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import com.nishantattrey.clipsync.local.LocalClipboardViewModel
import com.nishantattrey.clipsync.local.LocalUtilityScreen
import com.nishantattrey.clipsync.platform.ActivityFocusState
import com.nishantattrey.clipsync.sync.SyncViewModel
import com.nishantattrey.clipsync.sync.ImageCaptureViewModel
import com.nishantattrey.clipsync.core.sync.realtime.RealtimeSyncController
import com.nishantattrey.clipsync.ui.theme.ClipsyncTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var focusState: ActivityFocusState
    @Inject lateinit var realtime: RealtimeSyncController
    private val viewModel: LocalClipboardViewModel by viewModels()
    private val syncViewModel: SyncViewModel by viewModels()
    private val imageViewModel: ImageCaptureViewModel by viewModels()
    private var pendingImageUpload = true
    private var openShared by mutableStateOf(true)
    private val imagePicker = registerForActivityResult(PickMultipleVisualMedia(maxItems = 20)) { uris ->
        if (uris.isNotEmpty()) imageViewModel.capture(uris, pendingImageUpload)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openShared = intent.getBooleanExtra(EXTRA_OPEN_SHARED, true)
        enableEdgeToEdge()
        setContent {
            ClipsyncTheme {
                LocalUtilityScreen(viewModel, syncViewModel, imageViewModel, openShared) { upload ->
                    pendingImageUpload = upload
                    imagePicker.launch(PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openShared = intent.getBooleanExtra(EXTRA_OPEN_SHARED, true)
    }

    override fun onResume() {
        super.onResume()
        focusState.setResumed(true)
        syncViewModel.synchronize()
    }

    override fun onStart() {
        super.onStart()
        realtime.start()
    }

    override fun onStop() {
        realtime.stop()
        super.onStop()
    }

    override fun onPause() {
        focusState.setResumed(false)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focusState.setFocused(hasFocus)
    }

    companion object {
        const val EXTRA_OPEN_SHARED = "com.nishantattrey.clipsync.OPEN_SHARED"
    }
}
