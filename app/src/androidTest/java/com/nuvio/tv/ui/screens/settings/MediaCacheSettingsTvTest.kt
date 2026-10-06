package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.storage.*
import com.nuvio.tv.domain.model.NavigationMotion
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaCacheSettingsTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun remoteSelectsManualBudgetAndAutoWithoutChangingTheActiveBudget() {
        val focus = FocusRequester()
        val settings = mutableStateOf(MediaCacheSettings(MediaCacheMode.AUTO, 256L * MIB))
        compose.setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(24.dp)) {
                SettingsGroupCard { MediaCacheSettingsRows(settings.value,
                    MediaCacheBudget(256L * MIB, 256L * MIB, emptySet()), initialFocusRequester = focus) { settings.value = it } }
                LaunchedEffect(Unit) { focus.requestFocusAfterFrames() }
            }
        } }
        compose.waitForIdle()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(MediaCacheMode.MANUAL, settings.value.mode) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.runOnIdle { assertEquals(512L * MIB, settings.value.manualBytes) }
        compose.onNodeWithText("512 MiB").assertIsDisplayed()
        File(context.getExternalFilesDir(null), "telumia-cache-settings-tv.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(MediaCacheMode.AUTO, settings.value.mode); assertEquals(512L * MIB, settings.value.manualBytes) }
        compose.onNodeWithText("512 MiB").assertDoesNotExist()
    }

    @Test fun manualPreferenceSurvivesAStoreRecreationAndAutoKeepsTheChosenValue() {
        val cache = TvMediaCache(context)
        val previous = cache.loadSettings()
        try {
            assertTrue(cache.saveSettings(MediaCacheSettings(MediaCacheMode.MANUAL, 2048L * MIB)))
            val reopened = TvMediaCache(context).loadSettings()
            assertEquals(MediaCacheMode.MANUAL, reopened.mode)
            assertEquals(2048L * MIB, reopened.manualBytes)
            assertTrue(cache.saveSettings(reopened.copy(mode = MediaCacheMode.AUTO)))
            assertEquals(2048L * MIB, TvMediaCache(context).loadSettings().manualBytes)
        } finally { assertTrue(cache.saveSettings(previous)) }
    }

    @Test fun futureSchemaFallsBackSafelyWithoutErasingThePreferenceFile() {
        val preferences = context.getSharedPreferences("telumia_media_cache_v1", Context.MODE_PRIVATE)
        val cache = TvMediaCache(context)
        val previous = cache.loadSettings()
        try {
            assertTrue(preferences.edit().putInt("schema", 999).putString("mode", "FUTURE").commit())
            assertEquals(MediaCacheSettings(), TvMediaCache(context).loadSettings())
            assertEquals(999, preferences.getInt("schema", 0))
        } finally { assertTrue(cache.saveSettings(previous)) }
    }
}
