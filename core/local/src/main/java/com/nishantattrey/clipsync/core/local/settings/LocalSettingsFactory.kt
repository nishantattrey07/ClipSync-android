package com.nishantattrey.clipsync.core.local.settings

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun createLocalSettingsRepository(context: Context): LocalSettingsRepository =
    ProtoLocalSettingsRepository(
        DataStoreFactory.create(
            serializer = LocalSettingsSerializer,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = {
                File(context.filesDir, "datastore/local_settings.pb").also { it.parentFile?.mkdirs() }
            },
        ),
    )
