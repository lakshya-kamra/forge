package com.laksh.forge.feature.tools

import java.math.BigDecimal
import java.math.RoundingMode

/** Exact unit definitions, so conversions agree with every other calculator to the gram. */
object Units {
    /** International avoirdupois pound, exact by definition. */
    const val KG_PER_LB = 0.45359237

    /** International inch, exact by definition. */
    const val CM_PER_IN = 2.54

    /** Thermochemical calorie, the one food labels use. */
    const val KJ_PER_KCAL = 4.184

    fun kgToLb(kg: Double): Double = kg / KG_PER_LB
    fun lbToKg(lb: Double): Double = lb * KG_PER_LB
    fun cmToIn(cm: Double): Double = cm / CM_PER_IN
    fun inToCm(inches: Double): Double = inches * CM_PER_IN
    fun kcalToKj(kcal: Double): Double = kcal * KJ_PER_KCAL
    fun kjToKcal(kj: Double): Double = kj / KJ_PER_KCAL

    /**
     * "102.5", "100", "1.25" - at most [decimals] places, no trailing zeros, always a dot.
     * Plate weights need two places (1.25) but never more.
     */
    fun fmt(v: Double, decimals: Int = 2): String {
        if (v.isNaN() || v.isInfinite()) return "-"
        return BigDecimal(v).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            .let { if (it == "-0") "0" else it }
    }

    /** Parse what a person types into a number field: accepts "," as the decimal mark. */
    fun parse(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
}

/** The conversions offered on the converter tab. */
enum class Conversion(val fromLabel: String, val toLabel: String, val decimals: Int) {
    KG_TO_LB("kg", "lb", 1),
    LB_TO_KG("lb", "kg", 2),
    CM_TO_IN("cm", "in", 1),
    IN_TO_CM("in", "cm", 1),
    KCAL_TO_KJ("kcal", "kJ", 0),
    KJ_TO_KCAL("kJ", "kcal", 0);

    fun apply(v: Double): Double = when (this) {
        KG_TO_LB -> Units.kgToLb(v)
        LB_TO_KG -> Units.lbToKg(v)
        CM_TO_IN -> Units.cmToIn(v)
        IN_TO_CM -> Units.inToCm(v)
        KCAL_TO_KJ -> Units.kcalToKj(v)
        KJ_TO_KCAL -> Units.kjToKcal(v)
    }
}
