package com.nishantattrey.clipsync.local

import com.nishantattrey.clipsync.core.local.capture.ClipboardGateway
import com.nishantattrey.clipsync.core.local.capture.FocusedClipboardImportUseCase
import com.nishantattrey.clipsync.core.local.capture.TextCaptureUseCase
import com.nishantattrey.clipsync.core.local.model.CaptureResult
import com.nishantattrey.clipsync.core.local.model.CaptureSource
import com.nishantattrey.clipsync.core.local.model.LocalClipboardItem
import com.nishantattrey.clipsync.core.local.model.LocalDataResult
import com.nishantattrey.clipsync.core.local.model.LocalRecoveryState
import com.nishantattrey.clipsync.core.local.model.LocalSettings
import com.nishantattrey.clipsync.core.local.model.RetentionPeriod
import com.nishantattrey.clipsync.core.local.repository.LocalClipboardRepository
import com.nishantattrey.clipsync.core.local.repository.LocalRecoveryManager
import com.nishantattrey.clipsync.core.local.settings.LocalSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalClipboardViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `new search cancels older search result`() = runTest(dispatcher) {
        val repository = ViewModelRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.setQuery("old")
        advanceTimeBy(200)
        viewModel.setQuery("new")
        advanceUntilIdle()

        assertEquals("new", viewModel.state.value.query)
        assertEquals(listOf("new"), viewModel.state.value.items.map { it.text })
    }

    @Test fun `load more appends second deterministic history page`() = runTest(dispatcher) {
        val repository = ViewModelRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        repository.changesSignal.emit(1)
        advanceUntilIdle()
        assertEquals(100, viewModel.state.value.items.size)
        assertTrue(viewModel.state.value.canLoadMore)

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(200, viewModel.state.value.items.size)
    }

    private fun viewModel(repository: ViewModelRepository): LocalClipboardViewModel {
        val clipboard = object : ClipboardGateway {
            override fun readText(): String? = null
            override fun writeText(text: String, sensitive: Boolean) = Unit
        }
        return LocalClipboardViewModel(
            repository,
            TextCaptureUseCase(repository),
            FocusedClipboardImportUseCase(clipboard, { true }, TextCaptureUseCase(repository)),
            clipboard,
            ViewModelSettingsRepository(),
            object : LocalRecoveryManager {
                override suspend fun state() = LocalRecoveryState.Ready
                override suspend fun resetEncryptedHistory(confirmed: Boolean) = LocalRecoveryState.Ready
            },
        )
    }
}

private class ViewModelRepository : LocalClipboardRepository {
    val changesSignal = MutableSharedFlow<Int>()
    override val changes: Flow<Int> = changesSignal
    private val history = (200 downTo 1).map { index -> item("item-$index", index.toLong()) }

    override suspend fun capture(text: String, source: CaptureSource): LocalDataResult<CaptureResult> =
        LocalDataResult.Success(CaptureResult.Stored("captured"))
    override suspend fun storeInbound(id: String, text: String, createdAtEpochMillis: Long): LocalDataResult<Boolean> =
        LocalDataResult.Success(true)

    override suspend fun page(
        bookmarksOnly: Boolean,
        before: LocalClipboardItem?,
        limit: Int,
    ): LocalDataResult<List<LocalClipboardItem>> {
        val start = before?.let { item -> history.indexOfFirst { it.id == item.id } + 1 } ?: 0
        return LocalDataResult.Success(history.drop(start).take(limit))
    }

    override suspend fun search(query: String, bookmarksOnly: Boolean): LocalDataResult<List<LocalClipboardItem>> {
        if (query == "old") delay(1_000)
        return LocalDataResult.Success(listOf(item(query, 1)))
    }

    override suspend fun find(id: String): LocalDataResult<LocalClipboardItem?> = LocalDataResult.Success(null)
    override suspend fun setBookmarked(id: String, bookmarked: Boolean) = true
    override suspend fun delete(id: String) = true
    override suspend fun clearUnbookmarked() = 0
    override suspend fun applyRetention(period: RetentionPeriod) = 0

    private fun item(text: String, time: Long) = LocalClipboardItem(
        id = text,
        text = text,
        createdAtEpochMillis = time,
        captureSource = CaptureSource.COMPOSER,
        isBookmarked = false,
    )
}

private class ViewModelSettingsRepository : LocalSettingsRepository {
    private val mutableSettings = MutableStateFlow(LocalSettings())
    override val settings: Flow<LocalSettings> = mutableSettings
    override suspend fun setTextRetentionPeriod(period: RetentionPeriod) {
        mutableSettings.value = mutableSettings.value.copy(textRetentionPeriod = period)
    }
    override suspend fun setImageRetentionPeriod(period: RetentionPeriod) {
        mutableSettings.value = mutableSettings.value.copy(imageRetentionPeriod = period)
    }
    override suspend fun setDeviceAlias(deviceId: String, alias: String?) {
        mutableSettings.value = mutableSettings.value.copy(
            deviceAliases = mutableSettings.value.deviceAliases.toMutableMap().apply {
                if (alias == null) remove(deviceId) else put(deviceId, alias)
            },
        )
    }
    override suspend fun setMarkCopiedTextSensitive(enabled: Boolean) {
        mutableSettings.value = mutableSettings.value.copy(markCopiedTextSensitive = enabled)
    }
}
