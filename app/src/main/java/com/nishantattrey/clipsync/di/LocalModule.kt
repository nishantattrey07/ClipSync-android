package com.nishantattrey.clipsync.di

import android.content.ClipboardManager
import android.content.Context
import com.nishantattrey.clipsync.core.local.capture.ClipboardGateway
import com.nishantattrey.clipsync.core.local.capture.ForegroundFocusState
import com.nishantattrey.clipsync.core.local.capture.FocusedClipboardImportUseCase
import com.nishantattrey.clipsync.core.local.capture.TextCaptureUseCase
import com.nishantattrey.clipsync.core.local.repository.LocalClipboardRepository
import com.nishantattrey.clipsync.core.local.repository.LocalRecoveryCoordinator
import com.nishantattrey.clipsync.core.local.repository.LocalRecoveryManager
import com.nishantattrey.clipsync.core.local.repository.LocalStore
import com.nishantattrey.clipsync.core.local.repository.createLocalStore
import com.nishantattrey.clipsync.core.local.settings.LocalSettingsRepository
import com.nishantattrey.clipsync.core.local.settings.createLocalSettingsRepository
import com.nishantattrey.clipsync.platform.ActivityFocusState
import com.nishantattrey.clipsync.platform.AndroidClipboardGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocalModule {
    @Provides @Singleton fun store(@ApplicationContext context: Context): LocalStore = createLocalStore(context)
    @Provides @Singleton fun repository(store: LocalStore): LocalClipboardRepository = store.repository

    @Provides @Singleton fun settings(@ApplicationContext context: Context): LocalSettingsRepository =
        createLocalSettingsRepository(context)

    @Provides @Singleton fun clipboardManager(@ApplicationContext context: Context): ClipboardManager =
        context.getSystemService(ClipboardManager::class.java)
    @Provides @Singleton fun clipboard(manager: ClipboardManager): ClipboardGateway = AndroidClipboardGateway(manager)
    @Provides @Singleton fun focusState(impl: ActivityFocusState): ForegroundFocusState = impl
    @Provides fun capture(repository: LocalClipboardRepository) = TextCaptureUseCase(repository)
    @Provides fun importer(clipboard: ClipboardGateway, focus: ForegroundFocusState, capture: TextCaptureUseCase) =
        FocusedClipboardImportUseCase(clipboard, focus, capture)
    @Provides fun recoveryManager(store: LocalStore): LocalRecoveryManager = store.recovery
}
