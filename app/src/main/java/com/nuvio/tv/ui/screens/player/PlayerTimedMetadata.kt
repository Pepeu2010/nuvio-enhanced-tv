package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.player.metadata.*

@Composable
internal fun rememberPlayerTimedMarkers(timeline: TimedMetadataTimeline?, durationMs: Long): List<PlayerTimedMarker> {
    val intro = stringResource(R.string.player_timeline_intro)
    val recap = stringResource(R.string.player_timeline_recap)
    val credits = stringResource(R.string.player_timeline_credits)
    val postCredits = stringResource(R.string.player_timeline_post_credits)
    return remember(timeline, durationMs, intro, recap, credits, postCredits) {
        timeline?.toPlayerMarkers(durationMs) { kind -> when (kind) {
            TimedMetadataKind.INTRO -> intro
            TimedMetadataKind.RECAP -> recap
            TimedMetadataKind.CREDITS -> credits
            TimedMetadataKind.POST_CREDITS -> postCredits
            else -> ""
        } }.orEmpty()
    }
}
