package com.nishantattrey.clipsync.platform

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import com.nishantattrey.clipsync.core.local.capture.ClipboardGateway
import com.nishantattrey.clipsync.core.local.capture.ForegroundFocusState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActivityFocusState @Inject constructor() : ForegroundFocusState {
    @Volatile private var resumed = false
    @Volatile private var focused = false
    fun setResumed(value: Boolean) { resumed = value }
    fun setFocused(value: Boolean) { focused = value }
    override fun isVisibleAndFocused(): Boolean = resumed && focused
}

class AndroidClipboardGateway @Inject constructor(
    private val clipboard: ClipboardManager,
) : ClipboardGateway {
    override fun readText(): String? {
        val description = clipboard.primaryClipDescription ?: return null
        if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) return null
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }

    override fun writeText(text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText("ClipSync text", text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
    }
}
