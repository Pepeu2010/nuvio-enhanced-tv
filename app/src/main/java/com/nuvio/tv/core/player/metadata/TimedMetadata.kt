package com.nuvio.tv.core.player.metadata

/** A cut/edition is optional for timeline segments, mandatory for reliable scene claims. */
data class TimedMetadataScope(val mediaId: String, val mediaType: String, val videoId: String, val editionId: String? = null)

enum class TimedMetadataKind { INTRO, RECAP, CREDITS, POST_CREDITS, CHAPTER, BOOKMARK, ACTOR, MUSIC, TRIVIA }

sealed interface TimedMetadataBody {
    val kind: TimedMetadataKind
    data class Segment(override val kind: TimedMetadataKind) : TimedMetadataBody
    data class Chapter(val title: String) : TimedMetadataBody { override val kind = TimedMetadataKind.CHAPTER }
    data class Bookmark(val name: String) : TimedMetadataBody { override val kind = TimedMetadataKind.BOOKMARK }
    data class Actor(val personId: String, val name: String, val character: String? = null) : TimedMetadataBody {
        override val kind = TimedMetadataKind.ACTOR
    }
    data class Music(val title: String, val artist: String? = null, val recordingId: String? = null) : TimedMetadataBody {
        override val kind = TimedMetadataKind.MUSIC
    }
    data class Trivia(val text: String) : TimedMetadataBody { override val kind = TimedMetadataKind.TRIVIA }
}

data class TimedMetadataProvenance(val providerId: String, val evidenceId: String? = null)

data class TimedMetadataEvent(
    val id: String,
    val startMs: Long,
    val endMs: Long? = null,
    val body: TimedMetadataBody,
    val provenance: TimedMetadataProvenance,
    /** Unknown confidence stays unknown; adapters must not manufacture a value. */
    val confidence: Double? = null,
)

interface TimedMetadataProvider {
    val id: String
    suspend fun load(scope: TimedMetadataScope): List<TimedMetadataEvent>
}

/** Implementations must supply edition-specific, temporal evidence, never a global cast fallback. */
interface SceneMetadataProvider : TimedMetadataProvider

/** Immutable, bounded index. Membership uses [start, end), so adjacent scenes never overlap accidentally. */
class TimedMetadataTimeline private constructor(
    val scope: TimedMetadataScope,
    val events: List<TimedMetadataEvent>,
    private val trustedSceneProviderIds: Set<String>,
    val truncated: Boolean,
) {
    private val latestEndByIndex = LongArray(events.size).also { ends ->
        var latest = 0L
        events.forEachIndexed { index, event ->
            latest = maxOf(latest, event.endMs ?: (event.startMs + 1L))
            ends[index] = latest
        }
    }

    fun eventsAt(positionMs: Long): List<TimedMetadataEvent> {
        if (positionMs < 0 || events.isEmpty()) return emptyList()
        var low = 0
        var high = events.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (events[middle].startMs <= positionMs) low = middle + 1 else high = middle
        }
        val active = mutableListOf<TimedMetadataEvent>()
        var index = low - 1
        while (index >= 0 && latestEndByIndex[index] > positionMs) {
            val event = events[index--]
            if (event.endMs?.let { positionMs < it } ?: (event.startMs == positionMs)) active += event
        }
        return active.asReversed()
    }

    fun sceneEventsAt(positionMs: Long, minimumConfidence: Double = 0.8): List<TimedMetadataEvent> {
        if (scope.editionId.isNullOrBlank() || !minimumConfidence.isFinite() || minimumConfidence !in 0.0..1.0) return emptyList()
        return eventsAt(positionMs).filter {
            it.body.kind in sceneKinds && it.endMs != null &&
                it.provenance.providerId in trustedSceneProviderIds && (it.confidence ?: -1.0) >= minimumConfidence
        }
    }

    companion object {
        const val MAX_EVENTS = 4096
        private val segmentKinds = setOf(TimedMetadataKind.INTRO, TimedMetadataKind.RECAP,
            TimedMetadataKind.CREDITS, TimedMetadataKind.POST_CREDITS)
        private val sceneKinds = setOf(TimedMetadataKind.ACTOR, TimedMetadataKind.MUSIC, TimedMetadataKind.TRIVIA, TimedMetadataKind.CHAPTER)

        fun create(scope: TimedMetadataScope, input: Iterable<TimedMetadataEvent>, durationMs: Long? = null,
            trustedSceneProviderIds: Set<String> = emptySet(), limit: Int = MAX_EVENTS): TimedMetadataTimeline {
            val capacity = limit.coerceIn(0, MAX_EVENTS)
            val events = mutableListOf<TimedMetadataEvent>()
            val identities = mutableSetOf<Pair<String, String>>()
            var examined = 0
            var truncated = false
            for (event in input) {
                if (events.size == capacity || examined++ >= MAX_EVENTS * 4) { truncated = true; break }
                if (event.id.isBlank() || event.id.length > 1024 || event.startMs < 0 || event.startMs == Long.MAX_VALUE ||
                    event.provenance.providerId.isBlank() || event.provenance.providerId.length > 256 ||
                    event.provenance.evidenceId?.length?.let { it > 1024 } == true ||
                    event.endMs?.let { it <= event.startMs } == true ||
                    event.confidence?.let { !it.isFinite() || it !in 0.0..1.0 } == true || !validBody(event.body)) continue
                if (durationMs != null && durationMs > 0 && event.startMs >= durationMs) continue
                val bounded = if (durationMs != null && durationMs > 0 && event.endMs != null)
                    event.copy(endMs = event.endMs.coerceAtMost(durationMs)) else event
                if (identities.add(event.provenance.providerId to event.id)) events += bounded
            }
            return TimedMetadataTimeline(scope, events.sortedWith(compareBy({ it.startMs }, { it.id })),
                trustedSceneProviderIds.toSet(), truncated)
        }

        private fun validBody(body: TimedMetadataBody): Boolean = when (body) {
            is TimedMetadataBody.Segment -> body.kind in segmentKinds
            is TimedMetadataBody.Actor -> body.personId.isNotBlank() && body.personId.length <= 1024 &&
                body.name.isNotBlank() && body.name.length <= 256 && (body.character?.length ?: 0) <= 256
            is TimedMetadataBody.Music -> body.title.isNotBlank() && body.title.length <= 256 &&
                (body.artist?.length ?: 0) <= 256 && (body.recordingId?.length ?: 0) <= 1024
            is TimedMetadataBody.Chapter -> body.title.isNotBlank() && body.title.length <= 256
            is TimedMetadataBody.Bookmark -> body.name.isNotBlank() && body.name.length <= 256
            is TimedMetadataBody.Trivia -> body.text.isNotBlank() && body.text.length <= 4096
        }
    }
}
