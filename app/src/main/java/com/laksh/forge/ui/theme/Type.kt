package com.laksh.forge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.laksh.forge.R

/*
 * Google Sans Flex is bundled (res/font/google_sans_flex.ttf, SIL OFL 1.1, licence text in
 * assets/licenses) instead of downloaded, so the very first frame already has the right font
 * and nothing reflows when a provider answers late.
 *
 * It is one variable TTF with live wght / opsz / ROND axes. Each role gets its own family
 * pinned to an optical size and roundness: big numbers want the display cut and full
 * roundness (the PixelPlayer look), body copy wants the text cut with square terminals so it
 * stays crisp at 14 sp.
 */

private val Weights = listOf(300, 400, 500, 600, 700, 800)

@OptIn(ExperimentalTextApi::class)
private fun flexFont(weight: Int, opticalSize: Float, roundness: Float): Font = Font(
    resId = R.font.google_sans_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("opsz", opticalSize),
        FontVariation.Setting("ROND", roundness)
    )
)

private fun flexFamily(opticalSize: Float, roundness: Float): FontFamily =
    FontFamily(Weights.map { flexFont(it, opticalSize, roundness) })

private val DisplayFamily by lazy { flexFamily(opticalSize = 56f, roundness = 100f) }
private val HeadlineFamily by lazy { flexFamily(opticalSize = 32f, roundness = 100f) }
private val TitleFamily by lazy { flexFamily(opticalSize = 20f, roundness = 60f) }
private val BodyFamily by lazy { flexFamily(opticalSize = 15f, roundness = 0f) }
private val LabelFamily by lazy { flexFamily(opticalSize = 12f, roundness = 30f) }

/**
 * Tabular figures everywhere: kcal, grams, kg and timers animate constantly, and
 * proportional digits make a rolling counter jitter sideways on every tick.
 */
private const val TABULAR = "tnum"

private fun TextStyle.with(
    family: FontFamily,
    weight: FontWeight,
    letterSpacing: TextUnit = this.letterSpacing
): TextStyle = copy(
    fontFamily = family,
    fontWeight = weight,
    letterSpacing = letterSpacing,
    fontFeatureSettings = TABULAR
)

/**
 * Baseline styles keep Material's sizes (other screens are laid out against them); the
 * fifteen Expressive `*Emphasized` slots carry the extra weight and are what headline numbers
 * and screen titles use.
 */
fun forgeTypography(): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.with(DisplayFamily, FontWeight.Medium, (-0.5).sp),
        displayMedium = base.displayMedium.with(DisplayFamily, FontWeight.Medium, (-0.25).sp),
        displaySmall = base.displaySmall.with(DisplayFamily, FontWeight.Medium),
        headlineLarge = base.headlineLarge.with(HeadlineFamily, FontWeight.Medium),
        headlineMedium = base.headlineMedium.with(HeadlineFamily, FontWeight.Medium),
        headlineSmall = base.headlineSmall.with(HeadlineFamily, FontWeight.Medium),
        titleLarge = base.titleLarge.with(TitleFamily, FontWeight.Medium),
        titleMedium = base.titleMedium.with(TitleFamily, FontWeight.Medium),
        titleSmall = base.titleSmall.with(TitleFamily, FontWeight.Medium),
        bodyLarge = base.bodyLarge.with(BodyFamily, FontWeight.Normal),
        bodyMedium = base.bodyMedium.with(BodyFamily, FontWeight.Normal),
        bodySmall = base.bodySmall.with(BodyFamily, FontWeight.Normal),
        labelLarge = base.labelLarge.with(LabelFamily, FontWeight.Medium),
        labelMedium = base.labelMedium.with(LabelFamily, FontWeight.Medium),
        labelSmall = base.labelSmall.with(LabelFamily, FontWeight.Medium),

        displayLargeEmphasized = base.displayLarge.with(DisplayFamily, FontWeight.Bold, (-1.0).sp),
        displayMediumEmphasized = base.displayMedium.with(DisplayFamily, FontWeight.Bold, (-0.75).sp),
        displaySmallEmphasized = base.displaySmall.with(DisplayFamily, FontWeight.Bold, (-0.5).sp),
        headlineLargeEmphasized = base.headlineLarge.with(HeadlineFamily, FontWeight.SemiBold, (-0.25).sp),
        headlineMediumEmphasized = base.headlineMedium.with(HeadlineFamily, FontWeight.SemiBold, (-0.25).sp),
        headlineSmallEmphasized = base.headlineSmall.with(HeadlineFamily, FontWeight.SemiBold),
        titleLargeEmphasized = base.titleLarge.with(TitleFamily, FontWeight.SemiBold),
        titleMediumEmphasized = base.titleMedium.with(TitleFamily, FontWeight.SemiBold),
        titleSmallEmphasized = base.titleSmall.with(TitleFamily, FontWeight.SemiBold),
        bodyLargeEmphasized = base.bodyLarge.with(BodyFamily, FontWeight.Medium),
        bodyMediumEmphasized = base.bodyMedium.with(BodyFamily, FontWeight.Medium),
        bodySmallEmphasized = base.bodySmall.with(BodyFamily, FontWeight.Medium),
        labelLargeEmphasized = base.labelLarge.with(LabelFamily, FontWeight.Bold),
        labelMediumEmphasized = base.labelMedium.with(LabelFamily, FontWeight.Bold),
        labelSmallEmphasized = base.labelSmall.with(LabelFamily, FontWeight.Bold)
    )
}

/**
 * Built once per process: it allocates 30 `Font` objects, and recreating it on every theme
 * recomposition (dark mode flip, dynamic colour toggle) would be pure garbage.
 */
val ForgeType: Typography by lazy { forgeTypography() }
