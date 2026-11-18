package com.laksh.forge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.laksh.forge.ui.theme.cardContainer
import com.laksh.forge.ui.theme.tintedContainer

/*
 * Grouped ("segmented") lists in the Android 16 style: rows of one group share a card whose
 * outer corners are large and inner corners small, separated by a hairline gap. Material's
 * SegmentedListItem morphs those corners on press, which is most of the charm.
 */

/** Spacing under a row unless it is the last of its group. */
fun Modifier.segmentGap(index: Int, count: Int): Modifier =
    if (index < count - 1) this.padding(bottom = ListItemDefaults.SegmentedGap) else this

/** Icon on a soft cookie-shaped badge; the shape doubles as a visual index in long lists. */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 40.dp
) {
    Box(
        modifier
            .size(size)
            .clip(MaterialShapes.Cookie9Sided.toShape())
            .background(MaterialTheme.colorScheme.tintedContainer(tint)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** A navigating row: icon badge, title, optional supporting line, trailing chevron. */
@Composable
fun SegmentedNavRow(
    index: Int,
    count: Int,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null
) {
    val haptics = LocalHapticFeedback.current
    SegmentedListItem(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        modifier = modifier.segmentGap(index, count),
        leadingContent = icon?.let { { IconBadge(it, tint = iconTint) } },
        trailingContent = {
            if (trailing != null) trailing()
            else Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        supportingContent = supporting?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.cardContainer)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLargeEmphasized)
    }
}

/** A toggle row; the whole row is the touch target and the switch only mirrors state. */
@Composable
fun SegmentedSwitchRow(
    index: Int,
    count: Int,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true
) {
    val haptics = LocalHapticFeedback.current
    SegmentedListItem(
        checked = checked,
        onCheckedChange = { next ->
            haptics.performHapticFeedback(if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onCheckedChange(next)
        },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        modifier = modifier.segmentGap(index, count),
        enabled = enabled,
        leadingContent = icon?.let { { IconBadge(it, tint = iconTint) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        supportingContent = supporting?.let { { Text(it) } },
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.cardContainer)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLargeEmphasized)
    }
}

/** A static row (no click) that hosts arbitrary content, e.g. a slider, inside a group. */
@Composable
fun SegmentedContentRow(
    index: Int,
    count: Int,
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null
) {
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        modifier = modifier.segmentGap(index, count),
        leadingContent = icon?.let { { IconBadge(it, tint = iconTint) } },
        trailingContent = trailing,
        supportingContent = supporting?.let { { Text(it) } },
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.cardContainer)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLargeEmphasized)
    }
}

/**
 * Single-choice connected button group (the Expressive replacement for segmented buttons).
 * The checked button morphs to a pill; neighbours keep their inner corners tight.
 */
@Composable
fun <T> ConnectedChoiceGroup(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector?)? = null
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        options.forEachIndexed { index, option ->
            val shapes = when (index) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            val optionIcon = icon?.invoke(option)
            ToggleButton(
                checked = option == selected,
                onCheckedChange = {
                    if (option != selected) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(option)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
                shapes = shapes,
                colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.cardContainer),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                if (optionIcon != null) {
                    Icon(optionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Box(Modifier.size(6.dp))
                }
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
