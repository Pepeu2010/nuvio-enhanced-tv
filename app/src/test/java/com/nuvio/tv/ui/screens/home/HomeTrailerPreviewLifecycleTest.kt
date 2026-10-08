package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.trailer.TrailerPlaybackSource
import com.nuvio.tv.data.trailer.TrailerService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Actual Home ViewModel pipeline; resolver collaborators are controlled, playback is not mocked as proof. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeTrailerPreviewLifecycleTest {
    private val created = mutableListOf<HomeViewModel>()
    @Before fun setup() = Dispatchers.setMain(Dispatchers.Unconfined)
    @After fun teardown() {
        created.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test fun repeatedFocusKeepsThePendingRequestAndPublishesOnce() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val service = service()
        coEvery { service.getTrailerPlaybackSource(any(), any(), any(), any(), any()) } coAnswers {
            started.complete(Unit); finish.await(); TrailerPlaybackSource("https://fixture.test/a.mp4")
        }
        val vm = newViewModel(service)
        request(vm, "a"); started.await()
        val pending = vm.trailerPreviewJob
        request(vm, "a")
        assertSame(pending, vm.trailerPreviewJob)
        finish.complete(Unit); pending!!.join()
        assertEquals("https://fixture.test/a.mp4", vm.trailerPreviewUrlsState["a"])
        coVerify(exactly=1) { service.getTrailerPlaybackSource(any(), any(), any(), any(), any()) }
    }

    @Test fun cachedFocusCancelsTheOldResolution() = runBlocking { cancellationOnCachedFocus(negative=false) }
    @Test fun negativeCachedFocusAlsoCancelsTheOldResolution() = runBlocking { cancellationOnCachedFocus(negative=true) }

    private suspend fun cancellationOnCachedFocus(negative: Boolean) {
        val started = CompletableDeferred<Unit>()
        val cleaned = AtomicBoolean()
        val service = service()
        coEvery { service.getTrailerPlaybackSource(any(), any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            try { awaitCancellation() } finally { cleaned.set(true) }
        }
        val vm = newViewModel(service)
        if (negative) vm.trailerPreviewNegativeCache.add("b")
        else vm.trailerPreviewUrlsState["b"] = "https://fixture.test/b.mp4"
        request(vm, "a"); started.await(); val old = vm.trailerPreviewJob!!
        request(vm, "b"); withTimeout(2_000) { old.join() }
        assertTrue(cleaned.get()); assertNull(vm.trailerPreviewJob)
        assertFalse(vm.trailerPreviewUrlsState.containsKey("a"))
        assertFalse(vm.trailerPreviewNegativeCache.contains("a"))
        assertEquals("b", vm.activeTrailerPreviewItemId)
    }

    @Test fun tmdbCancellationDoesNotContinueToTrailerOrFallback() = runBlocking {
        val tmdb = mockk<TmdbService>(relaxed=true)
        val service = service(); val vm = newViewModel(service, tmdb)
        coEvery { tmdb.ensureTmdbId(any(), any()) } throws CancellationException("cancelled lookup")
        request(vm, "a", fallback="abcdefghijk"); vm.trailerPreviewJob!!.join()
        assertFalse(vm.trailerPreviewNegativeCache.contains("a"))
        coVerify(exactly=0) { service.getTrailerPlaybackSource(any(), any(), any(), any(), any()) }
        coVerify(exactly=0) { service.getTrailerPlaybackSourceFromYouTubeUrl(any(), any(), any()) }
    }

    @Test fun cancelledItemCanResolveAgainAfterFocusChangesBack() = runBlocking {
        val started = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        val service = service()
        coEvery { service.getTrailerPlaybackSource(any(), any(), any(), any(), any()) } coAnswers {
            if (calls.incrementAndGet() == 1) { started.complete(Unit); awaitCancellation() }
            TrailerPlaybackSource("https://fixture.test/retry.mp4")
        }
        val vm = newViewModel(service)
        request(vm, "a"); started.await()
        request(vm, "b"); request(vm, "a")
        withTimeout(3_000) { vm.trailerPreviewJob!!.join() }
        assertEquals(2, calls.get())
        assertEquals("https://fixture.test/retry.mp4", vm.trailerPreviewUrlsState["a"])
        assertFalse(vm.trailerPreviewUrlsState.containsKey("b"))
    }

    @Test fun leavingHomeCancelsAndInvalidatesANonCooperativeLateResponse() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val service = service()
        coEvery { service.getTrailerPlaybackSource(any(), any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            withContext(NonCancellable) { finish.await() }
            TrailerPlaybackSource("https://fixture.test/stale.mp4")
        }
        val vm = newViewModel(service)
        request(vm, "a"); started.await(); val old = vm.trailerPreviewJob!!
        vm.cancelTrailerPreview(); finish.complete(Unit)
        withTimeout(2_000) { old.join() }
        assertNull(vm.activeTrailerPreviewItemId); assertNull(vm.trailerPreviewJob)
        assertTrue(vm.trailerPreviewUrlsState.isEmpty()); assertTrue(vm.trailerPreviewNegativeCache.isEmpty())
    }

    @Test fun aDeadlineCleansTheResolverWithoutNegativeCachingATransientTimeout() = runBlocking {
        val cleaned = AtomicBoolean(); val service = service()
        coEvery { service.getTrailerPlaybackSource(any(), any(), any(), any(), any()) } coAnswers {
            try { awaitCancellation() } finally { cleaned.set(true) }
        }
        val vm = newViewModel(service); request(vm, "a")
        withTimeout(HOME_TRAILER_PREVIEW_TIMEOUT_MS + 3_000) { vm.trailerPreviewJob!!.join() }
        assertTrue(cleaned.get()); assertTrue(vm.trailerPreviewNegativeCache.isEmpty())
        assertTrue(vm.trailerPreviewUrlsState.isEmpty())
    }

    @Test fun transientResolverFailureCanBeRetriedWithoutAnUncaughtUiException() = runBlocking {
        val service = service()
        coEvery { service.getTrailerPlaybackSource(any(), any(), any(), any(), any()) } throws IllegalStateException("temporary")
        val vm = newViewModel(service); request(vm, "a"); vm.trailerPreviewJob!!.join()
        assertTrue(vm.trailerPreviewNegativeCache.isEmpty())
        coEvery { service.getTrailerPlaybackSource(any(), any(), any(), any(), any()) } returns TrailerPlaybackSource("https://fixture.test/recovered.mp4")
        request(vm, "a"); vm.trailerPreviewJob!!.join()
        assertEquals("https://fixture.test/recovered.mp4", vm.trailerPreviewUrlsState["a"])
    }

    @Test fun resolvedVideoAndAudioCachesRemainBoundedTogether() = runBlocking {
        val service = service()
        coEvery { service.getTrailerPlaybackSource(any(), any(), any(), any(), any()) } returns
            TrailerPlaybackSource("https://fixture.test/latest.mp4", "https://fixture.test/latest.m4a")
        val vm = newViewModel(service)
        repeat(64) { vm.trailerPreviewUrlsState["cached:$it"]="video"; vm.trailerPreviewAudioUrlsState["cached:$it"]="audio" }
        request(vm, "a"); vm.trailerPreviewJob!!.join()
        assertEquals(64, vm.trailerPreviewUrlsState.size); assertEquals(64, vm.trailerPreviewAudioUrlsState.size)
        assertEquals(vm.trailerPreviewUrlsState.keys, vm.trailerPreviewAudioUrlsState.keys)
        assertEquals("https://fixture.test/latest.mp4", vm.trailerPreviewUrlsState["a"])
    }

    @Test fun noTrailerCacheRemainsBounded() = runBlocking {
        val vm = newViewModel(service()); repeat(64) { vm.trailerPreviewNegativeCache.add("missing:$it") }
        request(vm, "a"); vm.trailerPreviewJob!!.join()
        assertEquals(64, vm.trailerPreviewNegativeCache.size); assertTrue("a" in vm.trailerPreviewNegativeCache)
    }

    private fun request(vm: HomeViewModel, id: String, fallback: String? = null) =
        vm.requestTrailerPreviewPipeline(id, "Título $id", "2026", "movie", fallback)

    private fun service() = mockk<TrailerService>(relaxed=true).apply {
        coEvery { getTrailerPlaybackSource(any(), any(), any(), any(), any()) } returns null
    }

    private fun newViewModel(service: TrailerService, tmdb: TmdbService = mockk(relaxed=true)): HomeViewModel {
        coEvery { tmdb.ensureTmdbId(any(), any()) } returns null
        val profiles = mockk<com.nuvio.tv.core.profile.ProfileManager>(relaxed=true) {
            every { activeProfileReady } returns MutableStateFlow(false)
            every { activeProfileId } returns MutableStateFlow(1)
        }
        val enrichment = mockk<com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache>(relaxed=true) {
            every { cacheCleared } returns MutableStateFlow(0)
        }
        return HomeViewModel(
            episodeShuffleStore=mockk(relaxed=true), episodeShuffle=com.nuvio.tv.domain.model.EpisodeShuffle(),
            appContext=mockk(relaxed=true), addonRepository=mockk(relaxed=true), startupSyncService=mockk(relaxed=true),
            catalogRepository=mockk(relaxed=true), watchProgressRepository=mockk(relaxed=true), libraryRepository=mockk(relaxed=true),
            metaRepository=mockk(relaxed=true), collectionsDataStore=mockk(relaxed=true), layoutPreferenceDataStore=mockk(relaxed=true),
            playerSettingsDataStore=mockk(relaxed=true), tmdbSettingsDataStore=mockk(relaxed=true), mdbListSettingsDataStore=mockk(relaxed=true),
            traktSettingsDataStore=mockk(relaxed=true), authSessionNoticeDataStore=mockk(relaxed=true), tmdbService=tmdb,
            tmdbMetadataService=mockk(relaxed=true), mdbListRepository=mockk(relaxed=true), imdbEpisodeRatingsRepository=mockk(relaxed=true),
            trailerService=service, watchedSeriesStateHolder=mockk(relaxed=true), cwEnrichmentCache=enrichment,
            profileManager=profiles, tvRecommendationManager=mockk(relaxed=true)
        ).also { it.startupGracePeriodActive=false; created.add(it) }
    }
}
