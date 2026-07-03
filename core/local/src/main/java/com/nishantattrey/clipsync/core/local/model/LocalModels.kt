package com.nishantattrey.clipsync.core.local.model

data class LocalClipboardItem(
    val id: String,
    val text: String,
    val createdAtEpochMillis: Long,
    val captureSource: CaptureSource,
    val isBookmarked: Boolean,
    val cloudSyncState: String = "local",
)

enum class CaptureSource { SHARE, FOCUSED_IMPORT, COMPOSER, CLOUD }

enum class RetentionPeriod(val durationMillis: Long?) {
    NEVER(null),
    ONE_HOUR(60L * 60 * 1_000),
    SIX_HOURS(6L * 60 * 60 * 1_000),
    ONE_DAY(24L * 60 * 60 * 1_000),
    SEVEN_DAYS(7L * 24 * 60 * 60 * 1_000),
    THIRTY_DAYS(30L * 24 * 60 * 60 * 1_000),
}

data class LocalSettings(
    val retentionPeriod: RetentionPeriod = RetentionPeriod.NEVER,
    val markCopiedTextSensitive: Boolean = true,
)

sealed interface LocalRecoveryState {
    data object Ready : LocalRecoveryState
    data class MissingKeys(val aliases: Set<LocalKeyPurpose>) : LocalRecoveryState
    data class InvalidatedKeys(val aliases: Set<LocalKeyPurpose>) : LocalRecoveryState
    data class TemporarilyUnavailable(val reason: String) : LocalRecoveryState
}

enum class LocalKeyPurpose { PAYLOAD_ENCRYPTION, DEDUP_FINGERPRINT }

sealed interface CaptureResult {
    data class Stored(val id: String) : CaptureResult
    data class Duplicate(val id: String) : CaptureResult
}

sealed interface LocalDataResult<out T> {
    data class Success<T>(val value: T) : LocalDataResult<T>
    data class RecoveryRequired(val state: LocalRecoveryState) : LocalDataResult<Nothing>
    data class CorruptItem(val id: String) : LocalDataResult<Nothing>
}

class EmptyCaptureException : IllegalArgumentException("Captured text is empty.")
class OversizedCaptureException : IllegalArgumentException("Captured text exceeds the local UTF-8 limit.")
