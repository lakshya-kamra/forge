package com.laksh.forge.feature.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class CompareMode(val label: String) { SIDE_BY_SIDE("Side by side"), SLIDER("Slider") }

private val photoDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

/** Progress photos are months apart, so the year is always shown. */
fun photoDateLabel(date: LocalDate?): String = date?.format(photoDateFormat) ?: "No date"

/**
 * Every chosen photo in date order, two to a screen width; with more than two the row swipes
 * and snaps a photo at a time, so any neighbouring pair lines up side by side.
 */
@Composable
fun SideBySideStrip(photos: List<SessionPhoto>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 8.dp
        val itemWidth = (maxWidth - gap) / 2
        val listState = rememberLazyListState()
        LazyRow(
            state = listState,
            // Start, not centre: with two photos per width, centring one would cut both neighbours.
            flingBehavior = rememberSnapFlingBehavior(lazyListState = listState, snapPosition = SnapPosition.Start),
            horizontalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(photos, key = { it.id }, contentType = { "photo" }) { photo ->
                LabelledPhoto(photo, Modifier.width(itemWidth).animateItem())
            }
        }
    }
}

@Composable
private fun LabelledPhoto(photo: SessionPhoto, modifier: Modifier) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Image(
                bitmap = photo.image,
                contentDescription = "Progress photo from ${photoDateLabel(photo.date)}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(photoDateLabel(photo.date), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The "after" photo fills the frame and the "before" photo is clipped to the divider. The
 * divider position is read only in the draw phase, so dragging redraws without recomposing.
 */
@Composable
fun SliderCompare(before: SessionPhoto?, after: SessionPhoto?, modifier: Modifier = Modifier) {
    var fraction by remember { mutableFloatStateOf(0.5f) }
    val haptics = LocalHapticFeedback.current
    val lineColor = Color.White
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .pointerInput(Unit) {
                    detectTapGestures { pos -> fraction = (pos.x / size.width).coerceIn(0f, 1f) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                        onDragEnd = { haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) }
                    ) { change, _ ->
                        change.consume()
                        fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (before == null && after == null) {
                Icon(Icons.Outlined.ImageNotSupported, "No photos chosen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (after != null) {
                Image(
                    bitmap = after.image,
                    contentDescription = "After, ${photoDateLabel(after.date)}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (before != null) {
                Image(
                    bitmap = before.image,
                    contentDescription = "Before, ${photoDateLabel(before.date)}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            clipRect(right = size.width * fraction) { this@drawWithContent.drawContent() }
                        }
                )
            }
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width * fraction
                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3.dp.toPx())
                drawCircle(lineColor, radius = 14.dp.toPx(), center = Offset(x, size.height / 2f))
                drawCircle(Color.Black.copy(alpha = 0.35f), radius = 5.dp.toPx(), center = Offset(x, size.height / 2f))
            }
            CornerLabel(before?.let { "Before · ${photoDateLabel(it.date)}" } ?: "No before photo", Modifier.align(Alignment.TopStart))
            CornerLabel(after?.let { "After · ${photoDateLabel(it.date)}" } ?: "No after photo", Modifier.align(Alignment.TopEnd))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Drag across the photo to compare. Small changes in light and pose can look like big changes in your body.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** White on a scrim: the photo underneath can be any colour, so theme colours can't be relied on. */
@Composable
private fun CornerLabel(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = modifier
            .padding(10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
