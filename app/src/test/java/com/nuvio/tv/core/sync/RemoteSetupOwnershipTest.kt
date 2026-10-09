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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.*
import org.junit.Test

class RemoteSetupOwnershipTest {
    private fun addons(factory: ProfileDataStoreFactory, account: MutableStateFlow<AuthState>): AddonPreferences {
        val auth = mockk<AuthManager> { every { authState } returns account }
        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns MutableStateFlow(2)
            every { activeProfile } returns null
            every { profiles } returns MutableStateFlow(emptyList())
        }
        val config = mockk<ServerConfiguration> { every { backendUrl } returns "https://fixture.invalid" }
        return AddonPreferences(factory, profileManager, auth, config)
    }
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
        val preferences = addons(factory, MutableStateFlow(AuthState.FullAccount("fixture", "fixture@invalid.test")))
        preferences.reconcileRemote(preferences.captureSyncOwner()!!,
            Json.parseToJsonElement("""[{"url":"https://fixture.invalid/manifest.json","name":"Brasil","enabled":false}]""").jsonArray)
        assertEquals(1, memory.writes)
        assertTrue(memory.data.value[stringPreferencesKey("addon_user_set_names")]!!.contains("Brasil"))
        assertTrue(memory.data.value[stringPreferencesKey("installed_addon_enabled_states")]!!.contains("false"))
        assertEquals("[\"https://fixture.invalid\"]", memory.data.value[stringPreferencesKey("installed_addon_urls_ordered")])
        assertEquals(1, memory.data.value.asMap().keys.count { it.name.startsWith("sync_journal_v1_") })
        verify(exactly = 1) { factory.get(2, "addon_preferences") }
    }

    @Test fun ownershipChangeAtTheTransactionLeavesAddonPreferencesUntouched() = runBlocking {
        val memory = MemoryStore()
        val account = MutableStateFlow<AuthState>(AuthState.FullAccount("fixture", "fixture@invalid.test"))
        memory.beforeWrite = { account.value = AuthState.SignedOut }
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(2, "addon_preferences") } returns memory
        val preferences = addons(factory, account)
        val owner = preferences.captureSyncOwner()!!
        var cancelled = false
        try { preferences.reconcileRemote(owner, JsonArray(emptyList())) }
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
        val profileManager = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(3) }
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
