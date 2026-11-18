package com.laksh.forge.ui.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.laksh.forge.ui.theme.LocalReduceMotion
import com.laksh.forge.ui.theme.isDark
import kotlin.math.abs

/**
 * Space the floating navigation bar occupies at the bottom of the window (bar + margin +
 * system navigation inset). Top-level screens that draw underneath the bar pad the end of
 * their lists by this much; it is 0 everywhere else.
 */
val LocalFloatingBarClearance = compositionLocalOf { 0.dp }

/** Height of the floating bar itself, excluding margins and insets. */
val FloatingNavBarHeight: Dp = 68.dp

@Immutable
data class FloatingNavItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
)

private val IndicatorWidth = 56.dp
private val IndicatorHeight = 32.dp
private val ItemTopPadding = 8.dp

/**
 * PixelPlayer-style floating pill navigation (design.md 3.1). One indicator pill is drawn
 * behind the items and slides between them on the theme's fast spatial spring; while it moves
 * it stretches with its velocity and squashes slightly, so a jump across three tabs reads as a
 * single liquid motion rather than a fade. All of that happens in the draw phase.
 *
 * Ripple is off: the bouncing pill and icon are the feedback.
 */
@Composable
fun FloatingNavBar(
    items: List<FloatingNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current
    val indicatorColor = scheme.primaryContainer
    val indicator = remember { Animatable(selectedIndex.coerceAtLeast(0).toFloat()) }
    val slideSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(selectedIndex, reduce) {
        if (selectedIndex < 0) return@LaunchedEffect
        if (reduce) indicator.snapTo(selectedIndex.toFloat())
        else indicator.animateTo(selectedIndex.toFloat(), slideSpec)
    }

    Surface(
        modifier = modifier.height(FloatingNavBarHeight),
        shape = CircleShape,
        color = if (scheme.isDark) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest,
        shadowElevation = 6.dp
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
                .drawBehind {
                    if (selectedIndex < 0 || items.isEmpty()) return@drawBehind
                    val itemW = size.width / items.size
                    // Velocity is in "tabs per second"; scale it into a stretch, capped so a
                    // long jump never turns the pill into a bar.
                    val stretch = (abs(indicator.velocity) * 7.dp.toPx())
                        .coerceAtMost(30.dp.toPx())
                    val w = IndicatorWidth.toPx() + stretch
                    val h = IndicatorHeight.toPx() - stretch * 0.12f
                    val cx = itemW * (indicator.value + 0.5f)
                    val cy = ItemTopPadding.toPx() + IndicatorHeight.toPx() / 2f
                    drawRoundRect(
                        color = indicatorColor,
                        topLeft = Offset(cx - w / 2f, cy - h / 2f),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(h / 2f)
                    )
                },
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            items.forEachIndexed { index, item ->
                NavItem(
                    item = item,
                    selected = index == selectedIndex,
                    enabled = enabled,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    item: FloatingNavItem,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Color>()

    // One transition per item, every visual derived from it (design.md 2.4).
    val transition = updateTransition(selected, label = "navItem")
    val iconScale = transition.animateFloat(
        transitionSpec = { spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium) },
        label = "iconScale"
    ) { if (it) 1.1f else 1f }
    val iconTint by transition.animateColor(transitionSpec = { effects }, label = "iconTint") {
        if (it) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    }
    val labelColor by transition.animateColor(transitionSpec = { effects }, label = "labelTint") {
        if (it) scheme.onSurface else scheme.onSurfaceVariant
    }

    Column(
        modifier
            .fillMaxHeight()
            .pressScale(source, pressedScale = 0.9f)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Tab,
                interactionSource = source,
                indication = null,
                onClick = {
                    if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onClick()
                }
            )
            .padding(top = ItemTopPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(width = IndicatorWidth, height = IndicatorHeight),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (selected) item.selectedIcon else item.icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        scaleX = iconScale.value
                        scaleY = iconScale.value
                    }
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            item.label,
            style = if (selected) MaterialTheme.typography.labelMediumEmphasized
            else MaterialTheme.typography.labelMedium,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The primary action next to the bar. A cookie-shaped container that twists a little while
 * pressed and springs back with overshoot; the icon stays upright. Shape, rotation and shadow
 * all live in one graphics layer.
 */
@Composable
fun ExpressiveFab(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp
) {
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val reduce = LocalReduceMotion.current
    val pressed by source.collectIsPressedAsState()
    val rotation = animateFloatAsState(
        targetValue = if (pressed && !reduce) 30f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>(),
        label = "fabTwist"
    )
    val fabShape = MaterialShapes.Cookie4Sided.toShape()
    val container = MaterialTheme.colorScheme.primary

    Box(
        modifier
            .size(size)
            .pressScale(source, pressedScale = 0.9f)
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClickLabel = contentDescription
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onClick()
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    rotationZ = rotation.value
                    shape = fabShape
                    clip = true
                    shadowElevation = 6.dp.toPx()
                }
                .background(container)
        )
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(28.dp)
        )
    }
}
