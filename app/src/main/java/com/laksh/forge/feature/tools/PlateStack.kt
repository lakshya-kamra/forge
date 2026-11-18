package com.laksh.forge.feature.tools

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/**
 * A stable colour per plate denomination, taken from the theme so the stack follows light,
 * dark and dynamic colour. The second lap of the palette is translucent so all eight
 * denominations stay distinguishable without inventing new colours.
 */
fun plateColor(kg: Double, scheme: ColorScheme): Color {
    val palette = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.onSurfaceVariant)
    val idx = GymProfile.DENOMINATIONS.indexOfFirst { PlateMath.roundKg(it) == PlateMath.roundKg(kg) }
        .let { if (it < 0) GymProfile.DENOMINATIONS.size else it }
    val base = palette[idx % palette.size]
    return if (idx >= palette.size) base.copy(alpha = 0.62f) else base
}

/**
 * One side of a loaded barbell: the inner collar, the sleeve, then plates heaviest-first
 * from the collar outward, exactly as they go on. Plate height and thickness scale with
 * weight so a 25 reads as bigger than a 2.5 without labels.
 *
 * The reveal animation is read only inside the draw lambda, so loading a new stack redraws
 * frames without recomposing anything.
 */
@Composable
fun PlateStack(
    perSide: List<Double>,
    modifier: Modifier = Modifier,
    heaviestKg: Double = GymProfile.DENOMINATIONS.first()
) {
    val scheme = MaterialTheme.colorScheme
    val sleeveColor = scheme.onSurfaceVariant
    val colors = remember(perSide, scheme) { perSide.map { plateColor(it, scheme) } }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val reveal = remember { Animatable(0f) }

    LaunchedEffect(perSide) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, spec)
    }

    val description = if (perSide.isEmpty()) "Empty bar" else
        "Per side: " + perSide.joinToString(", ") { Units.fmt(it) + " kg" }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(132.dp)
            .semantics { contentDescription = description }
    ) {
        val h = size.height
        val cy = h / 2f
        val sleeveH = h * 0.12f
        val shaftW = 14.dp.toPx()
        val stopW = 9.dp.toPx()
        val gap = 2.dp.toPx()

        // Shaft stub, inner collar (the stop plates sit against), then the sleeve.
        drawRoundRect(
            color = sleeveColor.copy(alpha = 0.45f),
            topLeft = Offset(0f, cy - sleeveH * 0.35f),
            size = Size(shaftW, sleeveH * 0.7f),
            cornerRadius = CornerRadius(sleeveH * 0.35f)
        )
        drawRoundRect(
            color = sleeveColor,
            topLeft = Offset(shaftW, cy - h * 0.2f),
            size = Size(stopW, h * 0.4f),
            cornerRadius = CornerRadius(3.dp.toPx())
        )
        val sleeveStart = shaftW + stopW
        drawRoundRect(
            color = sleeveColor.copy(alpha = 0.3f),
            topLeft = Offset(sleeveStart, cy - sleeveH / 2f),
            size = Size(size.width - sleeveStart, sleeveH),
            cornerRadius = CornerRadius(sleeveH / 2f)
        )

        if (perSide.isEmpty()) return@Canvas

        val fracs = perSide.map { sqrt(it / heaviestKg).coerceIn(0.3, 1.0).toFloat() }
        val widths = fracs.map { (6f + 13f * it).dp.toPx() }
        val needed = widths.sum() + gap * (widths.size + 1)
        val available = size.width - sleeveStart - 8.dp.toPx()
        // A huge stack (lots of small plates) is squeezed rather than clipped off the sleeve.
        val squeeze = if (needed > available) available / needed else 1f

        val progress = reveal.value
        val n = perSide.size
        var x = sleeveStart + gap * squeeze
        perSide.indices.forEach { i ->
            val w = widths[i] * squeeze
            // Plates slide on one after another: plate i starts once the reveal passes i/n.
            val local = (progress * n - i).coerceIn(0f, 1.05f)
            if (local > 0f) {
                val ph = h * fracs[i] * local
                drawRoundRect(
                    color = colors[i],
                    topLeft = Offset(x, cy - ph / 2f),
                    size = Size(w, ph),
                    cornerRadius = CornerRadius(minOf(5.dp.toPx(), w / 2f))
                )
            }
            x += w + gap * squeeze
        }
    }
}
