package com.nuvio.tv.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.BroadcastFrameClock
import com.nuvio.tv.domain.model.NavigationMotion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.nuvio.tv.domain.model.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusRingStyleTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun runtimeOffFinishesRawAnimationAndPreservesTheSystemDurationScale() = runTest {
        val scale = UiAnimationDurationScale(initialSystemScale = 2f)
        val clock = BroadcastFrameClock()
        val animation = Animatable(0f)
        val job = launch(clock + scale) { animation.animateTo(1f, tween(1_000, easing = LinearEasing)) }
        runCurrent()
        clock.sendFrame(0L)
        runCurrent()
        clock.sendFrame(400_000_000L)
        runCurrent()
        assertTrue(animation.value in 0.15f..0.25f)
        scale.mode = NavigationMotion.OFF
        clock.sendFrame(416_000_000L)
        runCurrent()
        // Compose samples the scale before awaiting a frame. The already queued
        // frame uses its old sample; the following frame observes Off.
        clock.sendFrame(432_000_000L)
        runCurrent()
        assertEquals(1f, animation.value, 0f)
        assertTrue(job.isCompleted)
        scale.mode = NavigationMotion.FULL
        assertEquals(2f, scale.scaleFactor, 0f)
        scale.systemScale = 0f
        val noSystemMotion = launch(clock + scale) { animation.animateTo(0f, tween(1_000)) }
        runCurrent()
        clock.sendFrame(448_000_000L)
        runCurrent()
        assertEquals(0f, animation.value, 0f)
        assertTrue(noSystemMotion.isCompleted)
        for (invalid in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            scale.systemScale = invalid
            assertEquals(1f, scale.scaleFactor, 0f)
        }
    }
    @Test
    fun standardThemeUsesItsSolidFocusColor() {
        val style = createFocusRingStyle(ThemeColors.Ocean)

        assertEquals(Color(0xFF42A5F5), style.solidColor)
        assertTrue(style.brush() is SolidColor)
    }

    @Test
    fun supporterThemesUseGradientFocusRings() {
        val expectedFallbacks = mapOf(
            AppTheme.GOLD to Color(0xFFFFD45C),
            AppTheme.JADE to Color(0xFF7BF08D),
            AppTheme.ROSE_GOLD to Color(0xFFFFB37A),
            AppTheme.ARCTIC_BLUE to Color(0xFF4DE3FF),
            AppTheme.GRAPHITE to Color(0xFFF3F5F7)
        )

        expectedFallbacks.forEach { (theme, expectedFallback) ->
            val style = createFocusRingStyle(ThemeColors.getColorPalette(theme))

            assertEquals(expectedFallback, style.solidColor)
            assertFalse(style.brush() is SolidColor)
        }
    }

    @Test
    fun supporterGradientsMatchTheMobileColorways() {
        val expectedGradients = mapOf(
            AppTheme.JADE to listOf(
                Color(0xFF7BF08D),
                Color(0xFF22D37C),
                Color(0xFF0BBF9A)
            ),
            AppTheme.ROSE_GOLD to listOf(
                Color(0xFFB75AFF),
                Color(0xFFEC70A9),
                Color(0xFFFFB37A)
            ),
            AppTheme.ARCTIC_BLUE to listOf(
                Color(0xFF4DE3FF),
                Color(0xFF3185F5),
                Color(0xFF4D55E8)
            ),
            AppTheme.GRAPHITE to listOf(
                Color(0xFFF3F5F7),
                Color(0xFFAAB2BE),
                Color(0xFF687381)
            )
        )

        expectedGradients.forEach { (theme, expectedGradient) ->
            val palette = ThemeColors.getColorPalette(theme)

            assertEquals(expectedGradient, palette.accentGradient)
            assertEquals(expectedGradient, palette.focusRingGradient)
        }
    }
}
