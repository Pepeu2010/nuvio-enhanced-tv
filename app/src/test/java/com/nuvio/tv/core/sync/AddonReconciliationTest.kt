package com.nuvio.tv.core.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.*
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AddonReconciliationTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        var beforeWrite: () -> Unit = {}
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            beforeWrite()
            return transform(data.value).also { data.value = it }
        }
    }

    private class Fixture(val disk: DataStore<Preferences> = MemoryStore()) {
        val account = MutableStateFlow<AuthState>(AuthState.FullAccount("fixture-a", "fixture@invalid.test"))
        val profileId = MutableStateFlow(1)
        val allProfiles = MutableStateFlow(listOf(UserProfile(1, "Principal", "#000000"),
            UserProfile(2, "Compartilhado", "#000000", usesPrimaryAddons = true)))
        val manager = mockk<AuthManager> { every { authState } returns account }
        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns profileId
            every { profiles } returns allProfiles
            every { activeProfile } answers { allProfiles.value.firstOrNull { it.id == profileId.value } }
        }
        private val factory = mockk<ProfileDataStoreFactory> { every { get(1, "addon_preferences") } returns disk }
        private val config = ServerConfiguration("https://fixture.invalid", "fixture", ServerCapabilities(false, true), false)
        fun preferences() = AddonPreferences(factory, profileManager, manager, config)
        val preferences = preferences()
        val owner get() = preferences.captureSyncOwner()!!
        fun service(remote: AddonSyncRemote) = AddonSyncService(mockk(relaxed = true), manager, preferences,
            profileManager, mockk(relaxed = true)).also { it.remote = remote }
    }

    private class RemoteFixture(var server: JsonArray) : AddonSyncRemote {
        var offline = false
        var failUpload = false
        var uploads = 0
        var lastProfileId = 0
        var onPull: () -> Unit = {}
        var onUpload: suspend () -> Unit = {}
        override suspend fun pull(profileId: Int, stillCurrent: () -> Boolean): JsonArray {
            check(!offline) { "Offline fixture" }
            lastProfileId = profileId
            onPull()
            return server
        }
        override suspend fun push(profileId: Int, snapshot: JsonArray, stillCurrent: () -> Boolean) {
            check(!offline && !failUpload) { "Upload unavailable" }
            check(stillCurrent())
            uploads++
            onUpload()
            server = snapshot
        }
    }

    private fun array(value: String) = Json.parseToJsonElement(value).jsonArray
    private fun urls(value: JsonArray) = value.map { it.jsonObject["url"]!!.jsonPrimitive.content }
    private fun record(url: String, name: String = "", enabled: Boolean = true) = buildJsonObject {
        put("url", url); put("name", name); put("enabled", enabled)
    }
    private fun snapshot(vararg records: JsonObject) = JsonArray(records.toList())
    private val a = "https://a.fixture.invalid"
    private val b = "https://b.fixture.invalid"
    private val c = "https://c.fixture.invalid"

    @Test fun offlineRemovalAndIndependentRemoteAdditionMergeThroughTheActualService() = runBlocking {
        val f = Fixture()
        val remote = RemoteFixture(snapshot(record(a, "A"), record(b, "B")))
        val service = f.service(remote)
        service.getRemoteAddonUrls().getOrThrow()
        remote.offline = true
        assertTrue(f.preferences.removeAddon(a))
        assertTrue(service.pushToRemote().isFailure)
        remote.offline = false
        remote.server = snapshot(record(a, "A"), record(b, "Renomeado"), record(c, "C"))
        assertEquals(listOf(b, c), service.getRemoteAddonUrls().getOrThrow())
        assertEquals(listOf(b, c), urls(remote.server))
        assertEquals("Renomeado", f.preferences.userSetNames.first()[b])
        assertEquals(1, remote.uploads)
        remote.server = array("[]")
        service.getRemoteAddonUrls().getOrThrow()
        assertTrue(f.preferences.installedAddonUrls.first().isEmpty())
        assertTrue(f.preferences.userSetNames.first().isEmpty())
        assertTrue(f.preferences.addonEnabledStates.first().isEmpty())
        assertEquals(1, remote.uploads)
    }

    @Test fun editDuringUploadRemainsPendingUntilAnotherSuccessfulReconciliation() = runBlocking {
        val f = Fixture()
        val remote = RemoteFixture(snapshot(record(a)))
        val service = f.service(remote)
        service.getRemoteAddonUrls().getOrThrow()
        f.preferences.setUserSetNames(mapOf(a to "Primeiro"))
        remote.onUpload = { f.preferences.setUserSetNames(mapOf(a to "Último")) }
        service.pushToRemote().getOrThrow()
        assertEquals("Primeiro", remote.server.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("Último", f.preferences.userSetNames.first()[a])
        remote.onUpload = {}
        service.getRemoteAddonUrls().getOrThrow()
        assertEquals("Último", remote.server.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(2, remote.uploads)
    }

    @Test fun failedUploadKeepsMergedDataAndPendingEditsForTheNextRefresh() = runBlocking {
        val f = Fixture()
        val remote = RemoteFixture(snapshot(record(a)))
        val service = f.service(remote)
        service.getRemoteAddonUrls().getOrThrow()
        f.preferences.setAddonEnabled(a, false)
        remote.server = snapshot(record(a), record(b))
        remote.failUpload = true
        assertTrue(service.pushToRemote().isFailure)
        assertEquals(listOf(a, b), f.preferences.installedAddonUrls.first())
        remote.failUpload = false
        service.getRemoteAddonUrls().getOrThrow()
        assertEquals(listOf(a, b), urls(remote.server))
        assertEquals(JsonPrimitive(false), remote.server.first().jsonObject["enabled"])
        assertEquals(1, remote.uploads)
    }

    @Test fun reorderPreservesIndependentRenameAndAssignsUniqueWirePositions() = runBlocking {
        val f = Fixture()
        val remote = RemoteFixture(snapshot(record(a), record(b)))
        val service = f.service(remote)
        service.getRemoteAddonUrls().getOrThrow()
        f.preferences.setAddonOrder(listOf(b, a))
        remote.server = snapshot(record(a, "Remoto"), record(b), record(c))
        service.pushToRemote().getOrThrow()
        assertEquals(listOf(b, a, c), urls(remote.server))
        assertEquals("Remoto", f.preferences.userSetNames.first()[a])
        assertEquals(listOf(0, 1, 2), remote.server.map { it.jsonObject["sort_order"]!!.jsonPrimitive.int })
    }

    @Test fun configurationQueriesKeepCaseAndDisabledCustomNamesSurviveRecreation() = runBlocking {
        val f = Fixture()
        val upper = "$a?token=ABC"
        val lower = "$a?token=abc"
        f.preferences.reconcileRemote(f.owner, snapshot(record("$a/manifest.json?token=ABC", "Brasil", false), record(lower)))
        assertEquals(listOf(upper, lower), f.preferences.installedAddonUrls.first())
        assertFalse(f.preferences.addAddon("$a/manifest.json?token=ABC"))
        val recreated = f.preferences()
        assertEquals("Brasil", recreated.userSetNames.first()[upper])
        assertEquals(false, recreated.addonEnabledStates.first()[upper])
    }

    @Test fun inheritedPrimaryAddonsAreReadOnlyIncludingNamesAndKeepPendingPrimaryEdits() = runBlocking {
        val f = Fixture()
        val remote = RemoteFixture(snapshot(record(a)))
        val service = f.service(remote)
        service.getRemoteAddonUrls().getOrThrow()
        f.preferences.setUserSetNames(mapOf(a to "Local"))
        f.profileId.value = 2
        val before = f.disk.data.first()
        assertFalse(f.preferences.addAddon(b))
        assertFalse(f.preferences.removeAddon(a))
        assertFalse(f.preferences.setAddonEnabled(a, false))
        f.preferences.setUserSetNames(mapOf(a to "Proibido"))
        assertEquals(before, f.disk.data.first())
        service.getRemoteAddonUrls().getOrThrow()
        service.pushToRemote().getOrThrow()
        assertEquals(1, remote.lastProfileId)
        assertEquals(0, remote.uploads)
        assertEquals("Local", f.preferences.userSetNames.first()[a])
        f.profileId.value = 1
        service.getRemoteAddonUrls().getOrThrow()
        assertEquals(1, remote.uploads)
        assertEquals("Local", remote.server.single().jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun stalePullAndDelayedUploadOwnersCannotWriteAnotherAccount() = runBlocking {
        val f = Fixture()
        val remote = RemoteFixture(snapshot(record(a)))
        val service = f.service(remote)
        val owner = f.owner
        remote.onPull = { f.account.value = AuthState.FullAccount("fixture-b", "other@invalid.test") }
        var cancelled = false
        try { service.getRemoteAddonUrls() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(emptyPreferences(), f.disk.data.first())
        cancelled = false
        try { service.pushForOwner(owner) } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(0, remote.uploads)
    }

    @Test fun ownerChangeInsideLocalTransactionCannotRecordOrMutateTheNewAccount() = runBlocking {
        val memory = MemoryStore()
        val f = Fixture(memory)
        memory.beforeWrite = { f.account.value = AuthState.SignedOut }
        var cancelled = false
        try { f.preferences.addAddon(a) } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(emptyPreferences(), memory.data.value)
    }

    @Test fun malformedRemoteAndFutureJournalDoNotReplaceExistingPreferences() = runBlocking {
        val f = Fixture()
        f.preferences.reconcileRemote(f.owner, snapshot(record(a)))
        val before = f.disk.data.first()
        var rejected = false
        try { f.preferences.reconcileRemote(f.owner, array("""[{"url":"valid","enabled":"false"}]""")) }
        catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        assertEquals(before, f.disk.data.first())
        val key = before.asMap().keys.single { it.name.startsWith("sync_journal_v1_") }
        f.disk.edit { it[stringPreferencesKey(key.name)] = """{"schema":99,"baseline":[],"local":[]}""" }
        val future = f.disk.data.first()
        rejected = false
        try { f.preferences.removeAddon(a) } catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        assertEquals(future, f.disk.data.first())
    }

    @Test fun actualPreferencesFileRecoversPendingLocalChangesAfterProcessStoreRecreation() = runBlocking {
        val directory = Files.createTempDirectory("telumia-addon-journal-").toFile()
        val file = directory.resolve("owned.preferences_pb")
        var job = SupervisorJob()
        var disk = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        try {
            val first = Fixture(disk)
            first.preferences.reconcileRemote(first.owner, snapshot(record(a, "Antes")))
            first.preferences.setUserSetNames(mapOf(a to "Durável"))
            first.preferences.setAddonEnabled(a, false)
            assertTrue(file.isFile)
            job.cancelAndJoin()
            job = SupervisorJob()
            disk = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
            val recreated = Fixture(disk)
            val remote = RemoteFixture(snapshot(record(a, "Antes"), record(b)))
            recreated.service(remote).getRemoteAddonUrls().getOrThrow()
            assertEquals(listOf(a, b), urls(remote.server))
            assertEquals("Durável", recreated.preferences.userSetNames.first()[a])
            assertEquals(false, recreated.preferences.addonEnabledStates.first()[a])
        } finally {
            job.cancelAndJoin()
            check(directory.canonicalPath.startsWith(System.getProperty("java.io.tmpdir")))
            directory.deleteRecursively()
        }
    }
}
