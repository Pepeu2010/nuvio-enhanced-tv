package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeAccessTest {
    @Test
    fun intensityChangesFullFeedbackWhileOffAndReducedKeepTheirSafetyRules() {
        val standard = com.nuvio.tv.ui.theme.NuvioMotion.tokensFor(NavigationMotion.FULL)
        val subtle = com.nuvio.tv.ui.theme.NuvioMotion.tokensFor(UiMotionPolicy(intensity = AnimationIntensity.SUBTLE))
        val cinematic = com.nuvio.tv.ui.theme.NuvioMotion.tokensFor(UiMotionPolicy(intensity = AnimationIntensity.CINEMATIC))
        assertTrue(subtle.durations.fast < standard.durations.fast)
        assertTrue(cinematic.durations.fast > standard.durations.fast)
        assertTrue(subtle.focusScale < standard.focusScale)
        for (intensity in AnimationIntensity.entries) {
            val off = com.nuvio.tv.ui.theme.NuvioMotion.tokensFor(UiMotionPolicy(NavigationMotion.OFF, intensity))
            val reduced = com.nuvio.tv.ui.theme.NuvioMotion.tokensFor(UiMotionPolicy(NavigationMotion.REDUCED, intensity))
            assertEquals(0, off.durations.medium)
            assertEquals(0, off.durations.shimmer)
            assertEquals(1f, off.focusScale)
            assertEquals(1f, reduced.focusScale)
            assertTrue(reduced.durations.sidebarPanelIn <= 120)
        }
        assertEquals(AnimationIntensity.STANDARD, AnimationIntensity.fromName("unknown"))
    }

    @Test
    fun shellPolicyDisablesSpatialFocusWithoutRemovingFeedbackOrChangingFullDefaults() {
        val full = com.nuvio.tv.ui.theme.NuvioMotion.tokensFor(NavigationMotion.FULL)
        val off = com.nuvio.tv.ui.theme.NuvioMotion.tokensFor(NavigationMotion.OFF)
        val reduced = com.nuvio.tv.ui.theme.NuvioMotion.tokensFor(NavigationMotion.REDUCED)
        assertEquals(240, full.durations.medium)
        assertEquals(0, off.durations.sidebarPanelIn)
        assertEquals(0, off.durations.sidebarPanelOut)
        assertEquals(0, off.durations.fast)
        assertEquals(0, off.durations.hero)
        assertEquals(0, off.durations.shimmer)
        assertEquals(1f, off.focusScale)
        assertEquals(1f, reduced.focusScale)
        assertEquals(1f, reduced.pressedScale)
        assertEquals(120, reduced.durations.sidebarPanelIn)
        assertEquals(full.easings, off.easings)
    }

    @Test
    fun reducedNavigationIsNonSpatialAndCorruptStoredPreferencesKeepWorking() {
        assertFalse(NavigationMotion.REDUCED.allowsSpatialEffects)
        assertEquals(120, NavigationMotion.REDUCED.durationMillis(700))
        assertEquals(80, NavigationMotion.REDUCED.durationMillis(80))
        assertEquals(0, NavigationMotion.OFF.durationMillis(700))
        assertEquals(NavigationMotion.FULL, NavigationMotion.fromName(null))
        assertEquals(NavigationMotion.FULL, NavigationMotion.fromName("invalid"))
        assertEquals(NavigationMotion.OFF, NavigationMotion.fromName("OFF"))
    }

    @Test
    fun customThemesAreAvailableWithoutMembership() {
        val themes = availableAppThemes(CosmeticEntitlements.None)

        assertEquals(AppTheme.CUSTOM, themes.first())
        assertEquals(AppTheme.CUSTOM, resolveAppTheme(AppTheme.CUSTOM, CosmeticEntitlements.None))
        assertEquals(AppTheme.WHITE, resolveAppTheme(null, CosmeticEntitlements.None))
        assertEquals(themes.size, themes.distinct().size)
    }

    @Test
    fun onlyMembersCanApplyMultipleCustomColors() {
        val gradient = CustomThemeColors(0xFF0000, 0x00FF00, 0x0000FF)
        val solid = CustomThemeColors.solid(0x00FF00)

        assertEquals(solid, resolveCustomThemeColors(gradient, null))
        assertEquals(solid, resolveCustomThemeColors(solid, null))
        MemberTier.entries.forEach { tier ->
            assertEquals(gradient, resolveCustomThemeColors(gradient, tier))
            assertEquals(solid, resolveCustomThemeColors(solid, tier))
        }
    }

    @Test
    fun standardUsersCannotAccessSupporterThemes() {
        val availableThemes = availableAppThemes(CosmeticEntitlements.None)

        assertFalse(AppTheme.GOLD in availableThemes)
        assertFalse(AppTheme.JADE in availableThemes)
        assertFalse(AppTheme.ROSE_GOLD in availableThemes)
        assertFalse(AppTheme.ARCTIC_BLUE in availableThemes)
        assertFalse(AppTheme.GRAPHITE in availableThemes)
        assertEquals(AppTheme.WHITE, resolveAppTheme(AppTheme.GOLD, CosmeticEntitlements.None))
    }

    @Test
    fun supporterAccessUnlocksAllThemesAndDefaultsToGold() {
        val entitlements = CosmeticEntitlements.SupporterPreview
        val availableThemes = availableAppThemes(entitlements)

        assertTrue(AppTheme.GOLD in availableThemes)
        assertTrue(AppTheme.JADE in availableThemes)
        assertTrue(AppTheme.ROSE_GOLD in availableThemes)
        assertTrue(AppTheme.ARCTIC_BLUE in availableThemes)
        assertTrue(AppTheme.GRAPHITE in availableThemes)
        assertEquals(AppTheme.GOLD, resolveAppTheme(null, entitlements))
    }

    @Test
    fun individualThemeEntitlementsCanBeGrantedSeparately() {
        val entitlements = CosmeticEntitlements(
            unlocked = setOf(CosmeticEntitlement.ARCTIC_BLUE_THEME)
        )

        assertTrue(AppTheme.ARCTIC_BLUE in availableAppThemes(entitlements))
        assertFalse(AppTheme.GOLD in availableAppThemes(entitlements))
        assertEquals(AppTheme.ARCTIC_BLUE, resolveAppTheme(null, entitlements))
    }

    @Test
    fun explicitThemeSelectionIsPreservedForSupporters() {
        assertEquals(
            AppTheme.OCEAN,
            resolveAppTheme(AppTheme.OCEAN, CosmeticEntitlements.SupporterPreview)
        )
    }
}
