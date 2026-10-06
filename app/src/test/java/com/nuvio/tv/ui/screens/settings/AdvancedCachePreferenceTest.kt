package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.runtime.AppRestarter
import com.nuvio.tv.core.storage.*
import com.nuvio.tv.data.local.*
import io.mockk.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AdvancedCachePreferenceTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun failedSaveKeepsTheCurrentPreferenceAndRetryPersistsWithoutChangingActiveQuota() = runTest {
        val layout = mockk<LayoutPreferenceDataStore> {
            every { fastHorizontalNavigationEnabled } returns emptyFlow()
            every { smoothBringIntoViewEnabled } returns emptyFlow()
            every { composeHighlighterEnabled } returns emptyFlow()
        }
        val players = mockk<PlayerSettingsDataStore> { every { playerSettings } returns emptyFlow() }
        val device = mockk<DeviceLocalPlayerPreferences> { every { playerStatsHudEnabled } returns emptyFlow() }
        val reports = mockk<SentrySettingsDataStore> { every { enabled } returns emptyFlow() }
        val images = mockk<ImagePerformancePreferences> { every { rgb565Enabled } returns true }
        val budget = MediaCacheBudget(256L * MIB, 256L * MIB, emptySet())
        val cache = mockk<TvMediaCache> {
            every { loadSettings() } returns MediaCacheSettings()
            every { activeBudget } returns budget
            every { saveSettings(any()) } returns false
        }
        val restart = mockk<AppRestarter>(relaxed = true)
        val viewModel = AdvancedSettingsViewModel(layout, players, device, reports, images, restart, cache)
        val store = ViewModelStore().apply { put("cache-settings", viewModel) }
        val manual = MediaCacheSettings(MediaCacheMode.MANUAL, 2048L * MIB)
        try {
            viewModel.onEvent(AdvancedSettingsEvent.SetMediaCache(manual))
            val failed = viewModel.uiState.first { !it.cacheSaving }
            assertTrue(failed.cacheSaveFailed)
            assertEquals(MediaCacheSettings(), failed.cacheSettings)
            every { cache.saveSettings(manual) } returns true
            viewModel.onEvent(AdvancedSettingsEvent.SetMediaCache(manual))
            val saved = viewModel.uiState.first { !it.cacheSaving }
            assertFalse(saved.cacheSaveFailed)
            assertEquals(manual, saved.cacheSettings)
            assertEquals(budget, saved.cacheBudget)
            verify(exactly = 2) { cache.saveSettings(manual) }
            verify(exactly = 0) { restart.restart() }
        } finally { store.clear() }
    }
}
