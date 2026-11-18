package com.laksh.forge.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.laksh.forge.ui.theme.LocalReduceMotion
import com.laksh.forge.ui.theme.cardContainer
import kotlin.math.roundToInt

/** Cached once: shapes are allocated per call otherwise, and cards appear in every list. */
private val CardShape = RoundedCornerShape(24.dp)

/**
 * The standard container used everywhere: generous 24 dp corners, no elevation (tone does the
 * separating), and a springy press-squish when it is clickable.
 */
@Composable
fun ForgeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    color: Color = MaterialTheme.colorScheme.cardContainer,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        val source = remember { MutableInteractionSource() }
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .pressScale(source, pressedScale = 0.98f),
            shape = CardShape,
            color = color,
            interactionSource = source
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = CardShape,
            color = color
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 0.dp, top = 12.dp, bottom = 2.dp)
            .heightIn(min = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f, fill = false)
                .semantics { heading() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (action != null && onAction != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .bounceClick(onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * A labelled horizontal progress bar - used for macros and any "x of y" figure.
 * The fill is drawn, not laid out, so the animation never re-measures the row; the number
 * rolls with [AnimatedCounter].
 */
@Composable
fun MacroBar(
    label: String,
    current: Double,
    target: Int,
    color: Color,
    modifier: Modifier = Modifier,
    unit: String = "g"
) {
    val fraction = if (target <= 0) 0f else (current / target).toFloat().coerceIn(0f, 1.3f)
    // design.md 2.3: progress values settle with a non-bouncy, low-stiffness spring.
    val animated = animateFloatAsState(
        targetValue = fraction,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "macro"
    )
    val over = current > target * 1.02
    val track = MaterialTheme.colorScheme.surfaceContainerHighest

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedCounter(
                    value = current.roundToInt(),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = if (over) color else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    " / $target$unit",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .drawBehind {
                    val r = CornerRadius(size.height / 2f)
                    drawRoundRect(track, cornerRadius = r)
                    val w = size.width * animated.value.coerceIn(0f, 1f)
                    if (w > 0f) {
                        // Never narrower than its own height, so tiny values still read as a dot.
                        val fillW = w.coerceAtLeast(size.height)
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                listOf(color.copy(alpha = 0.7f), color),
                                startX = 0f,
                                endX = fillW
                            ),
                            size = Size(fillW, size.height),
                            cornerRadius = r
                        )
                    }
                }
        )
    }
}

/** Small labelled figure, used in rows of three or four. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    sub: String? = null
) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        if (sub != null) {
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    filled: Boolean = false,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.bounceClick(onClick = onClick, pressedScale = 0.92f) else Modifier)
            .then(
                if (filled) Modifier.background(color.copy(alpha = 0.16f))
                else Modifier.border(1.dp, color.copy(alpha = 0.4f), CircleShape)
            )
            .heightIn(min = 34.dp)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Shown wherever a list is empty, so no screen is ever a blank rectangle. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    val reduce = LocalReduceMotion.current
    // The badge pops in once with the theme's spatial spring; it is the screen's one
    // moment of personality when there is nothing else to look at.
    val pop = remember { Animatable(if (reduce) 1f else 0.6f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) { if (pop.value < 1f) pop.animateTo(1f, spec) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(88.dp)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                    rotationZ = (1f - pop.value) * -40f
                }
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** Error card body: the exception message verbatim plus an optional retry. */
@Composable
fun ErrorNotice(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    ForgeCard(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            Pill(
                "Try again",
                color = MaterialTheme.colorScheme.onErrorContainer,
                filled = true,
                onClick = onRetry
            )
        }
    }
}
