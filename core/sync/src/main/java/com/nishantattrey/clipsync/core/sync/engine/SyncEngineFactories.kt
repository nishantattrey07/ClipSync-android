package com.nishantattrey.clipsync.core.sync.engine

import com.nishantattrey.clipsync.core.sync.image.ImageSyncEngine
import com.nishantattrey.clipsync.core.sync.model.ClipboardCloudTransport

fun interface TextSyncEngineFactory {
    fun create(transport: ClipboardCloudTransport): TextSyncEngine
}

fun interface ImageSyncEngineFactory {
    fun create(transport: ClipboardCloudTransport): ImageSyncEngine
}
