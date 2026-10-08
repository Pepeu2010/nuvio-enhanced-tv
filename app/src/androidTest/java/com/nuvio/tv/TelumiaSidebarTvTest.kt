package com.nuvio.tv

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.domain.model.NavigationMotion
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import dev.chrisbanes.haze.rememberHazeState
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TelumiaSidebarTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun fullMotionKeepsNativeDpadSelection() = exercise(NavigationMotion.FULL)
    @Test fun reducedMotionKeepsNativeDpadSelection() = exercise(NavigationMotion.REDUCED)
    @Test fun disabledMotionKeepsNativeDpadSelection() = exercise(NavigationMotion.OFF)

    private fun exercise(mode: NavigationMotion) {
        val items = listOf("Início", "Buscar", "Minha biblioteca", "Configurações")
            .mapIndexed { i, label -> DrawerItem("route-$i", label, icon = Icons.Default.Home) }
        val selected = mutableStateOf(items.first().route)
        val focus = items.associate { it.route to FocusRequester() }
        compose.setContent { NuvioTheme(navigationMotion = mode) {
            Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
                Box(Modifier.width(280.dp).fillMaxHeight().padding(8.dp)) {
                    ModernSidebarBlurPanel(items, selected.value, true, 1f, 1f, 1f,
                        true, false, false, rememberHazeState(), RoundedCornerShape(18.dp), focus,
                        onDrawerItemFocused = {}, onDrawerItemClick = { selected.value = it },
                        activeProfileName = "", activeProfileColorHex = "#F0A46C", activeProfileAvatarImageUrl = null,
                        showProfileSelector = false, onSwitchProfile = {})
                }
                LaunchedEffect(Unit) { focus.getValue(items.first().route).requestFocusAfterFrames() }
            }
        } }
        compose.onNodeWithText(items.first().label).assertIsFocused()
        for (item in items.drop(1)) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithText(item.label).assertIsFocused()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText(item.label).assertIsSelected()
            compose.runOnIdle { check(selected.value == item.route) }
        }
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "telumia-sidebar-${mode.name.lowercase()}-tv.png")
        output.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
