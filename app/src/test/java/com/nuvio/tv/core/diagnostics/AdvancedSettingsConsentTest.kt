package com.nuvio.tv.core.diagnostics

import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.runtime.AppRestarter
import com.nuvio.tv.data.local.DeviceLocalPlayerPreferences
import com.nuvio.tv.data.local.ImagePerformancePreferences
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.SentrySettingsDataStore
import com.nuvio.tv.ui.screens.settings.AdvancedSettingsEvent
import com.nuvio.tv.ui.screens.settings.AdvancedSettingsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AdvancedSettingsConsentTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun consentStaysOffUntilLoadedAndExplicitChangesPersist() = runTest {
        val consent = MutableSharedFlow<Boolean>(replay = 1)
        val layout = mockk<LayoutPreferenceDataStore> {
            every { fastHorizontalNavigationEnabled } returns emptyFlow()
            every { smoothBringIntoViewEnabled } returns emptyFlow()
            every { composeHighlighterEnabled } returns emptyFlow()
        }
        val players = mockk<PlayerSettingsDataStore> {
            every { playerSettings } returns emptyFlow()
        }
        val device = mockk<DeviceLocalPlayerPreferences> {
            every { playerStatsHudEnabled } returns emptyFlow()
        }
        val reports = mockk<SentrySettingsDataStore> {
            every { enabled } returns consent
            coEvery { setEnabled(any()) } coAnswers { consent.emit(firstArg()) }
        }
        val images = mockk<ImagePerformancePreferences> {
            every { rgb565Enabled } returns true
        }
        val viewModel = AdvancedSettingsViewModel(layout, players, device, reports, images, mockk<AppRestarter>())
        val store = ViewModelStore().apply { put("settings", viewModel) }
        try {
            assertFalse(viewModel.uiState.value.sentryEnabled)
            runCurrent()
            assertFalse(viewModel.uiState.value.sentryEnabled)
            consent.emit(true)
            runCurrent()
            assertTrue(viewModel.uiState.value.sentryEnabled)
            viewModel.onEvent(AdvancedSettingsEvent.SetSentryEnabled(false))
            runCurrent()
            assertFalse(viewModel.uiState.value.sentryEnabled)
            coVerify(exactly = 1) { reports.setEnabled(false) }
        } finally {
            store.clear()
        }
    }
}
