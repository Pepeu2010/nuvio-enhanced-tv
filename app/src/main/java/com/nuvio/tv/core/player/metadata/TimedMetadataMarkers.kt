package com.nuvio.tv.core.player.metadata

/** Bounded presentation contract shared by Compose and the existing native controls bridge. */
data class PlayerTimedMarker(
    val id: String,
    val kind: TimedMetadataKind,
    val startFraction: Float,
    val endFraction: Float?,
    val label: String,
    val providerId: String,
)

internal const val MAX_TIMELINE_MARKERS = 256

internal fun TimedMetadataTimeline.toPlayerMarkers(durationMs: Long,
    segmentLabel: (TimedMetadataKind) -> String): List<PlayerTimedMarker> {
    if (durationMs <= 0) return emptyList()
    return events.asSequence().mapNotNull { event ->
        val label = when (val body = event.body) {
            is TimedMetadataBody.Chapter -> body.title
            is TimedMetadataBody.Bookmark -> body.name
            is TimedMetadataBody.Segment -> segmentLabel(body.kind)
            else -> return@mapNotNull null
        }.trim().take(256)
        if (label.isBlank() || event.startMs >= durationMs) return@mapNotNull null
        PlayerTimedMarker("${event.provenance.providerId}:${event.id}", event.body.kind,
            (event.startMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f),
            event.endMs?.let { (it.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) },
            label, event.provenance.providerId)
    }.take(MAX_TIMELINE_MARKERS).toList()
}
