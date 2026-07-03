package com.nishantattrey.clipsync.core.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.nishantattrey.clipsync.core.local.model.LocalSettings
import com.nishantattrey.clipsync.core.local.model.RetentionPeriod
import com.nishantattrey.clipsync.core.local.proto.ClipboardSensitivityProto
import com.nishantattrey.clipsync.core.local.proto.LocalSettingsProto
import com.nishantattrey.clipsync.core.local.proto.RetentionPeriodProto
import com.nishantattrey.clipsync.core.local.proto.copy
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object LocalSettingsSerializer : Serializer<LocalSettingsProto> {
    override val defaultValue: LocalSettingsProto = LocalSettingsProto.getDefaultInstance()
    override suspend fun readFrom(input: InputStream): LocalSettingsProto = LocalSettingsProto.parseFrom(input)
    override suspend fun writeTo(t: LocalSettingsProto, output: OutputStream) = t.writeTo(output)
}

interface LocalSettingsRepository {
    val settings: Flow<LocalSettings>
    suspend fun setRetentionPeriod(period: RetentionPeriod)
    suspend fun setMarkCopiedTextSensitive(enabled: Boolean)
}

class ProtoLocalSettingsRepository(
    private val dataStore: DataStore<LocalSettingsProto>,
) : LocalSettingsRepository {
    override val settings: Flow<LocalSettings> = dataStore.data.map(::toModel)

    override suspend fun setRetentionPeriod(period: RetentionPeriod) {
        dataStore.updateData { current -> current.copy { textRetention = period.toProto() } }
    }

    override suspend fun setMarkCopiedTextSensitive(enabled: Boolean) {
        dataStore.updateData { current ->
            current.copy {
                copyBackSensitivity = if (enabled) {
                    ClipboardSensitivityProto.CLIPBOARD_SENSITIVITY_SENSITIVE
                } else {
                    ClipboardSensitivityProto.CLIPBOARD_SENSITIVITY_STANDARD
                }
            }
        }
    }

    private fun toModel(proto: LocalSettingsProto): LocalSettings = LocalSettings(
        retentionPeriod = when (proto.textRetention) {
            RetentionPeriodProto.RETENTION_PERIOD_ONE_HOUR -> RetentionPeriod.ONE_HOUR
            RetentionPeriodProto.RETENTION_PERIOD_SIX_HOURS -> RetentionPeriod.SIX_HOURS
            RetentionPeriodProto.RETENTION_PERIOD_ONE_DAY -> RetentionPeriod.ONE_DAY
            RetentionPeriodProto.RETENTION_PERIOD_SEVEN_DAYS -> RetentionPeriod.SEVEN_DAYS
            RetentionPeriodProto.RETENTION_PERIOD_THIRTY_DAYS -> RetentionPeriod.THIRTY_DAYS
            else -> RetentionPeriod.NEVER
        },
        markCopiedTextSensitive = proto.copyBackSensitivity !=
            ClipboardSensitivityProto.CLIPBOARD_SENSITIVITY_STANDARD,
    )

    private fun RetentionPeriod.toProto(): RetentionPeriodProto = when (this) {
        RetentionPeriod.NEVER -> RetentionPeriodProto.RETENTION_PERIOD_NEVER
        RetentionPeriod.ONE_HOUR -> RetentionPeriodProto.RETENTION_PERIOD_ONE_HOUR
        RetentionPeriod.SIX_HOURS -> RetentionPeriodProto.RETENTION_PERIOD_SIX_HOURS
        RetentionPeriod.ONE_DAY -> RetentionPeriodProto.RETENTION_PERIOD_ONE_DAY
        RetentionPeriod.SEVEN_DAYS -> RetentionPeriodProto.RETENTION_PERIOD_SEVEN_DAYS
        RetentionPeriod.THIRTY_DAYS -> RetentionPeriodProto.RETENTION_PERIOD_THIRTY_DAYS
    }
}
