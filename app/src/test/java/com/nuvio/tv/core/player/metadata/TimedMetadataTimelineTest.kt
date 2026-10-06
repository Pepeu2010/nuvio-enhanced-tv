package com.nuvio.tv.core.player.metadata

import com.nuvio.tv.data.repository.SkipInterval
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class TimedMetadataTimelineTest {
    private val scope = TimedMetadataScope("fixture:media", "movie", "fixture:video", "fixture:cut")
    private fun event(id: String, start: Long, end: Long? = null) = TimedMetadataEvent(id, start, end,
        TimedMetadataBody.Chapter(id), TimedMetadataProvenance("fixture.provider"))

    @Test fun overlappingIntervalsAndAdjacentBoundariesRemainCorrectDuringForwardAndBackwardSeek() {
        val timeline = TimedMetadataTimeline.create(scope, listOf(event("long", 0, 100),
            event("first", 10, 20), event("second", 20, 30), event("point", 20)))
        assertEquals(listOf("long", "first"), timeline.eventsAt(19).map { it.id })
        assertEquals(setOf("long", "second", "point"), timeline.eventsAt(20).map { it.id }.toSet())
        assertEquals(listOf("long", "second"), timeline.eventsAt(21).map { it.id })
        assertEquals(listOf("long"), timeline.eventsAt(30).map { it.id })
        assertEquals(listOf("long", "first"), timeline.eventsAt(10).map { it.id })
        assertTrue(timeline.eventsAt(100).isEmpty())
        assertTrue(timeline.eventsAt(-1).isEmpty())
    }

    @Test fun malformedUnboundedAndDuplicateInputCannotBreakTheIndexOrExceedCapacity() {
        val timeline = TimedMetadataTimeline.create(scope, listOf(event("negative", -1, 20),
            event("reversed", 30, 20), event("empty", 10, 10), event("maximum", Long.MAX_VALUE),
            event("confidence", 10, 20).copy(confidence = Double.NaN),
            event("invalid-payload", 10, 20).copy(body = TimedMetadataBody.Segment(TimedMetadataKind.ACTOR)),
            event("correct", 10, 30), event("correct", 10, 40), event("beyond-duration", 60, 70)), durationMs = 50)
        assertEquals(listOf("correct"), timeline.events.map { it.id })
        assertEquals(30L, timeline.events.single().endMs)
        val bounded = TimedMetadataTimeline.create(scope, (0..20).map { event("$it", it.toLong(), 100) }, limit = 3)
        assertEquals(3, bounded.events.size)
        assertTrue(bounded.truncated)
        assertFalse(timeline.truncated)
    }

    @Test fun sceneClaimsRequireKnownEditionTrustedSourceAndExplicitConfidence() {
        val actor = event("actor", 100, 200).copy(body = TimedMetadataBody.Actor("person:1", "Pessoa de teste"), confidence = 0.95)
        assertTrue(TimedMetadataTimeline.create(scope, listOf(actor)).sceneEventsAt(150).isEmpty())
        val trusted = setOf("fixture.provider")
        assertTrue(TimedMetadataTimeline.create(scope.copy(editionId = null), listOf(actor), trustedSceneProviderIds = trusted).sceneEventsAt(150).isEmpty())
        assertTrue(TimedMetadataTimeline.create(scope, listOf(actor.copy(confidence = null)), trustedSceneProviderIds = trusted).sceneEventsAt(150).isEmpty())
        val timeline = TimedMetadataTimeline.create(scope, listOf(actor), trustedSceneProviderIds = trusted)
        assertEquals(listOf(actor), timeline.sceneEventsAt(150))
        assertTrue(timeline.sceneEventsAt(200).isEmpty())
        assertTrue(timeline.sceneEventsAt(150, Double.NaN).isEmpty())
    }

    @Test fun existingSkipDataIsAdaptedWithoutInventingConfidenceAndClampedToMediaDuration() {
        val timeline = skipTimedMetadata(scope, listOf(SkipInterval(10.0, 20.0, "intro", "introdb"),
            SkipInterval(90.0, 150.0, "movie-credits", "introdb"), SkipInterval(Double.NaN, 20.0, "intro", "introdb"),
            SkipInterval(5.0, 6.0, "unknown", "addon")), 100_000)
        assertEquals(listOf(TimedMetadataKind.INTRO, TimedMetadataKind.CREDITS), timeline.events.map { it.body.kind })
        assertEquals(100_000L, timeline.events.last().endMs)
        assertTrue(timeline.events.all { it.confidence == null })
        assertTrue(timeline.sceneEventsAt(15_000).isEmpty())
    }

    @Test fun providerFailuresAreIsolatedAndPayloadsCannotImpersonateTrustedProviders() = runBlocking {
        val actor = event("actor", 0, 100).copy(body = TimedMetadataBody.Actor("person:1", "Pessoa de teste"), confidence = 0.95)
        val healthy = object : SceneMetadataProvider { override val id = "trusted"; override suspend fun load(scope: TimedMetadataScope) = listOf(actor) }
        val spoof = object : TimedMetadataProvider { override val id = "untrusted"; override suspend fun load(scope: TimedMetadataScope) = listOf(actor.copy(id = "spoof", provenance = TimedMetadataProvenance("trusted"))) }
        val broken = object : TimedMetadataProvider { override val id = "broken"; override suspend fun load(scope: TimedMetadataScope): List<TimedMetadataEvent> = error("private diagnostic must not enter the public failure state") }
        val slow = object : TimedMetadataProvider { override val id = "slow"; override suspend fun load(scope: TimedMetadataScope): List<TimedMetadataEvent> { delay(1000); return emptyList() } }
        val result = loadTimedMetadata(scope, listOf(healthy, spoof, broken, slow), trustedSceneProviderIds = setOf("trusted"), timeoutMs = 50)
        assertEquals(listOf("actor"), result.timeline.sceneEventsAt(50).map { it.id })
        assertEquals(setOf(TimedMetadataFailureReason.UNAVAILABLE, TimedMetadataFailureReason.TIMEOUT), result.failures.map { it.reason }.toSet())
        assertEquals("untrusted", result.timeline.events.first { it.id == "spoof" }.provenance.providerId)
    }

    @Test fun lifecycleCancellationIsNeverConvertedIntoAProviderFailure() = runBlocking {
        val cancelled = object : TimedMetadataProvider { override val id = "cancelled"; override suspend fun load(scope: TimedMetadataScope): List<TimedMetadataEvent> = throw CancellationException("media changed") }
        var propagated = false
        try { loadTimedMetadata(scope, listOf(cancelled)) } catch (_: CancellationException) { propagated = true }
        assertTrue(propagated)
    }
}

private fun assertEquals(expected: Any?, actual: Any?) = org.junit.Assert.assertEquals(expected, actual)
