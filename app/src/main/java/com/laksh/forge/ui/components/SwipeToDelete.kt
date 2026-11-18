package com.laksh.forge.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.laksh.forge.ui.theme.LocalHapticsEnabled
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The app-wide snackbar, hosted by the shell above the floating navigation bar. Null outside
 * the shell (tests, previews); callers fall back to a local state that simply never shows.
 */
val LocalForgeSnackbar = compositionLocalOf<SnackbarHostState?> { null }

/**
 * Shows "[message] · Undo" and routes the outcome. [onExpired] runs when the snackbar times
 * out or is swiped away, which is where irreversible clean-up (deleting a photo file) belongs.
 * If the calling coroutine is cancelled first, neither callback runs - callers must treat
 * pending work as "still undoable" and clean up on their own teardown.
 */
suspend fun SnackbarHostState.showUndo(
    message: String,
    onUndo: () -> Unit,
    onExpired: () -> Unit = {},
    actionLabel: String = "Undo"
) {
    // Only one undo is meaningful at a time; a newer action replaces the older prompt.
    currentSnackbarData?.dismiss()
    when (showSnackbar(message, actionLabel = actionLabel, withDismissAction = false, duration = SnackbarDuration.Short)) {
        SnackbarResult.ActionPerformed -> onUndo()
        SnackbarResult.Dismissed -> onExpired()
    }
}

/**
 * A row that deletes when swiped toward the start, PixelPlayer's queue gesture
 * (design.md section 10):
 * - the first 60 dp of drag moves the row at most 20 dp, so a sloppy vertical scroll never
 *   starts a delete;
 * - past that the row springs onto the finger (damping 0.8, low stiffness);
 * - crossing 40 % of the width arms the delete with a threshold haptic (and a release haptic
 *   when backing out);
 * - releasing armed flies the row off and calls [onDelete]; releasing early springs it back
 *   with a bouncy spring.
 *
 * Every visual is read in the draw/layer phase, so dragging never recomposes the row.
 * A "Delete" accessibility action replaces the gesture for TalkBack users.
 */
@Composable
fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(16.dp),
    deleteLabel: String = "Delete",
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val tensionZone = with(density) { 60.dp.toPx() }
    val tensionTravel = with(density) { 20.dp.toPx() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val view = LocalView.current
    val currentOnDelete by rememberUpdatedState(onDelete)

    val offset = remember { Animatable(0f) }
    // Finger position separate from the drawn offset: in the tension zone they differ.
    val raw = remember { FloatArray(1) }
    // Once the finger leaves the tension zone the row follows it for the rest of the drag;
    // dropping back into tension mode mid-drag would make the row jump.
    val escaped = remember { BooleanArray(1) }
    var width by remember { mutableIntStateOf(0) }
    var armed by remember { mutableStateOf(false) }

    val follow = remember { spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow) }
    val springBack = remember {
        spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
    }

    fun armThreshold(): Float = width * 0.4f

    val dragState = rememberDraggableState { delta ->
        // Only end-to-start deletes; dragging the other way is clamped at rest.
        raw[0] = (raw[0] + delta).coerceAtMost(0f)
        val finger = raw[0]
        val distance = abs(finger)
        if (distance > tensionZone) escaped[0] = true
        scope.launch {
            if (!escaped[0]) {
                offset.snapTo(-distance * (tensionTravel / tensionZone))
            } else {
                offset.animateTo(finger, follow)
            }
        }
        val nowArmed = width > 0 && distance >= armThreshold()
        if (nowArmed != armed) {
            armed = nowArmed
            if (nowArmed) {
                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            } else if (hapticsEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                view.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE)
            }
        }
    }

    val armedColor = MaterialTheme.colorScheme.error
    val idleColor = MaterialTheme.colorScheme.errorContainer
    val background by animateColorAsState(
        targetValue = if (armed) armedColor else idleColor,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "swipeBg"
    )
    val iconTint = if (armed) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onErrorContainer

    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(deleteLabel) { currentOnDelete(); true }
                )
            }
    ) {
        // Background only paints once the row has moved, so idle rows cost one layer.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = if (offset.value < -0.5f) 1f else 0f }
                .clip(shape)
                .background(background),
            contentAlignment = Alignment.CenterEnd
        ) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier
                    .padding(end = 24.dp)
                    .size(24.dp)
                    .graphicsLayer {
                        val t = if (width == 0) 0f else (abs(offset.value) / (width * 0.4f)).coerceIn(0f, 1f)
                        val s = 0.6f + 0.5f * t
                        scaleX = s
                        scaleY = s
                    }
            )
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset.value }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    onDragStopped = { _ ->
                        scope.launch {
                            val commit = armed
                            raw[0] = 0f
                            escaped[0] = false
                            armed = false
                            if (commit) {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                offset.animateTo(-width.toFloat(), follow)
                                currentOnDelete()
                                // Normally the row leaves composition here and this scope is
                                // cancelled. If the parent keeps it (the delete was refused),
                                // bring it back instead of leaving a hole.
                                delay(1_000)
                                offset.animateTo(0f, springBack)
                            } else {
                                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                offset.animateTo(0f, springBack)
                            }
                        }
                    }
                )
        ) {
            content()
        }
    }
}
