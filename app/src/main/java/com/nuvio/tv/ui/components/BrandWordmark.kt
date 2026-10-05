package com.nuvio.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.brandWordmarkResource

@Composable
fun BrandWordmark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = 1f,
    drawableOverride: Int? = null,
    compact: Boolean = false,
) {
    val artwork = drawableOverride ?: NuvioTheme.currentTheme.brandWordmarkResource
    val colors = NuvioTheme.colors
    BoxWithConstraints(
        modifier = modifier.aspectRatio(if (compact) 1f else 4.1f)
            .graphicsLayer { this.alpha = alpha.coerceIn(0f, 1f) }
            .clearAndSetSemantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val height = maxHeight
        val fontScale = LocalDensity.current.fontScale
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(artwork), contentDescription = null,
                modifier = Modifier.height(height * 0.9f).aspectRatio(1f), contentScale = contentScale)
            if (!compact) {
                Spacer(Modifier.width(height * 0.18f))
                Text("TELUMIA", style = TextStyle(
                    fontSize = (height.value * 0.50f / fontScale).sp,
                    lineHeight = (height.value * 0.60f / fontScale).sp,
                    letterSpacing = (height.value * 0.012f / fontScale).sp,
                    fontWeight = FontWeight.SemiBold,
                ), color = colors.TextPrimary, maxLines = 1)
            }
        }
    }
}
