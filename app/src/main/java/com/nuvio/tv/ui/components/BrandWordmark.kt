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
    val accent = NuvioTheme.palette.secondary
    BoxWithConstraints(
        modifier = modifier.aspectRatio(if (compact) 1f else 4.1f)
            .graphicsLayer { this.alpha = alpha.coerceIn(0f, 1f) }
            .clearAndSetSemantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val height = maxHeight
        val fontScale = LocalDensity.current.fontScale
        val signatureStyle = TextStyle(
            fontSize = (height.value * 0.24f / fontScale).sp,
            lineHeight = (height.value * 0.28f / fontScale).sp,
            letterSpacing = (height.value * 0.045f / fontScale).sp,
        )
        if (!compact && artwork != R.drawable.app_logo_wordmark) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(artwork), contentDescription = null,
                    modifier = Modifier.height(height * 0.68f), contentScale = contentScale,
                )
                Text("ENHANCED", style = signatureStyle, color = colors.TextSecondary, maxLines = 1)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.height(height * 0.86f).aspectRatio(1f)) {
                    drawRoundRect(accent.copy(alpha = 0.12f), cornerRadius = CornerRadius(size.width * 0.22f))
                    val aperture = Path().apply {
                        moveTo(size.width * 0.20f, size.height * 0.20f)
                        lineTo(size.width * 0.44f, size.height * 0.20f)
                        lineTo(size.width * 0.28f, size.height * 0.80f)
                        lineTo(size.width * 0.20f, size.height * 0.80f)
                        close()
                        moveTo(size.width * 0.72f, size.height * 0.20f)
                        lineTo(size.width * 0.80f, size.height * 0.20f)
                        lineTo(size.width * 0.80f, size.height * 0.80f)
                        lineTo(size.width * 0.56f, size.height * 0.80f)
                        close()
                    }
                    drawPath(aperture, accent)
                    val light = Path().apply {
                        moveTo(size.width * 0.43f, size.height * 0.34f)
                        lineTo(size.width * 0.66f, size.height * 0.50f)
                        lineTo(size.width * 0.43f, size.height * 0.66f)
                        close()
                    }
                    drawPath(light, colors.TextPrimary)
                }
                if (!compact) {
                    Spacer(Modifier.width(height * 0.24f))
                    Column {
                        Text("NUVIO", style = TextStyle(
                            fontSize = (height.value * 0.50f / fontScale).sp,
                            lineHeight = (height.value * 0.54f / fontScale).sp,
                            letterSpacing = (height.value * 0.02f / fontScale).sp,
                            fontWeight = FontWeight.SemiBold,
                        ), color = colors.TextPrimary, maxLines = 1)
                        Text("ENHANCED", style = signatureStyle, color = colors.TextSecondary, maxLines = 1)
                    }
                }
            }
        }
    }
}
