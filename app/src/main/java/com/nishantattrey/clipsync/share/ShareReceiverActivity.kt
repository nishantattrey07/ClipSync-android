package com.nishantattrey.clipsync.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.nishantattrey.clipsync.MainActivity
import com.nishantattrey.clipsync.core.local.capture.TextCaptureUseCase
import com.nishantattrey.clipsync.core.local.model.CaptureResult
import com.nishantattrey.clipsync.core.local.model.CaptureSource
import com.nishantattrey.clipsync.core.local.model.LocalDataResult
import com.nishantattrey.clipsync.core.local.repository.LocalClipboardRepository
import com.nishantattrey.clipsync.core.sync.engine.CloudSyncCoordinator
import com.nishantattrey.clipsync.sync.ImageCaptureCoordinator
import com.nishantattrey.clipsync.ui.theme.ClipsyncTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {
    @Inject lateinit var textCapture: TextCaptureUseCase
    @Inject lateinit var localRepository: LocalClipboardRepository
    @Inject lateinit var imageCapture: ImageCaptureCoordinator
    @Inject lateinit var cloudSync: CloudSyncCoordinator

    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val payload = extractPayload(intent)
        if (payload == null) {
            finish()
            return
        }
        setContent {
            ClipsyncTheme {
                ShareDestinationScreen(
                    kind = when (payload) {
                        is SharedPayload.Images -> if (payload.uris.size == 1) "image" else "${payload.uris.size} images"
                        is SharedPayload.Text -> "text"
                    },
                    busy = busy,
                    error = error,
                    onLocal = { save(payload, upload = false) },
                    onShared = { save(payload, upload = true) },
                    onCancel = ::finish,
                )
            }
        }
    }

    private fun save(payload: SharedPayload, upload: Boolean) {
        if (busy) return
        lifecycleScope.launch {
            busy = true
            error = null
            runCatching {
                when (payload) {
                    is SharedPayload.Images -> payload.uris.take(20).forEach { imageCapture.capture(it, upload) }
                    is SharedPayload.Text -> saveText(payload.value, upload)
                }
            }.onSuccess {
                startActivity(Intent(this@ShareReceiverActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(MainActivity.EXTRA_OPEN_SHARED, upload)
                })
                finish()
            }.onFailure {
                busy = false
                error = if (payload is SharedPayload.Images) {
                    "This image could not be decoded or saved."
                } else {
                    "This text could not be saved."
                }
            }
        }
    }

    private suspend fun saveText(text: String, upload: Boolean) {
        val result = textCapture(text, CaptureSource.SHARE)
        val id = ((result as? LocalDataResult.Success)?.value as? CaptureResult)?.let {
            when (it) {
                is CaptureResult.Stored -> it.id
                is CaptureResult.Duplicate -> it.id
            }
        } ?: error("Local text storage is unavailable.")
        if (upload && localRepository.queueForUpload(id)) cloudSync.synchronize()
    }
}

private sealed interface SharedPayload {
    data class Images(val uris: List<Uri>) : SharedPayload
    data class Text(val value: String) : SharedPayload
}

@Suppress("DEPRECATION")
private fun extractPayload(intent: Intent): SharedPayload? {
    if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        return uris.takeIf { it.isNotEmpty() }?.let(SharedPayload::Images)
    }
    if (intent.action != Intent.ACTION_SEND) return null
    val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    if (uri != null) return SharedPayload.Images(listOf(uri))
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
    return text?.let { SharedPayload.Text(it) }
}

@Composable
private fun ShareDestinationScreen(
    kind: String,
    busy: Boolean,
    error: String?,
    onLocal: () -> Unit,
    onShared: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            Text("Add $kind to ClipSync", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Keep it only on this phone, or encrypt and share it with your connected devices.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (busy) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            } else {
                Button(onClick = onShared, modifier = Modifier.fillMaxWidth()) { Text("Share with devices") }
                OutlinedButton(onClick = onLocal, modifier = Modifier.fillMaxWidth()) { Text("Save only on this phone") }
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
