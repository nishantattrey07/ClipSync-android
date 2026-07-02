package com.nishantattrey.clipsync.core.protocol.model

import kotlinx.serialization.Serializable

@Serializable
data class DeviceProfile(
    val ownerName: String,
    val platform: String,
    val appVersion: String,
    val protocolVersion: Int,
)
