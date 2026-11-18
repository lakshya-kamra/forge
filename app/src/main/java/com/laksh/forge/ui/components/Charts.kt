package com.laksh.forge.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laksh.forge.ui.theme.ForgeEasing
import com.laksh.forge.ui.theme.LocalReduceMotion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ChartPoint(val x: Float, val y: Float, val label: String = "")

// ---------------------------------------------------------------- calorie ring

/**
 * The headline figure on Today. Sweeps with the theme's slow spatial spring (it overshoots a
 * hair and settles, which reads as "alive" rather than mechanical) and turns to the error
 * colour as the target is passed, so going over is legible without reading the number.
 *
 * All animated values are read inside the Canvas draw lambda: the sweep re-draws one node and
 * never recomposes the card. [centerContent] replaces the three text lines when a screen wants
 * richer content (e.g. an [AnimatedCounter]) in the hole.
 */
@Composable
fun CalorieRing(
    consumed: Double,
    target: Int,
    modifier: Modifier = Modifier,
    ringColor: Color = MaterialTheme.colorScheme.primary,
    overColor: Color = MaterialTheme.colorScheme.error,
    centerTop: String,
    centerMain: String,
    centerBottom: String,
    centerContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    val fraction = if (target <= 0) 0f else (consumed / target).toFloat()
    val reduce = LocalReduceMotion.current
    val animated = animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1.5f),
        animationSpec = if (reduce) snap<Float>() else MaterialTheme.motionScheme.slowSpatialSpec<Float>(),
        label = "ring"
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val isOver = fraction > 1.02f
    val activeColor = if (isOver) overColor else ringColor

    Box(modifier = modifier.size(210.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            val value = animated.value.coerceAtLeast(0f)

            drawArc(
                color = track,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            val sweep = 270f * value.coerceAtMost(1f)
            if (sweep > 0.5f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(
                            activeColor.copy(alpha = 0.65f),
                            activeColor,
                            activeColor.copy(alpha = 0.65f)
                        ),
                        center = center
                    ),
                    startAngle = 135f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }

            // Overshoot draws a second, thinner lap so "how far over" stays readable.
            if (value > 1f) {
                drawArc(
                    color = overColor,
                    startAngle = 135f,
                    sweepAngle = 270f * (value - 1f).coerceAtMost(1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke / 2.6f, cap = StrokeCap.Round)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (centerContent != null) {
                centerContent()
            } else {
                Text(
                    centerTop,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    centerMain,
                    style = MaterialTheme.typography.displayMediumEmphasized,
                    color = if (isOver) overColor else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    centerBottom,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------- line chart

/**
 * Weight/trend chart. Draws an optional raw scatter behind a smoothed line so the user can
 * see both the noise and the signal - the whole point of trend weighing.
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    rawPoints: List<ChartPoint> = emptyList(),
    yFormatter: (Float) -> String = { it.roundToInt().toString() },
    height: androidx.compose.ui.unit.Dp = 200.dp,
    goalLine: Float? = null
) {
    if (points.size < 2) {
        Box(
            modifier
                .fillMaxWidth()
                .height(height),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Not enough data yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val axisText = MaterialTheme.colorScheme.onSurfaceVariant
    val rawColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

    // The reveal wipes left to right once per data set; reduce-motion shows it complete.
    val reduce = LocalReduceMotion.current
    val progress = remember(points) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(points) {
        if (progress.value < 1f) progress.animateTo(1f, tween(850, easing = ForgeEasing.Emphasized))
    }

    BoxWithConstraints(modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.fillMaxSize()) {
            val leftPad = 44.dp.toPx()
            val bottomPad = 22.dp.toPx()
            val topPad = 10.dp.toPx()
            val chartW = size.width - leftPad
            val chartH = size.height - bottomPad - topPad

            val allY = points.map { it.y } + rawPoints.map { it.y } + listOfNotNull(goalLine)
            var minY = allY.min()
            var maxY = allY.max()
            if (abs(maxY - minY) < 0.001f) { minY -= 1f; maxY += 1f }
            val pad = (maxY - minY) * 0.12f
            minY -= pad; maxY += pad

            val minX = points.first().x
            val maxX = points.last().x
            val spanX = (maxX - minX).takeIf { it != 0f } ?: 1f

            fun px(x: Float) = leftPad + (x - minX) / spanX * chartW
            fun py(y: Float) = topPad + (1f - (y - minY) / (maxY - minY)) * chartH

            // Horizontal grid + y labels
            val ticks = 4
            for (i in 0..ticks) {
                val v = minY + (maxY - minY) * i / ticks
                val y = py(v)
                drawLine(grid, Offset(leftPad, y), Offset(size.width, y), strokeWidth = 1f)
                val txt = measurer.measure(
                    yFormatter(v),
                    TextStyle(fontSize = 10.sp, color = axisText)
                )
                drawText(txt, topLeft = Offset(0f, y - txt.size.height / 2f))
            }

            goalLine?.let { g ->
                val y = py(g)
                drawLine(
                    color = lineColor.copy(alpha = 0.55f),
                    start = Offset(leftPad, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                )
            }

            // Raw scatter behind the trend line.
            rawPoints.forEach { p ->
                drawCircle(rawColor, radius = 2.5.dp.toPx(), center = Offset(px(p.x), py(p.y)))
            }

            val screen = points.map { Offset(px(it.x), py(it.y)) }
            val path = smoothPath(screen)

            // Gradient fill under the curve.
            val fill = Path().apply {
                addPath(path)
                lineTo(screen.last().x, topPad + chartH)
                lineTo(screen.first().x, topPad + chartH)
                close()
            }
            clipRect(left = 0f, top = 0f, right = leftPad + chartW * progress.value, bottom = size.height) {
                drawPath(
                    fill,
                    Brush.verticalGradient(
                        listOf(lineColor.copy(alpha = 0.28f), Color.Transparent),
                        startY = topPad,
                        endY = topPad + chartH
                    )
                )
                drawPath(
                    path,
                    color = lineColor,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Emphasise the most recent value.
            val last = screen.last()
            if (progress.value > 0.98f) {
                drawCircle(lineColor.copy(alpha = 0.25f), radius = 8.dp.toPx(), center = last)
                drawCircle(lineColor, radius = 4.dp.toPx(), center = last)
            }

            // X labels: first and last only, to stay uncluttered on a phone.
            drawXLabel(measurer, points.first().label, leftPad, size.height - 2f, axisText, alignStart = true)
            drawXLabel(measurer, points.last().label, size.width, size.height - 2f, axisText, alignStart = false)
        }
    }
}

// ---------------------------------------------------------------- bar chart

/** Daily calories against target. Bars above target tint toward the error colour. */
@Composable
fun BarChart(
    points: List<ChartPoint>,
    target: Float?,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    overColor: Color = MaterialTheme.colorScheme.error,
    height: androidx.compose.ui.unit.Dp = 180.dp,
    yFormatter: (Float) -> String = { it.roundToInt().toString() }
) {
    if (points.isEmpty()) {
        Box(
            modifier
                .fillMaxWidth()
                .height(height),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Nothing logged yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val axisText = MaterialTheme.colorScheme.onSurfaceVariant
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest

    val reduce = LocalReduceMotion.current
    val progress = remember(points) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(points) {
        if (progress.value < 1f) progress.animateTo(1f, tween(700, easing = ForgeEasing.Emphasized))
    }

    Box(modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.fillMaxSize()) {
            val leftPad = 44.dp.toPx()
            val bottomPad = 22.dp.toPx()
            val topPad = 10.dp.toPx()
            val chartW = size.width - leftPad
            val chartH = size.height - bottomPad - topPad

            val maxY = max(points.maxOf { it.y }, target ?: 0f) * 1.15f
            fun py(v: Float) = topPad + (1f - v / maxY) * chartH

            for (i in 0..3) {
                val v = maxY * i / 3
                val y = py(v)
                drawLine(grid, Offset(leftPad, y), Offset(size.width, y), strokeWidth = 1f)
                val txt = measurer.measure(yFormatter(v), TextStyle(fontSize = 10.sp, color = axisText))
                drawText(txt, topLeft = Offset(0f, y - txt.size.height / 2f))
            }

            val slot = chartW / points.size
            val barW = min(slot * 0.62f, 22.dp.toPx())

            points.forEachIndexed { i, p ->
                val cx = leftPad + slot * i + slot / 2
                val full = py(p.y)
                val base = topPad + chartH
                val animatedTop = base - (base - full) * progress.value
                val color = if (target != null && p.y > target * 1.02f) overColor else barColor

                // Faint track so days with nothing logged still read as days.
                drawRoundRect(
                    color = trackColor.copy(alpha = 0.4f),
                    topLeft = Offset(cx - barW / 2, topPad + chartH * 0.75f),
                    size = Size(barW, chartH * 0.25f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2)
                )
                if (p.y > 0f) {
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(color, color.copy(alpha = 0.72f)),
                            startY = animatedTop,
                            endY = base
                        ),
                        topLeft = Offset(cx - barW / 2, animatedTop),
                        size = Size(barW, (base - animatedTop).coerceAtLeast(0f)),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2)
                    )
                }
            }

            target?.let { t ->
                val y = py(t)
                drawLine(
                    color = axisText,
                    start = Offset(leftPad, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                )
            }

            drawXLabel(measurer, points.first().label, leftPad, size.height - 2f, axisText, alignStart = true)
            drawXLabel(measurer, points.last().label, size.width, size.height - 2f, axisText, alignStart = false)
        }
    }
}

// ---------------------------------------------------------------- helpers

/** Catmull-Rom style smoothing, converted to cubic beziers. */
private fun smoothPath(pts: List<Offset>): Path {
    val path = Path()
    if (pts.isEmpty()) return path
    path.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 2) {
        path.lineTo(pts[1].x, pts[1].y)
        return path
    }
    for (i in 0 until pts.size - 1) {
        val p0 = pts[max(0, i - 1)]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[min(pts.size - 1, i + 2)]
        // Tension of 6 keeps the curve close to the data without visible overshoot.
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
    return path
}

private fun DrawScope.drawXLabel(
    measurer: TextMeasurer,
    label: String,
    x: Float,
    y: Float,
    color: Color,
    alignStart: Boolean
) {
    if (label.isBlank()) return
    val txt = measurer.measure(label, TextStyle(fontSize = 10.sp, color = color))
    val left = if (alignStart) x else x - txt.size.width
    drawText(txt, topLeft = Offset(left, y - txt.size.height))
}
