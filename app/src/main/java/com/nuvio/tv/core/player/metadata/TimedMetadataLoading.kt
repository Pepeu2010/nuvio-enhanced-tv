package com.nuvio.tv.core.player.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

enum class TimedMetadataFailureReason { TIMEOUT, UNAVAILABLE }
data class TimedMetadataProviderFailure(val providerId: String, val reason: TimedMetadataFailureReason)
data class TimedMetadataLoadResult(val timeline: TimedMetadataTimeline, val failures: List<TimedMetadataProviderFailure>)

/** Bounded fan-out and sanitized failure states. The lifecycle owner cancels on media/edition changes. */
suspend fun loadTimedMetadata(scope: TimedMetadataScope, providers: List<TimedMetadataProvider>,
    durationMs: Long? = null, trustedSceneProviderIds: Set<String> = emptySet(),
    timeoutMs: Long = 1500): TimedMetadataLoadResult = coroutineScope {
    val registered = providers.take(8).filter { it.id.matches(Regex("[A-Za-z0-9._:-]{1,256}")) }.distinctBy { it.id }
    val results = registered.map { provider -> async {
        try {
            val events = withTimeoutOrNull(timeoutMs.coerceIn(1, 5000)) {
                provider.load(scope).take(TimedMetadataTimeline.MAX_EVENTS).map {
                    // Payloads cannot impersonate another registered provider, including a trusted scene source.
                    it.copy(provenance = it.provenance.copy(providerId = provider.id))
                }
            }
            if (events == null) emptyList<TimedMetadataEvent>() to TimedMetadataProviderFailure(provider.id, TimedMetadataFailureReason.TIMEOUT)
            else events to null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList<TimedMetadataEvent>() to TimedMetadataProviderFailure(provider.id, TimedMetadataFailureReason.UNAVAILABLE)
        }
    } }.awaitAll()
    val trusted = registered.filterIsInstance<SceneMetadataProvider>().map { it.id }.toSet().intersect(trustedSceneProviderIds)
    TimedMetadataLoadResult(TimedMetadataTimeline.create(scope, results.flatMap { it.first }, durationMs, trusted),
        results.mapNotNull { it.second })
}
