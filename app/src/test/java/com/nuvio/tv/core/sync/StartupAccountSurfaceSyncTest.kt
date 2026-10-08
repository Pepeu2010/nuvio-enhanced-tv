package com.nuvio.tv.core.sync

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.repository.LibraryRepositoryImpl
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.LibrarySourceMode
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@OptIn(InternalCoroutinesApi::class)
class StartupAccountSurfaceSyncTest {
    private class Fixture {
        var account: AuthState = AuthState.FullAccount("account-a", "fixture@example.invalid")
        val auth = mockk<AuthManager>()
        val authFlow = mockk<StateFlow<AuthState>>()
        val profiles = mockk<ProfileSyncService>()
        val profileManager = mockk<ProfileManager>()
        val active = MutableStateFlow(1)
        val addons = mockk<AddonSyncService>()
        val plugins = mockk<PluginSyncService>()
        val collections = mockk<CollectionSyncService>()
        val home = mockk<HomeCatalogSettingsSyncService>()
        val settings = mockk<ProfileSettingsSyncService>()
        val credentials = mockk<ProviderCredentialSyncService>()
        val library = mockk<LibrarySyncService>()
        val libraryRepository = mockk<LibraryRepositoryImpl>(relaxed = true)
        val source = MutableStateFlow(LibrarySourceMode.LOCAL)
        val service: StartupSyncService
        init {
            every { auth.authState } returns authFlow
            every { authFlow.value } answers { account }
            // No startup collector/network jobs in this scoped fixture.
            coEvery { authFlow.collect(any()) } throws CancellationException("Fixture collector closed")
            every { profileManager.activeProfileId } returns active
            every { libraryRepository.sourceMode } returns source
            coEvery { profiles.pullFromRemote(any()) } returns Result.success(emptyList())
            coEvery { addons.getRemoteAddonUrls() } returns Result.success(emptyList())
            coEvery { plugins.getRemoteRepoUrls() } returns Result.success(emptyList())
            coEvery { collections.pullFromRemote() } returns Result.success(false)
            coEvery { home.pullFromRemote() } returns Result.success(false)
            coEvery { settings.pullCurrentProfileFromRemote() } returns Result.success(false)
            coEvery { credentials.syncFromRemote(any()) } returns Result.success(false)
            coEvery { library.syncFromRemote(any()) } returns Result.success(LibraryRemoteSyncResult(0, 0, 0, 0, true, false))
            service = StartupSyncService(auth, plugins, addons, collections, home,
                mockk(relaxed = true), library, mockk(relaxed = true), settings, credentials,
                profiles, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                libraryRepository, mockk(relaxed = true), profileManager, mockk(relaxed = true), mockk(relaxed = true))
        }
    }

    @Test fun unchangedRemoteStillChecksEverySetupSurface() = runBlocking {
        val f = Fixture()
        assertTrue(f.service.pullBroadRemoteData(1, true))
        coVerify(exactly = 1) { f.profiles.pullFromRemote(any()); f.settings.pullCurrentProfileFromRemote()
            f.credentials.syncFromRemote(1); f.addons.getRemoteAddonUrls(); f.plugins.getRemoteRepoUrls()
            f.collections.pullFromRemote(); f.home.pullFromRemote(); f.library.syncFromRemote(1) }
    }

    @Test fun failedCollectionDoesNotPreventOtherPullsOrReportSuccess() = runBlocking {
        val f = Fixture()
        coEvery { f.collections.pullFromRemote() } returns Result.failure(IllegalStateException("offline"))
        assertFalse(f.service.pullBroadRemoteData(1, true))
        coVerify { f.addons.getRemoteAddonUrls(); f.home.pullFromRemote(); f.library.syncFromRemote(1) }
    }

    @Test fun failedSettingsAndAddonAreIncompleteEvenWhenLibrarySucceeds() = runBlocking {
        val f = Fixture()
        coEvery { f.settings.pullCurrentProfileFromRemote() } returns Result.failure(IllegalStateException("offline"))
        coEvery { f.addons.getRemoteAddonUrls() } returns Result.failure(IllegalStateException("offline"))
        assertFalse(f.service.pullBroadRemoteData(1, true))
        coVerify { f.collections.pullFromRemote(); f.library.syncFromRemote(1) }
    }

    @Test fun accountChangeDuringProfilePullCancelsSubsequentSetupWrites() = runBlocking {
        val f = Fixture()
        coEvery { f.profiles.pullFromRemote(any()) } coAnswers {
            f.account = AuthState.FullAccount("account-b", "other@example.invalid")
            Result.success(emptyList())
        }
        var cancelled = false
        try { f.service.pullBroadRemoteData(1, true) } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        coVerify(exactly = 0) { f.addons.getRemoteAddonUrls(); f.collections.pullFromRemote() }
    }
}
