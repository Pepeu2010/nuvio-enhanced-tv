package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.NavigationMotion
import com.nuvio.tv.domain.model.AnimationIntensity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ThemeDataStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun navigationMotionIsPersistedPerProfileAndSwitchingDoesNotInheritAnotherProfilesChoice() = runTest {
        val activeProfile = MutableStateFlow(1)
        val manager = mockk<ProfileManager> { every { activeProfileId } returns activeProfile }
        val stores = (1..2).associateWith { id ->
            PreferenceDataStoreFactory.create(scope = backgroundScope) {
                directory.root.resolve("theme-$id.preferences_pb")
            }
        }
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), ThemeDataStore.LOCAL_FEATURE) } answers { stores.getValue(firstArg()) }
        }
        val theme = ThemeDataStore(factory, manager)
        assertEquals(NavigationMotion.FULL, theme.navigationMotion.first())
        assertEquals(AnimationIntensity.STANDARD, theme.animationIntensity.first())
        theme.setAnimationIntensity(AnimationIntensity.CINEMATIC)
        theme.setNavigationMotion(NavigationMotion.OFF)
        assertEquals(NavigationMotion.OFF, theme.navigationMotion.first())
        activeProfile.value = 2
        assertEquals(AnimationIntensity.STANDARD, theme.animationIntensity.first())
        theme.setAnimationIntensity(AnimationIntensity.SUBTLE)
        assertEquals(NavigationMotion.FULL, theme.navigationMotion.first())
        theme.setNavigationMotion(NavigationMotion.REDUCED)
        activeProfile.value = 1
        assertEquals(AnimationIntensity.CINEMATIC, theme.animationIntensity.first())
        assertEquals(AnimationIntensity.CINEMATIC, ThemeDataStore(factory, manager).animationIntensity.first())
        assertEquals(NavigationMotion.OFF, theme.navigationMotion.first())
        assertEquals(NavigationMotion.OFF, ThemeDataStore(factory, manager).navigationMotion.first())
    }
}
