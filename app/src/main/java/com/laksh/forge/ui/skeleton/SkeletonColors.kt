package com.laksh.forge.ui.skeleton

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.laksh.forge.ui.theme.isDark

/**
 * The renderer's palette, resolved from the theme once and unpacked into plain floats so
 * per-frame shading is arithmetic on primitives (no Color conversions, no allocation).
 */
@Immutable
class SkeletonColors(scheme: ColorScheme) {

    /** r, g, b, a per slot. */
    internal val rgba = FloatArray(SLOTS * 4)
    val background: Color
    val backgroundTop: Color

    init {
        val dark = scheme.isDark
        background = scheme.surfaceContainer
        backgroundTop = scheme.surfaceContainerHighest
        // Bone: a warm neutral that holds its shading on both page tones.
        val bone = lerp(lerp(scheme.onSurface, scheme.surface, if (dark) 0.14f else 0.5f), scheme.secondary, 0.08f)
        put(BONE, bone)
        put(BAR, lerp(scheme.outline, scheme.onSurface, 0.2f))
        put(PLATE, if (dark) lerp(scheme.surfaceContainerHighest, scheme.onSurface, 0.18f) else scheme.inverseSurface)
        put(PAD, scheme.secondaryContainer)
        put(FRAME, scheme.outlineVariant)
        put(CABLE, scheme.onSurfaceVariant)
        put(BOX, scheme.tertiaryContainer)
        put(ROPE, scheme.tertiary)
        put(HIGHLIGHT, if (dark) scheme.inverseOnSurface else scheme.surfaceContainerLowest)
        put(OUTLINE, lerp(scheme.scrim, bone, if (dark) 0.08f else 0.3f))
        put(RIM, lerp(scheme.primary, scheme.onSurface, 0.35f))
        put(GLOW, scheme.primary)
        put(GLOW_2, scheme.tertiary)
        put(GRID, scheme.outlineVariant)
        put(SHADOW, scheme.scrim)
        put(BG, scheme.surfaceContainer)
        put(SOCKET, lerp(scheme.scrim, bone, 0.25f))
    }

    private fun put(slot: Int, c: Color) {
        val argb = c.toArgb()
        val o = slot * 4
        rgba[o] = ((argb shr 16) and 0xff) / 255f
        rgba[o + 1] = ((argb shr 8) and 0xff) / 255f
        rgba[o + 2] = (argb and 0xff) / 255f
        rgba[o + 3] = ((argb ushr 24) and 0xff) / 255f
    }

    /** Material colour scaled by [light] (0 = black, 1 = as-is) and faded toward the background by [fog]. */
    internal fun lit(slot: Int, light: Float, fog: Float, alpha: Float = 1f): Color {
        val o = slot * 4
        val b = BG * 4
        val l = light.coerceIn(0f, 1.25f)
        var r = rgba[o] * l
        var g = rgba[o + 1] * l
        var bl = rgba[o + 2] * l
        // Over-bright light washes toward the highlight colour instead of clipping.
        if (l > 1f) {
            val t = (l - 1f) * 2f
            val h = HIGHLIGHT * 4
            r = rgba[o] + (rgba[h] - rgba[o]) * t
            g = rgba[o + 1] + (rgba[h + 1] - rgba[o + 1]) * t
            bl = rgba[o + 2] + (rgba[h + 2] - rgba[o + 2]) * t
        }
        r += (rgba[b] - r) * fog
        g += (rgba[b + 1] - g) * fog
        bl += (rgba[b + 2] - bl) * fog
        return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), bl.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))
    }

    /** A slot's colour at [alpha], unshaded. */
    internal fun plain(slot: Int, alpha: Float): Color {
        val o = slot * 4
        return Color(rgba[o], rgba[o + 1], rgba[o + 2], (rgba[o + 3] * alpha).coerceIn(0f, 1f))
    }

    companion object {
        // Equipment slots line up with Mat so a primitive's material is its slot.
        const val BAR = Mat.BAR
        const val PLATE = Mat.PLATE
        const val PAD = Mat.PAD
        const val FRAME = Mat.FRAME
        const val CABLE = Mat.CABLE
        const val BOX = Mat.BOX
        const val ROPE = Mat.ROPE
        const val BONE = 7
        const val HIGHLIGHT = 8
        const val OUTLINE = 9
        const val RIM = 10
        const val GLOW = 11
        const val GLOW_2 = 12
        const val GRID = 13
        const val SHADOW = 14
        const val BG = 15
        const val SOCKET = 16
        const val SLOTS = 17
    }
}

@Composable
fun rememberSkeletonColors(): SkeletonColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) { SkeletonColors(scheme) }
}
