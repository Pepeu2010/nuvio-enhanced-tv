package com.nuvio.tv.core.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.*
import com.nuvio.tv.data.remote.supabase.SupabaseCollectionBlob
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CollectionReconciliationTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        var beforeWrite: () -> Unit = {}
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            beforeWrite()
            return transform(data.value).also { data.value = it }
        }
    }

    private class Fixture(val disk: DataStore<Preferences> = MemoryStore()) {
        val auth = MutableStateFlow<AuthState>(AuthState.FullAccount("fixture-a", "fixture@invalid.test"))
        val profile = MutableStateFlow(1)
        val manager = mockk<AuthManager> { every { authState } returns auth }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns profile }
        private val factory = mockk<ProfileDataStoreFactory> { every { get(1, "collections") } returns disk }
        private val config = ServerConfiguration("https://fixture.invalid", "fixture", ServerCapabilities(false, true), false)
        fun store() = CollectionsDataStore(mockk(relaxed = true), factory, profiles, manager, config)
        val store = store()
        val owner get() = store.captureSyncOwner()!!
        fun service(remote: CollectionSyncRemote) = CollectionSyncService(mockk(relaxed = true), manager, store,
            profiles, mockk(relaxed = true)).also { it.remote = remote }
    }

    private class RemoteFixture(var server: JsonArray) : CollectionSyncRemote {
        var offline = false
        var uploads = 0
        var onPull: () -> Unit = {}
        var onUpload: suspend () -> Unit = {}
        var returnedProfileId = 1
        override suspend fun pull(profileId: Int, stillCurrent: () -> Boolean): SupabaseCollectionBlob {
            check(!offline) { "Offline fixture" }
            onPull()
            return SupabaseCollectionBlob(returnedProfileId, server)
        }
        override suspend fun push(profileId: Int, snapshot: JsonArray, stillCurrent: () -> Boolean) {
            check(!offline)
            check(stillCurrent())
            uploads++
            onUpload()
            server = snapshot
        }
    }

    private fun array(value: String) = Json.parseToJsonElement(value).jsonArray
    private fun ids(value: JsonArray) = value.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()

    @Test fun offlineEditsAreMergedByTheActualServiceAndConfirmedEmptySnapshotsDelete() = runBlocking {
        val f = Fixture()
        val remote = RemoteFixture(array("""[{"id":"a","title":"Antes","folders":[]}]"""))
        val service = f.service(remote)
        assertTrue(service.pullFromRemote().getOrThrow())
        remote.offline = true
        f.store.updateCollection(f.store.getCurrentCollections().single().copy(title = "Local"))
        assertTrue(service.pullFromRemote().isFailure)
        remote.offline = false
        remote.server = array("""[{"id":"a","title":"Antes","folders":[],"future":true},{"id":"b","title":"Remota","folders":[]}]""")
        service.pullFromRemote().getOrThrow()
        assertEquals(setOf("a", "b"), ids(remote.server))
        assertEquals("Local", remote.server.first().jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), remote.server.first().jsonObject["future"])
        assertEquals(1, remote.uploads)
        remote.server = array("[]")
        service.pullFromRemote().getOrThrow()
        assertTrue(f.store.getCurrentCollections().isEmpty())
        assertEquals(1, remote.uploads)
    }

    @Test fun editsDuringUploadRemainPendingForTheNextServiceRefresh() = runBlocking {
        val f = Fixture()
        f.store.addCollection(Collection("a", "Primeiro"))
        val remote = RemoteFixture(array("[]"))
        val service = f.service(remote)
        remote.onUpload = { f.store.updateCollection(f.store.getCurrentCollections().single().copy(title = "Último")) }
        service.pushToRemote().getOrThrow()
        assertEquals("Primeiro", remote.server.first().jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("Último", f.store.getCurrentCollections().single().title)
        remote.onUpload = {}
        service.pullFromRemote().getOrThrow()
        assertEquals("Último", remote.server.first().jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(2, remote.uploads)
    }

    @Test fun accountChangeAtDataStoreTransactionDoesNotApplyOrAcknowledgeAnotherAccount() = runBlocking {
        val memory = MemoryStore()
        val f = Fixture(memory)
        val owner = f.owner
        memory.beforeWrite = { f.auth.value = AuthState.FullAccount("fixture-b", "other@invalid.test") }
        var cancelled = false
        try { f.store.reconcileRemote(owner, array("""[{"id":"a","title":"Remoto","folders":[]}]"""), true) }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(emptyPreferences(), memory.data.value)
    }

    @Test fun staleAccountResponsesAndWrongProfileRowsNeverReplaceTheSnapshot() = runBlocking {
        val f = Fixture()
        val remote = RemoteFixture(array("""[{"id":"a","title":"Remoto","folders":[]}]"""))
        val service = f.service(remote)
        remote.returnedProfileId = 2
        assertTrue(service.pullFromRemote().isFailure)
        assertNull(f.store.exportCurrentProfileJson())
        remote.returnedProfileId = 1
        remote.onPull = { f.auth.value = AuthState.FullAccount("fixture-b", "other@invalid.test") }
        var cancelled = false
        try { service.pullFromRemote() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertNull(f.store.exportCurrentProfileJson())
        assertEquals(0, remote.uploads)
    }

    @Test fun invalidRemoteDomainDataLeavesTheExistingSnapshotAndJournalUntouched() = runBlocking {
        val memory = MemoryStore()
        val f = Fixture(memory)
        f.store.addCollection(Collection("a", "Preservada"))
        val before = memory.data.value
        var rejected = false
        try { f.store.reconcileRemote(f.owner, array("""[{"id":"bad","title":"","folders":[]}]"""), true) }
        catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        assertEquals(before, memory.data.value)
    }

    @Test fun missingRemoteRowSeedsLegacyLocalDataWhileConfirmedEmptyRowDoesNot() = runBlocking {
        val f = Fixture()
        f.auth.value = AuthState.SignedOut
        f.store.addCollection(Collection("legacy", "Local"))
        f.auth.value = AuthState.FullAccount("fixture-a", "fixture@invalid.test")
        val applied = f.store.reconcileRemote(f.owner, array("[]"), false)
        assertEquals(setOf("legacy"), ids(applied.upload!!.second))
        f.store.acknowledgeUpload(f.owner, applied.upload.first, applied.upload.second)
        assertNull(f.store.reconcileRemote(f.owner, array("[]"), true).upload)
        assertTrue(f.store.getCurrentCollections().isEmpty())
    }

    @Test fun futureJournalAndOrdinarySnapshotSurviveARejectedMutation() = runBlocking {
        val memory = MemoryStore()
        val f = Fixture(memory)
        f.store.reconcileRemote(f.owner, array("""[{"id":"a","title":"Antes","folders":[]}]"""), true)
        val key = memory.data.value.asMap().keys.single { it.name.startsWith("sync_journal_v1_") }
        val future = """{"schema":99,"baseline":[],"local":[]}"""
        memory.edit { it[stringPreferencesKey(key.name)] = future }
        val previous = memory.data.value
        var rejected = false
        try { f.store.removeCollection("a") } catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        assertEquals(previous, memory.data.value)
        assertEquals("Antes", f.store.getCurrentCollections().single().title)
    }

    @Test fun localTitleChangesPreserveFolderSourceAndFutureProviderWireFields() = runBlocking {
        val f = Fixture()
        val initial = array("""[{"id":"a","title":"Antes","future":true,"folders":[{"id":"f","title":"Pasta","futureFolder":42,"sources":[{"provider":"addon","addonId":"x","type":"movie","catalogId":"popular","futureSource":true},{"provider":"future-provider","opaque":"keep"}]}]}]""")
        f.store.reconcileRemote(f.owner, initial, true)
        f.store.updateCollection(f.store.getCurrentCollections().single().copy(title = "Novo"))
        val raw = array(f.store.exportCurrentProfileJson()!!).single().jsonObject
        assertEquals(JsonPrimitive(true), raw["future"])
        val folder = raw["folders"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(42), folder["futureFolder"])
        val sources = folder["sources"]!!.jsonArray
        assertEquals(JsonPrimitive(true), sources.first().jsonObject["futureSource"])
        assertEquals("keep", sources.last().jsonObject["opaque"]!!.jsonPrimitive.content)
    }

    @Test fun actualPreferencesFileRecoversThePendingJournalAfterStoreRecreation() = runBlocking {
        val directory = Files.createTempDirectory("telumia-collection-journal-").toFile()
        val file = directory.resolve("owned.preferences_pb")
        var job = SupervisorJob()
        var disk = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        try {
            val first = Fixture(disk)
            first.store.addCollection(Collection("offline", "Durável"))
            assertTrue(file.isFile)
            job.cancelAndJoin()
            job = SupervisorJob()
            disk = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
            val recreated = Fixture(disk)
            assertEquals("Durável", recreated.store.getCurrentCollections().single().title)
            val applied = recreated.store.reconcileRemote(recreated.owner,
                array("""[{"id":"remote","title":"Remota","folders":[]}]"""), true)
            assertEquals(setOf("offline", "remote"), ids(applied.upload!!.second))
        } finally {
            job.cancelAndJoin()
            check(directory.canonicalPath.startsWith(System.getProperty("java.io.tmpdir")))
            directory.deleteRecursively()
        }
    }
}
