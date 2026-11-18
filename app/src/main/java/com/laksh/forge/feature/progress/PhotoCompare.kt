package com.laksh.forge.feature.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import coil.compose.AsyncImage
import com.laksh.forge.data.db.ProgressPhoto
import com.laksh.forge.util.Dates
import java.io.File

enum class CompareMode(val label: String) { SIDE_BY_SIDE("Side by side"), SLIDER("Slider") }

/** Two photos of the same pose from different dates, side by side or under a drag divider. */
@Composable
fun PhotoCompareView(
    before: ProgressPhoto?,
    after: ProgressPhoto?,
    mode: CompareMode,
    modifier: Modifier = Modifier
) {
    when (mode) {
        CompareMode.SIDE_BY_SIDE -> Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelledPhoto(before, "Before", Modifier.weight(1f))
            LabelledPhoto(after, "After", Modifier.weight(1f))
        }
        CompareMode.SLIDER -> SliderCompare(before, after, modifier)
    }
}

@Composable
private fun LabelledPhoto(photo: ProgressPhoto?, label: String, modifier: Modifier) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (photo != null) {
                AsyncImage(
                    model = File(photo.path),
                    contentDescription = "$label photo from ${Dates.shortLabel(photo.localDate)}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Outlined.ImageNotSupported, "No photo for this pose", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "$label · ${photo?.let { Dates.shortLabel(it.localDate) } ?: "-"}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * The "after" photo fills the frame and the "before" photo is clipped to the divider. The
 * divider position is read only in the draw phase, so dragging redraws without recomposing.
 */
@Composable
private fun SliderCompare(before: ProgressPhoto?, after: ProgressPhoto?, modifier: Modifier) {
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
                    detectTapGestures { pos ->
                        fraction = (pos.x / size.width).coerceIn(0f, 1f)
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                        onDragEnd = { haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) }
                    ) { change, _ ->
                        change.consume()
                        fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }
        ) {
            if (after != null) {
                AsyncImage(
                    model = File(after.path),
                    contentDescription = "After, ${Dates.shortLabel(after.localDate)}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (before != null) {
                AsyncImage(
                    model = File(before.path),
                    contentDescription = "Before, ${Dates.shortLabel(before.localDate)}",
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
            Text(
                before?.let { "Before · ${Dates.shortLabel(it.localDate)}" } ?: "No before photo",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Text(
                after?.let { "After · ${Dates.shortLabel(it.localDate)}" } ?: "No after photo",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Drag across the photo to compare. Small changes in light and pose can look like big changes in your body.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
