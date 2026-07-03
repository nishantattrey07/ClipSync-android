package com.nishantattrey.clipsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.nishantattrey.clipsync.local.LocalClipboardViewModel
import com.nishantattrey.clipsync.local.LocalUtilityScreen
import com.nishantattrey.clipsync.platform.ActivityFocusState
import com.nishantattrey.clipsync.ui.theme.ClipsyncTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var focusState: ActivityFocusState
    private val viewModel: LocalClipboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ClipsyncTheme { LocalUtilityScreen(viewModel) } }
    }

    override fun onResume() {
        super.onResume()
        focusState.setResumed(true)
    }

    override fun onPause() {
        focusState.setResumed(false)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focusState.setFocused(hasFocus)
    }
}
