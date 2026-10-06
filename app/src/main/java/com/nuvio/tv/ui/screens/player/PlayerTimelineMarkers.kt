package com.nuvio.tv.ui.screens.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.player.metadata.PlayerTimedMarker
import com.nuvio.tv.core.player.metadata.TimedMetadataKind
import com.nuvio.tv.core.player.metadata.MAX_TIMELINE_MARKERS

/** Decorative marks never intercept the existing seek input. */
internal fun DrawScope.drawTimelineMarkers(markers: List<PlayerTimedMarker>, trackY: Float) {
    markers.take(MAX_TIMELINE_MARKERS).forEach { marker ->
        if (!marker.startFraction.isFinite() || marker.startFraction !in 0f..1f ||
            marker.endFraction?.let { !it.isFinite() || it <= marker.startFraction || it > 1f } == true) return@forEach
        val color = when (marker.kind) {
            TimedMetadataKind.INTRO, TimedMetadataKind.RECAP -> Color(0xFFEBB874)
            TimedMetadataKind.CREDITS, TimedMetadataKind.POST_CREDITS -> Color(0xFF93A3BE)
            TimedMetadataKind.CHAPTER -> Color.White
            TimedMetadataKind.BOOKMARK -> Color(0xFF72D5CF)
            else -> return@forEach
        }
        val x = size.width * marker.startFraction
        marker.endFraction?.let { end ->
            drawLine(color.copy(alpha = 0.8f), Offset(x, trackY), Offset(size.width * end, trackY), strokeWidth = 3.dp.toPx())
        }
        drawLine(color, Offset(x, trackY - 5.dp.toPx()), Offset(x, trackY + 5.dp.toPx()), strokeWidth = 2.dp.toPx())
    }
}

internal fun timelineMarkerDescription(markers: List<PlayerTimedMarker>): String =
    markers.take(12).joinToString(" · ") { it.label.take(256) }
