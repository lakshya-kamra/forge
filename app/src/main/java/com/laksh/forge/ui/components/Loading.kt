package com.laksh.forge.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.laksh.forge.ui.theme.LocalReduceMotion
import com.laksh.forge.ui.theme.cardContainer

/**
 * The Expressive shape-morphing loading indicator, with an optional status line under it.
 * [contained] puts it on its own tonal container, for when it floats over content.
 */
@Composable
fun ExpressiveLoading(
    modifier: Modifier = Modifier,
    message: String? = null,
    contained: Boolean = false,
    size: Dp = 48.dp
) {
    Column(
        modifier = modifier.semantics { contentDescription = message ?: "Loading" },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (contained) {
            ContainedLoadingIndicator(Modifier.size(size))
        } else {
            LoadingIndicator(Modifier.size(size), color = MaterialTheme.colorScheme.primary)
        }
        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Skeleton fill with a light sweep. The sweep position is read only inside the draw lambda,
 * so the infinite animation re-draws this one node instead of recomposing the screen.
 */
@Composable
fun Modifier.shimmer(shape: Shape = RoundedCornerShape(12.dp)): Modifier {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    if (LocalReduceMotion.current) return this.clip(shape).background(base)

    val transition = rememberInfiniteTransition(label = "shimmer")
    val sweep = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerSweep"
    )
    return this
        .clip(shape)
        .drawWithCache {
            val band = size.width * 0.6f
            // Built once per size; the sweep moves it with a translate instead of a new Brush.
            val brush = Brush.linearGradient(
                colors = listOf(base, highlight, base),
                start = Offset.Zero,
                end = Offset(band, size.height)
            )
            onDrawBehind {
                drawRect(base)
                val x = -band + sweep.value * (size.width + band)
                translate(left = x) {
                    drawRect(brush, topLeft = Offset(-x, 0f), size = size)
                }
            }
        }
}

/** A single skeleton bar or block. */
@Composable
fun ShimmerBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp)
) {
    Box(modifier.shimmer(shape))
}

/** Placeholder card shaped like a typical list card, for first-load states. */
@Composable
fun ShimmerCard(
    modifier: Modifier = Modifier,
    lines: Int = 3,
    height: Dp? = null
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.cardContainer)
            .padding(18.dp)
            .then(if (height != null) Modifier.height(height) else Modifier),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShimmerBlock(Modifier.size(36.dp), CircleShape)
            Spacer(Modifier.width(12.dp))
            ShimmerBlock(
                Modifier
                    .fillMaxWidth(0.45f)
                    .height(16.dp)
            )
        }
        repeat(lines.coerceAtLeast(0)) { i ->
            ShimmerBlock(
                Modifier
                    .fillMaxWidth(if (i == lines - 1) 0.6f else 1f)
                    .height(12.dp)
            )
        }
    }
}
