package com.laksh.forge.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.laksh.forge.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay

/**
 * Shrinks the element while [interactionSource] is pressed and springs it back with the
 * theme's fast spatial spring (it overshoots slightly on release - the Expressive "squish").
 *
 * The scale is read inside `graphicsLayer`, so a press only re-draws the layer; nothing
 * re-measures or recomposes per frame.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.94f
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    val scale = animateFloatAsState(
        targetValue = if (pressed && !reduce) pressedScale else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>(),
        label = "pressScale"
    )
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * Clickable with press-scale, ripple and a haptic tick. Haptics go through
 * [LocalHapticFeedback], which `ForgeTheme` gates on Settings -> Appearance -> Haptics, so a
 * user who turned them off never feels one.
 *
 * Clip before calling this if the ripple should follow a shape.
 */
@Composable
fun Modifier.bounceClick(
    onClick: () -> Unit,
    enabled: Boolean = true,
    haptic: HapticFeedbackType? = HapticFeedbackType.ContextClick,
    pressedScale: Float = 0.96f,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    showRipple: Boolean = true
): Modifier {
    val source = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val indication = if (showRipple) LocalIndication.current else null
    return this
        .pressScale(source, pressedScale)
        .clickable(
            interactionSource = source,
            indication = indication,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role
        ) {
            if (haptic != null) haptics.performHapticFeedback(haptic)
            onClick()
        }
}

/**
 * One-time entrance: fade up from 24 dp below, delayed by [index] x 40 ms (capped at six so
 * long lists don't wait). When [play] is false the element is simply there - callers pass
 * false after the first appearance so switching tabs never replays it.
 */
@Composable
fun Modifier.enterStagger(index: Int, play: Boolean): Modifier {
    val reduce = LocalReduceMotion.current
    val animate = play && !reduce
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            delay(index.coerceIn(0, 6) * 40L)
            progress.animateTo(1f, spec)
        }
    }
    return this.graphicsLayer {
        val p = progress.value
        // The spatial spring overshoots; alpha must not, so it is clamped.
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 24.dp.toPx()
    }
}
