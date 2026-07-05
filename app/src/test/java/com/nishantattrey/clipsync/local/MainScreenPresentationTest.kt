package com.nishantattrey.clipsync.local

import com.nishantattrey.clipsync.core.sync.model.SyncedDevice
import com.nishantattrey.clipsync.sync.SyncUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class MainScreenPresentationTest {
    @Test
    fun `healthy connection shows device count and relative success time`() {
        val state = SyncUiState(
            configured = true,
            status = "Connected",
            devices = listOf(SyncedDevice("id", "Phone", "Android")),
            lastSuccessfulSyncAtEpochMillis = 1_000_000,
        )

        val presentation = connectionPresentation(state, 1_120_000)

        assertEquals(ConnectionVisualState.HEALTHY, presentation.state)
        assertEquals("1 device", presentation.title)
        assertEquals("Updated 2m ago", presentation.detail)
    }

    @Test
    fun `syncing and actionable states use distinct presentation`() {
        val syncing = connectionPresentation(
            SyncUiState(configured = true, status = "Connecting", isBusy = true),
            0,
        )
        val actionable = connectionPresentation(
            SyncUiState(configured = true, status = "Action required", error = "Check credentials"),
            0,
        )

        assertEquals(ConnectionVisualState.SYNCING, syncing.state)
        assertEquals("Syncing", syncing.title)
        assertEquals(ConnectionVisualState.ACTION_REQUIRED, actionable.state)
        assertEquals("Check credentials", actionable.detail)
    }

    @Test
    fun `section headings match navigation destinations`() {
        assertEquals("Local clips", sectionHeading(HistorySection.LOCAL))
        assertEquals("Shared clips", sectionHeading(HistorySection.SHARED))
        assertEquals("Bookmarks", sectionHeading(HistorySection.BOOKMARKS))
    }
}
