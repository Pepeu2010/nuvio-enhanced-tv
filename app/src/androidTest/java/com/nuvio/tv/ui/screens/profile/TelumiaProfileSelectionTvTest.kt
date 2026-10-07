package com.nuvio.tv.ui.screens.profile

import android.graphics.Bitmap
import android.content.res.Configuration
import android.os.LocaleList
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.domain.model.NavigationMotion
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import java.io.File
import java.util.Locale
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the production selector and real remote events; no account/profile writes. */
@RunWith(AndroidJUnit4::class)
class TelumiaProfileSelectionTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val profiles = (1..6).map {
        UserProfile(it, if (it == 5) "Perfil brasileiro com nome longo" else "Perfil $it",
            listOf("#E8BE72", "#9ADAC5", "#F6AEAE", "#9EC9EE", "#D6C7F1", "#B8DBA4")[it-1],
            studioIdentity = "selector-fixture-$it")
    }

    private fun press(key: Int) {
        instrumentation.sendKeyDownUpSync(key)
        compose.waitForIdle()
    }

    private fun capture(name: String = "telumia-profile-selection-tv.png") {
        val context = instrumentation.targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            val frame = instrumentation.uiAutomation.takeScreenshot()
            try { assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            finally { frame.recycle() }
        }
    }

    private fun assertFullyVisible(tag: String) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val rootDp = compose.onRoot().getUnclippedBoundsInRoot()
        val scale = root.width / (rootDp.right - rootDp.left).value
        val node = compose.onNodeWithTag(tag)
        node.assertIsDisplayed()
        val intended = node.getUnclippedBoundsInRoot()
        val visible = node.fetchSemanticsNode().boundsInRoot
        assertTrue("$tag must fit in the viewport", visible.left >= root.left && visible.top >= root.top &&
            visible.right <= root.right && visible.bottom <= root.bottom)
        assertTrue("$tag must not be clipped", visible.width >= (intended.right - intended.left).value * scale - 1f &&
            visible.height >= (intended.bottom - intended.top).value * scale - 1f)
        if (tag.startsWith("tv-profile-card-")) {
            assertTrue("$tag must fit inside a five-percent overscan safe area",
                visible.left >= root.left + root.width * .05f - 1f &&
                    visible.right <= root.right - root.width * .05f + 1f &&
                    visible.top >= root.top + root.height * .05f - 1f &&
                    visible.bottom <= root.bottom - root.height * .05f + 1f)
        }
    }

    @Composable private fun Screen(items: List<UserProfile>, preferred: Int = 1, canAdd: Boolean = false,
        mode: NavigationMotion = NavigationMotion.OFF, onFocused: (UserProfile?) -> Unit = {},
        onSelected: (UserProfile) -> Unit = {}, onEdit: (UserProfile) -> Unit = {}, onAdd: () -> Unit = {},
        background: ProfileBackgroundArtwork? = null) {
        val context = LocalContext.current
        val configuration = remember(context) { Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag("pt-BR")))
        } }
        val localized = remember(context) { context.createConfigurationContext(configuration) }
        var focusedColor by remember { mutableStateOf(Color(0xFFE8BE72)) }
        CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
        NuvioTheme(navigationMotion = mode) {
            Box(Modifier.fillMaxSize().background(Color(0xFF080E18))) {
                ProfileSelectionBackground(focusedColor, background)
                ProfileSelectionMainContent("Quem vai assistir?", "Sua experiência começa aqui",
                    "OK: assistir · Menu: editar", false, items, 1, canAdd, mapOf(3 to true), emptyMap(),
                    onProfileFocused = { profile ->
                        profile?.let { focusedColor = Color(android.graphics.Color.parseColor(it.avatarColorHex)) }
                        onFocused(profile)
                    }, onProfileSelected = onSelected, onProfileLongPress = onEdit,
                    onAddProfileClick = onAdd, preferredFocusId = preferred)
            }
        }
        }
    }

    @Test fun sixProfilesFitAndRealRemoteReachesEveryProfileAndEditAction() {
        val visited = mutableSetOf<Int>()
        var selected: Int? = null
        var edited: Int? = null
        compose.setContent { Screen(profiles, preferred = 3, onFocused = { it?.id?.let(visited::add) },
            onSelected = { selected = it.id }, onEdit = { edited = it.id }) }
        compose.waitForIdle()
        compose.onNodeWithTag("tv-profile-card-3").assertIsFocused()
        profiles.forEach {
            assertFullyVisible("tv-profile-card-${it.id}")
            compose.onNodeWithText(it.name).assertIsDisplayed()
        }
        capture()
        compose.onNodeWithTag("tv-profile-add").assertDoesNotExist()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(3, selected)
        press(KeyEvent.KEYCODE_MENU)
        assertEquals(3, edited)
        // Sweep both row layouts using real D-pad, never request arbitrary node focus.
        repeat(2) {
            repeat(6) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
            repeat(6) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
            press(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        repeat(6) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
        repeat(6) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals((1..6).toSet(), visited)
    }

    @Test fun returningToSelectorRestoresFocusUnderReducedMotion() {
        val mounted = mutableStateOf(true)
        var focused: Int? = null
        compose.setContent { if (mounted.value) Screen(profiles, preferred = 5, mode = NavigationMotion.REDUCED,
            onFocused = { focused = it?.id }) }
        compose.waitForIdle()
        compose.onNodeWithTag("tv-profile-card-5").assertIsFocused()
        compose.runOnIdle { mounted.value = false }
        compose.runOnIdle { mounted.value = true }
        compose.onNodeWithTag("tv-profile-card-5").assertIsFocused()
        assertEquals(5, focused)
        assertFullyVisible("tv-profile-card-5")
    }

    @Test fun emptyProfileListProvidesARealCreateAction() {
        var creates = 0
        compose.setContent { Screen(emptyList(), canAdd = true, onAdd = { creates++ }) }
        compose.waitForIdle()
        compose.onNodeWithTag("tv-profile-add").assertIsFocused()
        assertFullyVisible("tv-profile-add")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, creates)
    }

    @Test fun brightLocalCoverLoadsBehindReadableCardsWithFullMotion() {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "selector-cover-${java.util.UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(384, 216, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            compose.setContent { Screen(profiles, preferred = 2, mode = NavigationMotion.FULL,
                background = ProfileBackgroundArtwork.Custom(file.toURI().toString())) }
            compose.waitForIdle()
            // The white photo must actually load and be darkened, rather than testing a blank fallback.
            compose.waitUntil(10_000) {
                val frame = instrumentation.uiAutomation.takeScreenshot()
                try { android.graphics.Color.red(frame.getPixel(4, 4)) in 55..95 }
                finally { frame.recycle() }
            }
            profiles.forEach {
                assertFullyVisible("tv-profile-card-${it.id}")
                compose.onNodeWithText(it.name).assertIsDisplayed()
            }
            compose.onNodeWithTag("tv-profile-card-2").assertIsFocused()
            press(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithTag("tv-profile-card-1").assertIsFocused()
            assertFullyVisible("tv-profile-card-1")
            val caption = compose.onNodeWithText("Sua experiência começa aqui").fetchSemanticsNode().boundsInRoot
            val frame = instrumentation.uiAutomation.takeScreenshot()
            try {
                val backgroundLight = luminance(frame.getPixel(4, caption.center.y.toInt()))
                var textLight = backgroundLight
                for (y in caption.top.toInt()..caption.bottom.toInt()) {
                    for (x in caption.left.toInt()..caption.right.toInt() step 2) {
                        textLight = maxOf(textLight, luminance(frame.getPixel(x, y)))
                    }
                }
                assertTrue("Rendered caption contrast against the white cover must be at least 4.5:1",
                    (textLight + .05) / (backgroundLight + .05) >= 4.5)
            } finally { frame.recycle() }
            capture("telumia-profile-selection-cover-tv.png")
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            file.delete()
        }
    }

    private fun luminance(pixel: Int): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * linear(android.graphics.Color.red(pixel)) +
            .7152 * linear(android.graphics.Color.green(pixel)) + .0722 * linear(android.graphics.Color.blue(pixel))
    }

    @Test fun missingLocalAvatarKeepsAnInitialAfterTheImageLoaderReportsFailure() {
        val context = instrumentation.targetContext
        var failures = 0
        val missing = File(context.cacheDir, "missing-avatar-${java.util.UUID.randomUUID()}.png")
        compose.setContent {
            NuvioTheme(navigationMotion = NavigationMotion.OFF) {
                Box(Modifier.fillMaxSize().background(Color(0xFF080E18)), contentAlignment = Alignment.Center) {
                    ProfileAvatarCircle("Perfil", "#E8BE72", avatarImageUrl = missing.toURI().toString(),
                        imageCrossfade = false, onImageError = { failures++ })
                }
            }
        }
        compose.waitUntil(10_000) { failures > 0 }
        compose.onNodeWithText("P").assertIsDisplayed()
        assertFalse(missing.exists())
    }
}
