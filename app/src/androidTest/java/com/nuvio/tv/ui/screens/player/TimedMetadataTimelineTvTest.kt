package com.nuvio.tv.ui.screens.player

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
import com.nuvio.tv.core.player.metadata.*
import com.nuvio.tv.domain.model.NavigationMotion
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimedMetadataTimelineTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val marker = PlayerTimedMarker("real:intro", TimedMetadataKind.INTRO, 0.1f, 0.2f, "Abertura", "introdb")

    @Test fun dpadPreviewAndCommitKeepTheExistingCallbacksWhileShowingTimedEvents() {
        val focus = FocusRequester()
        val deltas = mutableListOf<Long>()
        var commits = 0
        compose.setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(48.dp)) {
                PlayerProgressBar(currentPosition = 20_000, duration = 100_000,
                    timedMarkers = listOf(marker, marker.copy(id = "bookmark", kind = TimedMetadataKind.BOOKMARK, startFraction = .5f, endFraction = null, label = "Momento salvo")),
                    focusRequester = focus, onSeekPreview = { deltas += it }, onSeekCommit = { commits++ })
                LaunchedEffect(Unit) { focus.requestFocusAfterFrames() }
            }
        } }
        compose.onNode(hasContentDescription("Abertura", substring = true)).assertIsDisplayed().assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.runOnIdle { assertEquals(listOf(10_000L), deltas); assertEquals(1, commits) }
        val directory = instrumentation.targetContext.getExternalFilesDir(null)!!
        File(directory, "telumia-timed-timeline-tv.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun changingMediaClearsPreviousTimelineLabels() {
        val markers = mutableStateOf(listOf(marker))
        compose.setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            PlayerProgressBar(0, 100_000, {}, {}, timedMarkers = markers.value)
        } }
        compose.onNode(hasContentDescription("Abertura", substring = true)).assertExists()
        compose.runOnIdle { markers.value = emptyList() }
        compose.onNode(hasContentDescription("Abertura", substring = true)).assertDoesNotExist()
    }

    @Test fun unknownDurationDoesNotDispatchFakeSeek() {
        val focus = FocusRequester()
        var calls = 0
        compose.setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            PlayerProgressBar(0, 0, { calls++ }, { calls++ }, focusRequester = focus)
            LaunchedEffect(Unit) { focus.requestFocusAfterFrames() }
        } }
        compose.waitForIdle()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.runOnIdle { assertEquals(0, calls) }
    }
}
