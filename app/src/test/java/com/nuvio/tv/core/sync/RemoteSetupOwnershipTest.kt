package com.nuvio.tv.core.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.ServerConfiguration
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test

class RemoteSetupOwnershipTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        var beforeWrite: () -> Unit = {}
        var writes = 0
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            beforeWrite()
            val next = transform(data.value)
            data.value = next
            writes++
            return next
        }
    }

    @Test fun addonNamesAndEnabledStatesPublishTogetherToTheCapturedProfile() = runBlocking {
        val memory = MemoryStore()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(2, "addon_preferences") } returns memory
        val preferences = AddonPreferences(factory, mockk<ProfileManager>(relaxed = true))
        preferences.applyRemoteMetadata(2, mapOf("https://fixture.invalid/manifest.json" to "Brasil"),
            mapOf("https://fixture.invalid/manifest.json" to false)) { true }
        assertEquals(1, memory.writes)
        assertTrue(memory.data.value[stringPreferencesKey("addon_user_set_names")]!!.contains("Brasil"))
        assertTrue(memory.data.value[stringPreferencesKey("installed_addon_enabled_states")]!!.contains("false"))
        verify(exactly = 1) { factory.get(2, "addon_preferences") }
    }

    @Test fun ownershipChangeAtTheTransactionLeavesAddonPreferencesUntouched() = runBlocking {
        val memory = MemoryStore()
        var current = true
        memory.beforeWrite = { current = false }
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(2, "addon_preferences") } returns memory
        val preferences = AddonPreferences(factory, mockk<ProfileManager>(relaxed = true))
        var cancelled = false
        try { preferences.applyRemoteMetadata(2, mapOf("fixture" to "name"), emptyMap()) { current } }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(0, memory.writes)
        assertEquals(emptyPreferences(), memory.data.value)
    }

    @Test fun ownershipChangeAtTheTransactionLeavesCollectionsUntouched() = runBlocking {
        val memory = MemoryStore()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(3, "collections") } returns memory
        val account = MutableStateFlow<AuthState>(AuthState.FullAccount("fixture", "fixture@invalid.test"))
        val auth = mockk<AuthManager> { every { authState } returns account }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(3) }
        val config = mockk<ServerConfiguration> { every { backendUrl } returns "https://fixture.invalid" }
        val store = CollectionsDataStore(mockk(relaxed = true), factory, profiles, auth, config)
        val owner = store.captureSyncOwner()!!
        memory.beforeWrite = { account.value = AuthState.SignedOut }
        var cancelled = false
        try { store.reconcileRemote(owner, JsonArray(emptyList()), true) }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(0, memory.writes)
        assertEquals(emptyPreferences(), memory.data.value)
    }
}
