package com.laksh.forge.feature.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The Quick add 4/4/9 cross-check: a gentle hint only when typed kcal and macros clearly disagree. */
class QuickAddMathTest {

    @Test fun impliedKcalUsesFourFourNine() {
        assertThat(QuickAddMath.impliedKcal(proteinG = 40.0, carbsG = 80.0, fatG = 30.0))
            .isWithin(1e-9).of(40 * 4.0 + 80 * 4.0 + 30 * 9.0)
    }

    @Test fun consistentBurritoGivesNoHint() {
        // 40 P / 80 C / 32 F = 160 + 320 + 288 = 768 kcal, 4% under 800.
        assertThat(QuickAddMath.mismatch(800.0, 40.0, 80.0, 32.0)).isNull()
    }

    @Test fun macrosFarAboveTypedKcalHint() {
        // 40 / 120 / 50 = 160 + 480 + 450 = 1090 kcal, 36% over 800.
        val m = QuickAddMath.mismatch(800.0, 40.0, 120.0, 50.0)
        assertThat(m).isNotNull()
        assertThat(m!!.impliedKcal).isWithin(1e-9).of(1090.0)
        assertThat(m.deviation).isWithin(1e-9).of(0.3625)
        assertThat(m.impliedRounded).isEqualTo(1090)
    }

    @Test fun macrosFarBelowTypedKcalHintWithNegativeDeviation() {
        // 40 / 40 / 10 = 160 + 160 + 90 = 410 kcal, ~49% under 800.
        val m = QuickAddMath.mismatch(800.0, 40.0, 40.0, 10.0)
        assertThat(m).isNotNull()
        assertThat(m!!.deviation).isLessThan(0.0)
    }

    @Test fun exactlyTwentyFivePercentIsTolerated() {
        // 0 / 250 / 0 = 1000 kcal vs 800: +25% exactly sits on the boundary and stays quiet.
        assertThat(QuickAddMath.mismatch(800.0, 0.0, 250.0, 0.0)).isNull()
        // 0 / 150 / 0 = 600 kcal vs 800: -25% exactly.
        assertThat(QuickAddMath.mismatch(800.0, 0.0, 150.0, 0.0)).isNull()
    }

    @Test fun justPastTheToleranceHints() {
        // 1004 kcal vs 800 is +25.5%.
        assertThat(QuickAddMath.mismatch(800.0, 0.0, 251.0, 0.0)).isNotNull()
    }

    @Test fun anyMacroMissingMeansNoComparison() {
        // Only protein known: the rest is unknown, not zero, so 40 g alone must not trigger.
        assertThat(QuickAddMath.mismatch(800.0, 40.0, null, null)).isNull()
        assertThat(QuickAddMath.mismatch(800.0, 40.0, 80.0, null)).isNull()
        assertThat(QuickAddMath.mismatch(800.0, null, 80.0, 32.0)).isNull()
        assertThat(QuickAddMath.mismatch("800", "40", "", "")).isNull()
    }

    @Test fun missingOrZeroKcalMeansNoHint() {
        assertThat(QuickAddMath.mismatch(null, 40.0, 80.0, 32.0)).isNull()
        assertThat(QuickAddMath.mismatch(0.0, 40.0, 80.0, 32.0)).isNull()
        assertThat(QuickAddMath.mismatch("", "40", "80", "32")).isNull()
    }

    @Test fun explicitZeroMacrosAreComparedAndHint() {
        // All three typed as 0 is a real claim (0 kcal from macros) and disagrees with 800.
        assertThat(QuickAddMath.mismatch("800", "0", "0", "0")).isNotNull()
    }

    @Test fun stringOverloadParsesDecimalComma() {
        assertThat(QuickAddMath.mismatch("800", "40", "120,0", "50")).isNotNull()
        assertThat(QuickAddMath.mismatch("800", "40", "80", "32,0")).isNull()
    }

    @Test fun parseRejectsJunkAndNegatives() {
        assertThat(QuickAddMath.parse("  42 ")).isEqualTo(42.0)
        assertThat(QuickAddMath.parse("12,5")).isEqualTo(12.5)
        assertThat(QuickAddMath.parse("")).isNull()
        assertThat(QuickAddMath.parse("abc")).isNull()
        assertThat(QuickAddMath.parse("-5")).isNull()
        assertThat(QuickAddMath.parse("NaN")).isNull()
        assertThat(QuickAddMath.parse("Infinity")).isNull()
    }

    @Test fun sanitizeKeepsOneSeparatorAndDigits() {
        assertThat(QuickAddMath.sanitize("800 kcal")).isEqualTo("800")
        assertThat(QuickAddMath.sanitize("12,5")).isEqualTo("12.5")
        assertThat(QuickAddMath.sanitize("1.2.3")).isEqualTo("1.23")
        assertThat(QuickAddMath.sanitize("-40")).isEqualTo("40")
    }

    @Test fun sanitizeKcalTreatsThreeDigitGroupsAsThousands() {
        assertThat(QuickAddMath.sanitizeKcal("1,250")).isEqualTo("1250")
        assertThat(QuickAddMath.sanitizeKcal("1.250 kcal")).isEqualTo("1250")
        assertThat(QuickAddMath.sanitizeKcal("800.5")).isEqualTo("800")
        assertThat(QuickAddMath.sanitizeKcal("1,")).isEqualTo("1")
        assertThat(QuickAddMath.sanitizeKcal("800 kcal")).isEqualTo("800")
    }

    @Test fun validationNeedsNameAndPositiveKcal() {
        assertThat(QuickAddMath.validateName("  ")).isEqualTo(QuickAddProblem.NAME_MISSING)
        assertThat(QuickAddMath.validateName("Chicken burrito")).isNull()
        assertThat(QuickAddMath.validateKcal("")).isEqualTo(QuickAddProblem.KCAL_MISSING)
        assertThat(QuickAddMath.validateKcal("0")).isEqualTo(QuickAddProblem.KCAL_MISSING)
        assertThat(QuickAddMath.validateKcal("800")).isNull()
        assertThat(QuickAddMath.validateKcal("80000")).isEqualTo(QuickAddProblem.KCAL_TOO_HIGH)
    }

    @Test fun formIsValidOnlyWithNameAndKcal() {
        assertThat(QuickAddForm(name = "Burrito", kcal = "800").isValid).isTrue()
        assertThat(QuickAddForm(name = "", kcal = "800").isValid).isFalse()
        assertThat(QuickAddForm(name = "Burrito", kcal = "").isValid).isFalse()
        // Macros are optional and never block the save, even when inconsistent.
        assertThat(QuickAddForm(name = "Burrito", kcal = "800", protein = "40", carbs = "300", fat = "90").isValid).isTrue()
    }
}
