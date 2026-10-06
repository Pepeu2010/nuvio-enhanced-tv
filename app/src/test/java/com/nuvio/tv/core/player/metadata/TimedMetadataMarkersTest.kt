package com.nuvio.tv.core.player.metadata

import org.junit.Test
import org.junit.Assert.assertTrue

class TimedMetadataMarkersTest {
    private val scope = TimedMetadataScope("movie:1", "movie", "movie:1")
    private fun event(id: String, start: Long, end: Long?, body: TimedMetadataBody) =
        TimedMetadataEvent(id, start, end, body, TimedMetadataProvenance("source"))

    @Test fun projectionKeepsRealIntervalsAndPointBookmarksButNeverGlobalSceneClaims() {
        val timeline = TimedMetadataTimeline.create(scope, listOf(
            event("intro", 10, 20, TimedMetadataBody.Segment(TimedMetadataKind.INTRO)),
            event("bookmark", 50, null, TimedMetadataBody.Bookmark("Minha cena")),
            event("chapter", 70, 100, TimedMetadataBody.Chapter("Capítulo final")),
            event("actor", 10, 20, TimedMetadataBody.Actor("person:1", "Pessoa"))))
        val markers = timeline.toPlayerMarkers(100) { "Abertura" }
        assertEquals(listOf("Abertura", "Minha cena", "Capítulo final"), markers.map { it.label })
        assertEquals(0.1f, markers.first().startFraction)
        assertEquals(0.2f, markers.first().endFraction)
        assertEquals(null, markers[1].endFraction)
        assertTrue(markers.all { it.providerId == "source" })
        assertTrue(timeline.toPlayerMarkers(0) { "Abertura" }.isEmpty())
    }

    @Test fun durationChangesAndCapacityCannotProduceOutOfRangeNativeMarkers() {
        val timeline = TimedMetadataTimeline.create(scope, (0..300).map {
            event("chapter:$it", it.toLong(), 1000, TimedMetadataBody.Chapter("Capítulo $it")) })
        assertEquals(MAX_TIMELINE_MARKERS, timeline.toPlayerMarkers(1000) { "" }.size)
        val shorter = timeline.toPlayerMarkers(100) { "" }
        assertEquals(100, shorter.size)
        assertTrue(shorter.all { it.endFraction == 1f && it.startFraction in 0f..1f })
    }
}

private fun assertEquals(expected: Any?, actual: Any?) = org.junit.Assert.assertEquals(expected, actual)
