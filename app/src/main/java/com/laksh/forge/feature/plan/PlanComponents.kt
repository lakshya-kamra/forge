package com.laksh.forge.feature.plan

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.laksh.forge.domain.guardrails.Guardrails
import com.laksh.forge.domain.plan.BodyFatRange
import com.laksh.forge.domain.plan.MeasurementGuide

// Building blocks shared by onboarding, the plan screens, progress and profile settings.
// Internal so they stay inside this feature area.

/** A large selectable card. Selection morphs the corner radius and tints the container. */
@Composable
internal fun ChoiceCard(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null
) {
    val motion = MaterialTheme.motionScheme
    val haptics = LocalHapticFeedback.current
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        motion.fastEffectsSpec(),
        label = "choiceContainer"
    )
    val corner by animateDpAsState(if (selected) 28.dp else 18.dp, motion.fastSpatialSpec(), label = "choiceCorner")
    val scale by animateFloatAsState(if (selected) 1f else 0.985f, motion.fastSpatialSpec(), label = "choiceScale")
    val shape = RoundedCornerShape(corner)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .semantics { role = Role.RadioButton }
            .clickable(enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onClick()
            },
        shape = shape,
        color = container,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                Icon(
                    leading, null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Single-select connected button group (the M3 Expressive replacement for segmented buttons). */
@Composable
internal fun <T> ConnectedChoice(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        options.forEachIndexed { i, opt ->
            ToggleButton(
                checked = opt == selected,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(opt)
                },
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
                shapes = when {
                    options.size == 1 -> ToggleButtonDefaults.shapesFor(ToggleButtonDefaults.MinHeight)
                    i == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    i == options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(label(opt), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/** Decimal input that keeps its own draft so typing "72." is not reformatted mid-edit. */
@Composable
internal fun NumberField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    isError: Boolean = false,
    supporting: String? = null,
    decimal: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val cleaned = raw.filter { it.isDigit() || (decimal && (it == '.' || it == ',')) }.replace(',', '.')
            onValue(cleaned)
        },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        suffix = suffix?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    )
}

/** A tape-measure input with its protocol one tap away. */
@Composable
internal fun MeasurementInput(
    guide: MeasurementGuide,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false
) {
    var open by rememberSaveable(guide.title) { mutableStateOf(false) }
    val motion = MaterialTheme.motionScheme
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumberField(
                label = guide.title,
                value = value,
                onValue = onValue,
                suffix = "cm",
                isError = isError,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable { open = !open },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (open) Icons.Outlined.ExpandLess else Icons.Outlined.Info,
                    contentDescription = if (open) "Hide how to measure" else "How to measure ${guide.title}",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec())
        ) {
            Text(
                guide.howTo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(12.dp)
            )
        }
    }
}

enum class NoteTone { NEUTRAL, CAUTION, SUPPORT }

/** A calm callout. Caution uses tertiary, never error red - nothing here is the person's fault. */
@Composable
internal fun NoteCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    tone: NoteTone = NoteTone.NEUTRAL,
    icon: ImageVector = Icons.Outlined.Info,
    content: (@Composable () -> Unit)? = null
) {
    val (bg, fg) = when (tone) {
        NoteTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
        NoteTone.CAUTION -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        NoteTone.SUPPORT -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = fg)
        }
        if (body.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = fg)
        }
        if (content != null) {
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/**
 * Tap-to-call support lines from Guardrails.crisisResources (Tele MANAS first). Dialling uses
 * ACTION_DIAL, which needs no permission and lets the person decide whether to place the call.
 */
@Composable
internal fun SupportResources(modifier: Modifier = Modifier, intro: String? = null) {
    val context = LocalContext.current
    val resources = remember { Guardrails.crisisResources }
    NoteCard(
        title = "Support is available",
        body = intro ?: "You don't have to handle this alone. These lines are free and confidential.",
        tone = NoteTone.SUPPORT,
        icon = Icons.Outlined.FavoriteBorder,
        modifier = modifier
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            resources.forEach { r ->
                val digits = r.contact.filter { it.isDigit() || it == '+' }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                        .clickable(enabled = digits.isNotEmpty()) {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(r.contact, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        if (r.description.isNotBlank()) {
                            Text(r.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (digits.isNotEmpty()) {
                        Icon(Icons.Outlined.Call, "Call ${r.name}", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** A switch row used in settings-like cards. */
@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) {
                haptics.performHapticFeedback(if (!checked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onCheckedChange(!checked)
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** An expandable section header + body, used for the photo protocol and measurement guide. */
@Composable
internal fun Expandable(
    title: String,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
    content: @Composable () -> Unit
) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    val motion = MaterialTheme.motionScheme
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { open = !open }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) "Collapse" else "Expand")
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec())
        ) { Column { content() } }
    }
}

/** Numbered list text, used for protocols and stop conditions. */
@Composable
internal fun BulletList(items: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Row {
                Box(
                    Modifier
                        .padding(top = 7.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(10.dp))
                Text(item, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * A body-fat range drawn on a 0-50% track. Showing the band rather than a number makes the
 * uncertainty the first thing the eye reads.
 */
@Composable
internal fun RangeBar(
    range: BodyFatRange,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val motion = MaterialTheme.motionScheme
    // Sweeps open from the centre on first show and whenever the range changes.
    val progress = remember(range) { Animatable(0f) }
    LaunchedEffect(range) { progress.animateTo(1f, motion.slowSpatialSpec()) }
    val max = 50f
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
        ) {
            val r = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            val lo = (range.low.toFloat() / max).coerceIn(0f, 1f)
            val hi = (range.high.toFloat() / max).coerceIn(0f, 1f)
            val mid = (lo + hi) / 2f
            val half = (hi - lo) / 2f * progress.value
            drawRoundRect(
                color.copy(alpha = 0.85f),
                topLeft = Offset(size.width * (mid - half), 0f),
                size = Size(size.width * half * 2f, size.height),
                cornerRadius = r
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 10, 20, 30, 40, 50).forEach {
                Text("$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** kcal / protein / carbs / fat in a row; calories hidden when the person opted out of numbers. */
@Composable
internal fun TargetsRow(
    kcal: Int,
    proteinG: Int,
    carbsG: Int,
    fatG: Int,
    hideCalories: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        if (!hideCalories) TargetCell("kcal", kcal.toString())
        TargetCell("protein", "${proteinG}g")
        if (!hideCalories) {
            TargetCell("carbs", "${carbsG}g")
            TargetCell("fat", "${fatG}g")
        }
    }
}

@Composable
private fun RowScope.TargetCell(label: String, value: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A small rounded badge for the plan source and phase. */
@Composable
internal fun Badge(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = modifier
            .clip(CircleShape)
            .border(1.dp, color.copy(alpha = 0.35f), CircleShape)
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
