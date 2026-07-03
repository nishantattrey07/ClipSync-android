package com.nishantattrey.clipsync.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.nishantattrey.clipsync.core.local.capture.TextCaptureUseCase
import com.nishantattrey.clipsync.core.local.model.CaptureSource
import com.nishantattrey.clipsync.core.local.model.EmptyCaptureException
import com.nishantattrey.clipsync.core.local.model.LocalDataResult
import com.nishantattrey.clipsync.core.local.model.LocalRecoveryState
import com.nishantattrey.clipsync.core.local.model.OversizedCaptureException
import com.nishantattrey.clipsync.core.local.repository.LocalRecoveryCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {
    @Inject lateinit var capture: TextCaptureUseCase
    @Inject lateinit var recovery: LocalRecoveryCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = extractSharedText(intent)
        if (text == null) {
            Toast.makeText(this, "No plain text to save", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        lifecycleScope.launch {
            val recoveryState = recovery.state()
            val message = try {
                val result = if (recoveryState == LocalRecoveryState.Ready) {
                    capture(text, CaptureSource.SHARE)
                } else {
                    LocalDataResult.RecoveryRequired(recoveryState)
                }
                if (result is LocalDataResult.Success) "Saved to ClipSync" else "Local recovery required"
            } catch (_: EmptyCaptureException) {
                "Shared text is empty"
            } catch (_: OversizedCaptureException) {
                "Shared text exceeds the size limit"
            }
            Toast.makeText(this@ShareReceiverActivity, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

internal fun extractSharedText(intent: Intent): String? {
    if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
    return intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
}
