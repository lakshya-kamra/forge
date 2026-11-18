package com.laksh.forge.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import com.laksh.forge.ui.theme.LocalReduceMotion

/**
 * A number whose digits roll like an odometer when it changes: up when the value grows, down
 * when it shrinks. Only digits that actually change move, because each position (counted
 * from the right, so 99 -> 100 keeps the units column in place) is its own AnimatedContent.
 *
 * Relies on tabular figures (every Forge text style sets `tnum`) so columns never shift width.
 */
@Composable
fun AnimatedCounter(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    prefix: String = "",
    suffix: String = ""
) {
    val text = value.toString()
    val full = prefix + text + suffix

    if (LocalReduceMotion.current) {
        Text(full, modifier = modifier, style = style, color = color)
        return
    }

    // A plain holder, not State: the direction is decided while composing the new value and
    // writing it back must not trigger another recomposition.
    val previous = remember { IntArray(1) { value } }
    val countingUp = value >= previous[0]
    previous[0] = value

    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = full },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (prefix.isNotEmpty()) Text(prefix, style = style, color = color)
        val chars = text.toCharArray()
        chars.forEachIndexed { i, ch ->
            key(chars.size - 1 - i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        val dir = if (countingUp) 1 else -1
                        (slideInVertically(spatial) { h -> h * dir } + fadeIn(fadeInSpec))
                            .togetherWith(slideOutVertically(spatial) { h -> -h * dir } + fadeOut(fadeOutSpec))
                            .using(SizeTransform(clip = false))
                    },
                    label = "digit"
                ) { digit ->
                    Text(digit.toString(), style = style, color = color)
                }
            }
        }
        if (suffix.isNotEmpty()) Text(suffix, style = style, color = color)
    }
}
