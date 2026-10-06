package com.nuvio.tv.core.player.metadata

import com.nuvio.tv.data.local.AutoSkipSegmentType
import com.nuvio.tv.data.repository.SkipInterval

/** Adapts data already loaded by the current repositories; makes no additional network request. */
internal fun skipTimedMetadata(scope: TimedMetadataScope, intervals: List<SkipInterval>, durationMs: Long): TimedMetadataTimeline {
    val events = intervals.asSequence().take(TimedMetadataTimeline.MAX_EVENTS).mapNotNull { interval ->
        if (!interval.startTime.isFinite() || !interval.endTime.isFinite() || interval.startTime < 0 ||
            interval.endTime <= interval.startTime || interval.endTime >= Long.MAX_VALUE / 1000.0) return@mapNotNull null
        val kind = when (AutoSkipSegmentType.fromSkipIntervalType(interval.type)) {
            AutoSkipSegmentType.INTRO -> TimedMetadataKind.INTRO
            AutoSkipSegmentType.RECAP -> TimedMetadataKind.RECAP
            AutoSkipSegmentType.OUTRO, AutoSkipSegmentType.MOVIE_CREDITS -> TimedMetadataKind.CREDITS
            null -> if (interval.type.trim().equals("post-credits", ignoreCase = true)) TimedMetadataKind.POST_CREDITS else return@mapNotNull null
        }
        val start = (interval.startTime * 1000.0).toLong()
        val end = (interval.endTime * 1000.0).toLong()
        TimedMetadataEvent("${interval.type}:$start:$end", start, end, TimedMetadataBody.Segment(kind),
            TimedMetadataProvenance(interval.provider))
    }.asIterable()
    return TimedMetadataTimeline.create(scope, events, durationMs = durationMs)
}
