package com.nishantattrey.clipsync.core.protocol.model

data class SyncCursor(
    val channelId: String,
    val createdAtMicroseconds: Long,
    val id: String,
)
