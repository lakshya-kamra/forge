package com.laksh.forge.ui.theme

import androidx.compose.ui.graphics.Color

// Core surfaces - deep, near-black with a cool cast so the ember accent pops.
val Ink0 = Color(0xFF07090C)
val Ink1 = Color(0xFF0B0D10)
val Ink2 = Color(0xFF14181D)
val Ink3 = Color(0xFF1C2129)
val Ink4 = Color(0xFF262D37)

val Paper0 = Color(0xFFF7F7F5)
val Paper1 = Color(0xFFFFFFFF)
val Paper2 = Color(0xFFEFEFEC)
val Paper3 = Color(0xFFE2E2DE)

// Ember - the brand accent.
val Ember = Color(0xFFFF6B4A)
val EmberBright = Color(0xFFFF8A5C)
val EmberDim = Color(0xFFC2472C)

// Macro palette. Distinct in hue AND lightness so they survive greyscale.
val Protein = Color(0xFF37D6C0)
val Carbs = Color(0xFFF2B84B)
val Fat = Color(0xFFF26D8A)
val Fiber = Color(0xFF9B8AF8)

// Semantic
val Positive = Color(0xFF4ADE80)
val Caution = Color(0xFFFBBF24)
val Negative = Color(0xFFF87171)

val TextHi = Color(0xFFF2F4F7)
val TextMid = Color(0xFFA5AEBC)
val TextLo = Color(0xFF6B7688)

val TextHiLight = Color(0xFF14181D)
val TextMidLight = Color(0xFF5A6472)
val TextLoLight = Color(0xFF8B94A3)

// ------------------------------------------------------------------ v2 tonal ladder
// Hand-tuned rather than generated from a seed: generated schemes desaturate ember into
// salmon, and the brand only works when the accent stays hot against cool neutrals.

/** Light-mode ember, darkened until white text on it clears 4.5:1. */
val EmberDeep = Color(0xFFCF4A2C)
val EmberContainerLight = Color(0xFFFFDBD1)
val OnEmberContainerLight = Color(0xFF3F0A00)
val EmberContainerDark = Color(0xFF5C1A08)
val OnEmberContainerDark = Color(0xFFFFDBD1)
/** Ember is luminous; near-black text on it reads at ~6:1 where white only manages ~2.8:1. */
val OnEmberDark = Color(0xFF2D0A00)

val TealDeep = Color(0xFF0F8577)
val TealContainerLight = Color(0xFFB4EFE4)
val OnTealContainerLight = Color(0xFF00201C)
val TealContainerDark = Color(0xFF00504A)
val OnTealContainerDark = Color(0xFFA6F5E8)

val VioletDeep = Color(0xFF5A48D6)
val VioletContainerLight = Color(0xFFE4DFFF)
val OnVioletContainerLight = Color(0xFF170065)
val VioletContainerDark = Color(0xFF41309F)
val OnVioletContainerDark = Color(0xFFE4DFFF)

// Light neutrals: a cool page with white cards, so content lifts off the background.
val Mist0 = Color(0xFFEFF1F4)   // page
val Mist1 = Color(0xFFF8F9FB)
val Mist2 = Color(0xFFF4F5F8)
val Mist3 = Color(0xFFE7EAEE)
val Mist4 = Color(0xFFDFE3E8)

/** Pure black for AMOLED mode: lit pixels only where there is content. */
val Amoled0 = Color(0xFF000000)
val Amoled1 = Color(0xFF080A0D)
val Amoled2 = Color(0xFF0F1216)
val Amoled3 = Color(0xFF171B21)
val Amoled4 = Color(0xFF20252D)
