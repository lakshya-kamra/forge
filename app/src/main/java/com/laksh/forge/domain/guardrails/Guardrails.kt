package com.laksh.forge.domain.guardrails

import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.PregnancyStatus
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.prefs.TrainingAge
import com.laksh.forge.domain.training.Muscles
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Every number in this file comes from docs/research/guardrails.final.json (rules reconciled
// after three adversarial reviews). Rule ids match the research ids so a flag can be traced
// back to its source. Values the research marks as "app convention" are Forge's own safety
// choices, not trial results, and the comments say so.

/** Everything the guardrails need to know about the person. */
data class GuardrailProfile(
    val sex: Sex,
    val age: Int,
    val heightCm: Double,
    val weightKg: Double,
    val bodyFatPct: Double?,
    val trainingAge: TrainingAge,
    val pregnancy: PregnancyStatus,
    val edScreenPositive: Boolean,
    /** Self-reported kidney disease: protein switches to the KDIGO 2024 limits. */
    val kidneyDisease: Boolean = false,
    /**
     * How [bodyFatPct] was obtained, using BodyMeasurement.bodyFatMethod's vocabulary
     * (NAVY | VISUAL_AI | SCALE | DEXA | MANUAL). Decides the safety offset applied before
     * comparing against low-body-fat thresholds. Null means [bodyFatPct] is already the
     * cautious value (e.g. the lower bound of an estimate range) and is used as given.
     */
    val bodyFatMethod: String? = null
) {
    val bmi: Double get() = weightKg / ((heightCm / 100.0) * (heightCm / 100.0))

    val isMinor: Boolean get() = age < 18

    /**
     * Body fat as the safety checks must see it: the lower bound for low-confidence methods,
     * never the point estimate (safety_low_body_fat_no_deficit, photo_bf_accuracy_limits).
     * Null for minors, whose body fat is never used to set goals (safety_under_18).
     */
    val safetyBodyFatPct: Double?
        get() = if (isMinor) null
        else bodyFatPct?.let { it - Guardrails.bodyFatSafetyOffset(bodyFatMethod) }
}

enum class Severity { INFO, CAUTION, BLOCK }

data class GuardrailFlag(
    val id: String,
    val severity: Severity,
    /** Calm, plain-language message shown to the user as-is. */
    val message: String,
    val citation: String? = null
)

/** A nutrition prescription after guardrails: possibly clamped, with the reasons. */
data class NutritionCheck(
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    val rateKgPerWeek: Double,
    val flags: List<GuardrailFlag>
) {
    val wasAdjusted: Boolean get() = flags.any { it.severity != Severity.INFO }
}

/** Phase ids, matching PhasePlan.phase. */
object Phases {
    const val CUT = "CUT"
    const val LEAN_GAIN = "LEAN_GAIN"
    const val MAINGAIN = "MAINGAIN"
    const val MAINTAIN = "MAINTAIN"
    const val RECOMP = "RECOMP"
    const val REVERSE_DIET = "REVERSE_DIET"
    const val DIET_BREAK = "DIET_BREAK"
    val ALL = listOf(CUT, LEAN_GAIN, MAINGAIN, MAINTAIN, RECOMP, REVERSE_DIET, DIET_BREAK)
}

/** A helpline or emergency number shown with tap-to-call. */
data class CrisisResource(val name: String, val contact: String, val description: String)

/** Monthly bodyweight-gain band for a training stage, in % of bodyweight per month. */
data class GainBand(val minPctPerMonth: Double, val maxPctPerMonth: Double) {
    val midPctPerMonth: Double get() = (minPctPerMonth + maxPctPerMonth) / 2.0
}

/** Exercise categories that carry different reps-in-reserve targets. */
enum class LiftType { ISOLATION, MACHINE_COMPOUND, BARBELL_COMPOUND, STRENGTH }

/** Citations, verbatim from guardrails.final.json. */
object Cite {
    const val ISSN_2026 = "Aragon et al. 2026 (ISSN position stand: diets for body weight reduction), J Int Soc Sports Nutr - https://doi.org/10.1080/15502783.2026.2740266"
    const val HELMS_2014_JISSN = "Helms, Aragon & Fitschen 2014, J Int Soc Sports Nutr - https://doi.org/10.1186/1550-2783-11-20"
    const val HELMS_2014_IJSNEM = "Helms et al. 2014, Int J Sport Nutr Exerc Metab - https://doi.org/10.1123/ijsnem.2013-0054"
    const val GARTHE_2011 = "Garthe et al. 2011, Int J Sport Nutr Exerc Metab - https://doi.org/10.1123/ijsnem.21.2.97"
    const val ROBERTS_2020 = "Roberts et al. 2020, J Hum Kinet - https://doi.org/10.2478/hukin-2019-0096"
    const val MURPHY_2022 = "Murphy & Koehler 2022, Scand J Med Sci Sports - https://doi.org/10.1111/sms.14075"
    const val PYRAMID_NUTRITION = "Helms, Morgan & Valdez, The Muscle and Strength Pyramid: Nutrition 2nd ed. (ranges as quoted by co-author A. Morgan) - https://rippedbody.com/updated-bulking-guidelines/"
    const val IRAKI_2019 = "Iraki et al. 2019, Sports (Basel) - https://doi.org/10.3390/sports7070154"
    const val MORTON_2018 = "Morton et al. 2018, Br J Sports Med - https://doi.org/10.1136/bjsports-2017-097608"
    const val JAGER_2017 = "Jäger et al. 2017 (ISSN position stand: protein and exercise), J Int Soc Sports Nutr - https://doi.org/10.1186/s12970-017-0177-8"
    const val KDIGO_2024 = "KDIGO 2024 Clinical Practice Guideline for the Evaluation and Management of CKD, Kidney Int 105(4S):S117-S314 (Rec. 3.3.1.1; Practice Point 3.3.1.1) - https://doi.org/10.1016/j.kint.2023.10.018"
    const val SCHOENFELD_ARAGON_2018 = "Schoenfeld & Aragon 2018, J Int Soc Sports Nutr - https://doi.org/10.1186/s12970-018-0215-1"
    const val WHITTAKER_2021 = "Whittaker & Wu 2021, J Steroid Biochem Mol Biol - https://doi.org/10.1016/j.jsbmb.2021.105878"
    const val WHO_2023_CARBS = "WHO 2023, Carbohydrate intake for adults and children: WHO guideline - https://www.who.int/news/item/17-07-2023-who-updates-guidelines-on-fats-and-carbohydrates"
    const val MATADOR_2018 = "Byrne et al. 2018 (MATADOR), Int J Obes - https://doi.org/10.1038/ijo.2017.206"
    const val WHO_2004 = "WHO Expert Consultation 2004, Lancet - https://doi.org/10.1016/S0140-6736(03)15268-3"
    const val NICE_NG69 = "NICE 2017 (NG69), Eating disorders: recognition and treatment - https://www.nice.org.uk/guidance/ng69/chapter/Recommendations"
    const val IOC_2023 = "Mountjoy et al. 2023 (IOC consensus statement on REDs), Br J Sports Med - https://doi.org/10.1136/bjsports-2023-106994"
    const val FAGERBERG_2018 = "Fagerberg 2018, Int J Sport Nutr Exerc Metab - https://doi.org/10.1123/ijsnem.2016-0332"
    const val AAP_2016 = "Golden et al. 2016 (AAP clinical report), Pediatrics - https://doi.org/10.1542/peds.2016-1649"
    const val ACOG_804 = "ACOG 2020 (Committee Opinion 804), Obstet Gynecol - https://doi.org/10.1097/AOG.0000000000003772"
    const val CDC_BREASTFEEDING = "CDC, Maternal Diet - Breastfeeding Special Circumstances - https://www.cdc.gov/breastfeeding-special-circumstances/hcp/diet-micronutrients/maternal-diet.html"
    const val SCOFF_1999 = "Morgan, Reid & Lacey 1999, BMJ - https://doi.org/10.1136/bmj.319.7223.1467"
    const val KUTZ_2020 = "Kutz et al. 2020, J Gen Intern Med - https://doi.org/10.1007/s11606-019-05478-6"
    const val TELE_MANAS = "DGHS MoHFW India, National Mental Health Programme / Tele MANAS - https://dghs.mohfw.gov.in/national-mental-health-programme.php"
    const val ACSM_2015 = "Riebe et al. 2015 (ACSM preparticipation screening), Med Sci Sports Exerc - https://doi.org/10.1249/MSS.0000000000000664"
    const val PELLAND_2025 = "Pelland et al. 2025 (online; issue 2026), Sports Med - https://doi.org/10.1007/s40279-025-02344-w"
    const val IVERSEN_2021 = "Iversen et al. 2021, Sports Med - https://doi.org/10.1007/s40279-021-01490-1"
    const val SCHOENFELD_2017 = "Schoenfeld, Ogborn & Krieger 2017, J Sports Sci - https://doi.org/10.1080/02640414.2016.1210197"
    const val BAZ_VALLE_2022 = "Baz-Valle et al. 2022, J Hum Kinet - https://doi.org/10.2478/hukin-2022-0017"
    const val SCHOENFELD_2016_FREQ = "Schoenfeld, Ogborn & Krieger 2016, Sports Med - https://doi.org/10.1007/s40279-016-0543-8"
    const val SCHOENFELD_2019_FREQ = "Schoenfeld, Grgic & Krieger 2019, J Sports Sci - https://doi.org/10.1080/02640414.2018.1555906"
    const val ROBINSON_2024 = "Robinson et al. 2024, Sports Med - https://doi.org/10.1007/s40279-024-02069-2"
    const val REFALO_2023 = "Refalo et al. 2023, Sports Med - https://doi.org/10.1007/s40279-022-01784-y"
    const val REFALO_2024 = "Refalo et al. 2024, J Sports Sci - https://doi.org/10.1080/02640414.2024.2321021"
    const val SINGER_2024 = "Singer et al. 2024, Front Sports Act Living - https://doi.org/10.3389/fspor.2024.1429789"
    const val ACSM_2009 = "ACSM 2009 (position stand: progression models in resistance training), Med Sci Sports Exerc - https://doi.org/10.1249/MSS.0b013e3181915670"
    const val BELL_2023 = "Bell et al. 2023 (Delphi consensus), Sports Med Open - https://doi.org/10.1186/s40798-023-00633-0"
    const val ROGERSON_2024 = "Rogerson et al. 2024, Sports Med Open - https://doi.org/10.1186/s40798-024-00691-y"
    const val SILBERNAGEL_2007 = "Silbernagel et al. 2007, Am J Sports Med - https://doi.org/10.1177/0363546506298279"
    const val MAJMUDAR_2022 = "Majmudar et al. 2022, NPJ Digit Med - https://doi.org/10.1038/s41746-022-00628-3"
    const val NEDC_2024 = "National Eating Disorders Collaboration 2024, Eating Disorder Safe principles, Section 3 (communicators/platforms)"

    fun join(vararg c: String): String = c.joinToString("; ")
}

object Guardrails {

    // ------------------------------------------------------------------ constants

    /** nutrition_calorie_floor_by_sex. The sex floors are an app convention. */
    const val MIN_KCAL_MALE = 1500
    const val MIN_KCAL_FEMALE = 1200
    const val ABSOLUTE_MIN_KCAL = 800

    /** nutrition_max_deficit_size: the smaller of 25% of maintenance and 500 kcal/day. */
    const val MAX_DEFICIT_PCT_OF_TDEE = 25.0
    const val MAX_DEFICIT_KCAL = 500.0

    /** nutrition_max_deficit_rate_by_body_fat, % of bodyweight per week. */
    const val ABSOLUTE_MAX_LOSS_PCT_PER_WEEK = 1.0

    /** safety_breastfeeding_no_deficit (CDC): extra energy over maintenance. */
    const val BREASTFEEDING_EXTRA_KCAL_MIN = 330
    const val BREASTFEEDING_EXTRA_KCAL_MAX = 400

    /** nutrition_fibre_minimum (WHO 2023). */
    const val MIN_FIBRE_G = 25
    const val MIN_FRUIT_VEG_G = 400

    /** nutrition_max_cut_length (app convention). */
    const val MAX_CONTINUOUS_DEFICIT_WEEKS = 16
    const val PROMPT_DIET_BREAK_AT_WEEKS = 12
    const val MIN_MAINTENANCE_WEEKS_AFTER_CUT = 4

    /** nutrition_adjustment_cadence (app convention). */
    const val MIN_DAYS_BETWEEN_ADJUSTMENTS = 14
    const val MAX_ADJUSTMENT_PCT_OF_TARGET = 10.0
    const val TARGET_DISPLAY_RANGE_KCAL = 100

    /** safety_scoff_positive_response: minimum lock (app convention). */
    const val SCOFF_LOCK_DAYS = 30

    /** training_set_counting_fractional / training_weekly_sets_per_muscle. */
    const val HARD_SET_MAX_RIR = 4.0
    const val MIN_EFFECTIVE_SETS = 4.0
    const val TYPICAL_MIN_SETS = 10.0
    const val TYPICAL_MAX_SETS = 20.0
    const val WARN_ABOVE_SETS = 30.0
    const val MAX_WEEKLY_SET_INCREASE = 2.0
    const val NOVICE_MAX_WEEKLY_SETS = 12.0

    /** training_frequency_per_muscle: split above this many weekly sets (app convention). */
    const val SPLIT_ABOVE_WEEKLY_SETS = 10.0

    /** training_load_progression_cap. */
    const val MAX_LOAD_INCREASE_PCT = 10.0
    const val DEFAULT_SMALLEST_INCREMENT_KG = 2.5

    /** training_rest_intervals. */
    const val MIN_REST_SEC = 60

    /** training_pain_stop_and_refer. */
    const val MAX_ACCEPTABLE_PAIN = 5
    const val REFER_IF_PAIN_PERSISTS_DAYS = 7

    /** photo_bf_accuracy_limits / photo_feature_blocked_groups. */
    const val PHOTO_BF_HALF_WIDTH = 6.0
    const val MIN_DAYS_BETWEEN_PHOTO_ESTIMATES = 14
    const val PHOTO_REQUIRE_SCREEN_WITHIN_DAYS = 12 * 7

    private const val KCAL_PER_KG = 7700.0
    private const val WEEKS_PER_MONTH = 52.0 / 12.0

    // ------------------------------------------------------------------ profile

    /**
     * Build the guardrail profile from settings. Body fat is dropped for minors because no
     * body-fat value may be used to set their goals (safety_under_18).
     */
    fun profileFrom(
        s: ForgeSettings,
        weightKg: Double,
        bodyFatPct: Double?,
        bodyFatMethod: String? = null
    ): GuardrailProfile = GuardrailProfile(
        sex = s.sex,
        age = s.age,
        heightCm = s.heightCm,
        weightKg = weightKg,
        bodyFatPct = if (s.isMinor) null else bodyFatPct,
        trainingAge = s.trainingAge,
        pregnancy = s.pregnancyStatus,
        edScreenPositive = s.edScreenPositive,
        bodyFatMethod = if (s.isMinor) null else bodyFatMethod
    )

    /**
     * Points subtracted from a body-fat estimate before safety comparisons.
     * Photo estimates use the lower bound of the displayed +/-6 range; consumer BIA gets 5
     * (IOC 2023: doubly indirect methods do not give valid data); DXA uses the reported value.
     * Navy tape and a named-but-unknown method (e.g. MANUAL) are not covered by the research:
     * Navy's error against DXA is of the same order as BIA's (onboarding research), and an
     * unknown source is treated like the least accurate method. Both are app conventions
     * erring towards safety.
     *
     * A null method means the caller already passes a value fit for safety checks (the plan
     * feature passes the lower bound of its estimate range), so nothing more is subtracted.
     */
    fun bodyFatSafetyOffset(method: String?): Double = when (method?.uppercase(Locale.ROOT)) {
        null -> 0.0
        "DEXA", "DXA", "SKINFOLD" -> 0.0
        "SCALE", "BIA", "NAVY" -> 5.0
        else -> PHOTO_BF_HALF_WIDTH
    }

    fun calorieFloor(p: GuardrailProfile): Int =
        max(ABSOLUTE_MIN_KCAL, if (p.sex == Sex.MALE) MIN_KCAL_MALE else MIN_KCAL_FEMALE)

    // ------------------------------------------------------------------ phases

    /** Phases this person may be put in. A deficit is never offered when unsafe. */
    fun allowedPhases(p: GuardrailProfile): Set<String> {
        val all = Phases.ALL.toSet()
        return when {
            // Pregnancy, breastfeeding and a positive eating screen lock to maintenance
            // (safety_pregnancy_no_deficit, safety_breastfeeding_no_deficit,
            // safety_scoff_positive_response).
            p.pregnancy != PregnancyStatus.NONE || p.edScreenPositive -> setOf(Phases.MAINTAIN)
            // Minors: maintenance or performance; no fat-loss framing (safety_under_18) and,
            // per the onboarding research, no aggressive surplus.
            p.isMinor -> setOf(Phases.MAINTAIN, Phases.MAINGAIN, Phases.LEAN_GAIN)
            // Underweight or very low body fat: maintenance or surplus only. A diet break only
            // exists inside a cut, so it goes too.
            deficitBlockers(p).isNotEmpty() -> all - Phases.CUT - Phases.DIET_BREAK
            else -> all
        }
    }

    /**
     * Every reason this person must not be put in a calorie deficit, as BLOCK flags.
     * Empty when a deficit is allowed.
     */
    fun deficitBlockers(p: GuardrailProfile): List<GuardrailFlag> = buildList {
        if (p.isMinor) add(underEighteenFlag())
        when (p.pregnancy) {
            PregnancyStatus.PREGNANT -> add(pregnancyFlag())
            PregnancyStatus.BREASTFEEDING -> add(breastfeedingFlag())
            PregnancyStatus.NONE -> Unit
        }
        if (p.edScreenPositive) add(edLockFlag())
        // Adult BMI categories are never applied to minors, who are already blocked above.
        if (!p.isMinor && p.heightCm > 0 && p.weightKg > 0 && p.bmi < 18.5) add(underweightFlag())
        if (lowBodyFatBlock(p)) add(lowBodyFatFlag())
    }

    private fun lowBodyFatBlock(p: GuardrailProfile): Boolean {
        val bf = p.safetyBodyFatPct ?: return false
        return bf < if (p.sex == Sex.MALE) 8.0 else 15.0
    }

    private fun lowBodyFatCaution(p: GuardrailProfile): Boolean {
        val bf = p.safetyBodyFatPct ?: return false
        return bf < if (p.sex == Sex.MALE) 11.0 else 20.0
    }

    private fun isLeanTier(p: GuardrailProfile): Boolean {
        val bf = p.safetyBodyFatPct ?: return false
        return bf < if (p.sex == Sex.MALE) 12.0 else 22.0
    }

    /**
     * Maximum planned loss in % of bodyweight per week, by body-fat tier
     * (nutrition_max_deficit_rate_by_body_fat). Unknown body fat uses the 0.7 tier, as the
     * rule specifies. Tier cut-points are an app convention.
     */
    fun maxLossPctPerWeek(p: GuardrailProfile): Double {
        val bf = p.safetyBodyFatPct
        val tier = when {
            bf == null -> 0.7
            p.sex == Sex.MALE -> when {
                bf >= 20.0 -> 1.0
                bf >= 12.0 -> 0.7
                else -> 0.5
            }
            else -> when {
                bf >= 30.0 -> 1.0
                bf >= 22.0 -> 0.7
                else -> 0.5
            }
        }
        val capped = if (lowBodyFatCaution(p)) min(tier, 0.5) else tier
        return min(capped, ABSOLUTE_MAX_LOSS_PCT_PER_WEEK)
    }

    /**
     * Gain band by training stage (nutrition_surplus_rate_by_training_age, Pyramid book
     * ranges). An unset training age uses the intermediate band: the middle option, so an
     * unknown beginner is not pushed to fat gain and an unknown advanced lifter is warned
     * by the rate check rather than over-fed by default. App convention.
     */
    fun gainBand(trainingAge: TrainingAge): GainBand = when (trainingAge) {
        TrainingAge.NOVICE -> GainBand(1.0, 1.5)
        TrainingAge.INTERMEDIATE, TrainingAge.UNKNOWN -> GainBand(0.5, 1.0)
        TrainingAge.ADVANCED -> GainBand(0.0, 0.5)
    }

    /** Convert a monthly %BW rate into kg per week. */
    fun monthlyPctToKgPerWeek(pctPerMonth: Double, weightKg: Double): Double =
        weightKg * pctPerMonth / 100.0 / WEEKS_PER_MONTH

    /**
     * Starting surplus for a gaining phase (nutrition_surplus_size): bottom of the Iraki 2019
     * band, and no more than 300 kcal/day for anyone past the novice stage.
     */
    fun startingSurplusKcal(p: GuardrailProfile, tdee: Double): Int {
        val pct = if (p.trainingAge == TrainingAge.ADVANCED) 5.0 else 10.0
        val raw = tdee * pct / 100.0
        val capped = if (p.trainingAge == TrainingAge.NOVICE) raw else min(raw, 300.0)
        return capped.roundToInt().coerceAtLeast(0)
    }

    // ------------------------------------------------------------------ nutrition check

    /** Clamp a proposed prescription into safe bounds and explain every change. */
    fun checkNutrition(
        p: GuardrailProfile,
        kcal: Int,
        proteinG: Int,
        carbsG: Int,
        fatG: Int,
        rateKgPerWeek: Double,
        tdee: Double
    ): NutritionCheck {
        val flags = LinkedHashMap<String, GuardrailFlag>()
        fun flag(f: GuardrailFlag) { flags[f.id] = f }

        val w = p.weightKg
        val floorKcal = calorieFloor(p).toDouble()
        if (w <= 0.0 || !w.isFinite()) {
            // Without a bodyweight nothing per-kg can be checked; the floor still applies.
            val k = max(kcal.toDouble(), floorKcal)
            if (k > kcal) flag(calorieFloorFlag(p))
            return NutritionCheck(k.roundToInt(), proteinG, carbsG, fatG, 0.0, flags.values.toList())
        }

        // Without a usable maintenance estimate the proposed intake is the best reference.
        val maintenance = tdee.takeIf { it.isFinite() && it > 0.0 } ?: kcal.toDouble()
        var k = kcal.toDouble()
        var rate = rateKgPerWeek

        val blockers = deficitBlockers(p)
        val wantsDeficit = rate < 0.0 || k < maintenance - 1.0

        when {
            p.pregnancy == PregnancyStatus.PREGNANT -> {
                // Maintenance or above; weight is expected to rise along the IOM ranges, so it
                // is not tracked against a goal and no surplus cap applies.
                val changed = k < maintenance - 1.0 || rate != 0.0
                if (k < maintenance) k = maintenance
                rate = 0.0
                flag(pregnancyFlag().copy(severity = if (changed) Severity.BLOCK else Severity.INFO))
            }
            p.pregnancy == PregnancyStatus.BREASTFEEDING -> {
                val lo = maintenance + BREASTFEEDING_EXTRA_KCAL_MIN
                val hi = maintenance + BREASTFEEDING_EXTRA_KCAL_MAX
                val changed = k < lo - 1.0 || k > hi + 1.0 || rate != 0.0
                k = k.coerceIn(lo, hi)
                rate = 0.0
                flag(breastfeedingFlag().copy(severity = if (changed) Severity.BLOCK else Severity.INFO))
            }
            else -> {
                if (blockers.isNotEmpty()) {
                    if (wantsDeficit) {
                        k = max(k, maintenance)
                        rate = max(rate, 0.0)
                        blockers.forEach { flag(it) }
                    } else {
                        blockers.forEach { flag(it.copy(severity = Severity.INFO)) }
                    }
                }

                if (blockers.isEmpty() && wantsDeficit) {
                    val pct = maxLossPctPerWeek(p)
                    val maxRate = w * pct / 100.0
                    if (-rate > maxRate + 1e-9) {
                        rate = -maxRate
                        flag(lossRateFlag(pct))
                    }
                    if (lowBodyFatCaution(p)) flag(lowBodyFatCautionFlag())

                    val pctCap = maintenance * MAX_DEFICIT_PCT_OF_TDEE / 100.0
                    val rateCap = maxRate * KCAL_PER_KG / 7.0
                    val floorCap = maintenance - floorKcal
                    val allowed = minOf(pctCap, MAX_DEFICIT_KCAL, rateCap, floorCap).coerceAtLeast(0.0)
                    val deficit = maintenance - k
                    if (deficit > allowed + 0.5) {
                        k = maintenance - allowed
                        // Explain the limit that actually bound the deficit.
                        when {
                            floorCap <= minOf(pctCap, MAX_DEFICIT_KCAL, rateCap) -> flag(calorieFloorFlag(p))
                            rateCap <= min(pctCap, MAX_DEFICIT_KCAL) -> flag(lossRateFlag(pct))
                            else -> flag(deficitSizeFlag())
                        }
                    }
                    // Keep the stated rate honest: a capped deficit cannot deliver a faster loss.
                    val implied = -(maintenance - k) * 7.0 / KCAL_PER_KG
                    if (rate < implied) rate = implied
                }

                if (!wantsDeficit && (rate > 0.0 || k > maintenance + 1.0)) {
                    val band = gainBand(p.trainingAge)
                    val maxRate = monthlyPctToKgPerWeek(band.maxPctPerMonth, w)
                    if (rate > maxRate + 1e-9) {
                        rate = maxRate
                        flag(gainRateFlag(band))
                    }
                    // Iraki 2019 band ceiling, then the rate cap, whichever is smaller.
                    val bandMaxPct = if (p.trainingAge == TrainingAge.ADVANCED) 10.0 else 20.0
                    val allowed = min(maintenance * bandMaxPct / 100.0, maxRate * KCAL_PER_KG / 7.0)
                    if (k - maintenance > allowed + 0.5) {
                        k = maintenance + allowed
                        flag(surplusSizeFlag(bandMaxPct))
                    }
                }
            }
        }

        // Pregnancy/breastfeeding handled their own flag above; any other blocker (e.g. a
        // positive screen) still belongs in the explanation.
        blockers.forEach { if (it.id !in flags) flag(it.copy(severity = Severity.INFO)) }

        // The floor always wins, whatever the arithmetic above produced.
        if (k < floorKcal) {
            k = floorKcal
            flag(calorieFloorFlag(p))
        }

        val inDeficit = k < maintenance - 1.0

        // ---- protein
        var protein = proteinG.toDouble()
        if (p.kidneyDisease) {
            val cap = 0.8 * w
            if (protein > cap + 0.5) {
                protein = cap
                flag(kidneyProteinFlag(cap))
            }
        } else {
            val lo = if (inDeficit) 1.8 * w else 1.6 * w
            val hiPerKg = when {
                !inDeficit -> 2.2
                isLeanTier(p) -> 3.2
                else -> 2.7
            }
            val hi = min(hiPerKg, 3.5) * w
            if (protein < lo - 0.5) {
                protein = lo
                flag(if (inDeficit) deficitProteinFlag(lo) else maintenanceProteinFlag(lo))
            } else if (protein > hi + 0.5) {
                protein = hi
                flag(proteinCapFlag(hi))
            }
        }

        // ---- fat
        var fat = fatG.toDouble()
        val fatFloor = max(0.5 * w, k * (if (inDeficit) 0.15 else 0.20) / 9.0)
        if (fat < fatFloor - 0.5) {
            fat = fatFloor
            flag(fatFloorFlag(fatFloor))
        }

        // ---- carbohydrate fills what is left
        var carbs = (k - protein * 4.0 - fat * 9.0) / 4.0
        if (carbs < 0.0) {
            // Give back fat above its floor first, then protein above its floor, and only
            // then raise calories: the protein and fat floors are protective.
            val fatSpare = max(0.0, fat - fatFloor)
            val needFromFat = min(fatSpare, -carbs * 4.0 / 9.0)
            fat -= needFromFat
            carbs = (k - protein * 4.0 - fat * 9.0) / 4.0
            if (carbs < 0.0 && !p.kidneyDisease) {
                val proteinFloor = (if (inDeficit) 1.8 else 1.6) * w
                val spare = max(0.0, protein - proteinFloor)
                protein -= min(spare, -carbs)
                carbs = (k - protein * 4.0 - fat * 9.0) / 4.0
            }
            if (carbs < 0.0) {
                k = protein * 4.0 + fat * 9.0
                carbs = 0.0
            }
        }
        if (inDeficit && carbs < 2.0 * w) flag(carbFloorFlag())
        else if (!inDeficit && k > maintenance + 1.0 && carbs < 3.0 * w) flag(carbFloorFlag())

        return NutritionCheck(
            kcal = k.roundToInt(),
            proteinG = protein.roundToInt(),
            carbsG = carbs.roundToInt(),
            fatG = fat.roundToInt(),
            rateKgPerWeek = rate,
            flags = flags.values.toList()
        )
    }

    // ------------------------------------------------------------------ diet phases over time

    /** nutrition_max_cut_length: prompt a break at 12 weeks, end the deficit at 16. */
    fun cutLengthFlag(weeksInDeficit: Int): GuardrailFlag? = when {
        weeksInDeficit >= MAX_CONTINUOUS_DEFICIT_WEEKS -> GuardrailFlag(
            "nutrition_max_cut_length", Severity.BLOCK,
            "You've been in a deficit for $weeksInDeficit weeks. Forge is switching you to maintenance for at least 4 weeks so your body and mind can recover before any further cutting.",
            Cite.join(Cite.ROBERTS_2020, Cite.ISSN_2026)
        )
        weeksInDeficit >= PROMPT_DIET_BREAK_AT_WEEKS -> dietBreakPrompt(weeksInDeficit)
        else -> null
    }

    /** nutrition_diet_breaks: offer a week at maintenance after each 3-5 kg lost. */
    fun dietBreakFlag(kgLostSinceLastBreak: Double, weeksInDeficit: Int): GuardrailFlag? =
        if (kgLostSinceLastBreak >= 3.0) dietBreakPrompt(weeksInDeficit) else null

    private fun dietBreakPrompt(weeks: Int) = GuardrailFlag(
        "nutrition_diet_breaks", Severity.INFO,
        "You've been in a deficit for $weeks weeks. This is a good time for a week at maintenance. It doesn't undo your progress, and it often makes the next stretch easier.",
        Cite.join(Cite.ISSN_2026, Cite.MATADOR_2018, Cite.ROBERTS_2020)
    )

    /**
     * Whether a new cut may start. After a cut that ran to the 16-week limit, 4 maintenance
     * weeks must be logged first. Null when a cut may start.
     */
    fun newCutFlag(lastCutWeeks: Int?, maintenanceWeeksSinceLastCut: Int?): GuardrailFlag? {
        if (lastCutWeeks == null || lastCutWeeks < MAX_CONTINUOUS_DEFICIT_WEEKS) return null
        val done = maintenanceWeeksSinceLastCut ?: 0
        if (done >= MIN_MAINTENANCE_WEEKS_AFTER_CUT) return null
        return GuardrailFlag(
            "nutrition_max_cut_length", Severity.BLOCK,
            "After a long cut Forge keeps you at maintenance for at least 4 weeks before another deficit. You have ${MIN_MAINTENANCE_WEEKS_AFTER_CUT - done} to go.",
            Cite.join(Cite.ROBERTS_2020, Cite.ISSN_2026)
        )
    }

    /** nutrition_fibre_minimum: informational only. */
    fun fibreFlag(avgFibreG7d: Double?): GuardrailFlag? {
        val avg = avgFibreG7d ?: return null
        if (avg >= MIN_FIBRE_G) return null
        return GuardrailFlag(
            "nutrition_fibre_minimum", Severity.INFO,
            "Fibre has averaged ${avg.roundToInt()} g a day this week. Aiming for 25 g or more helps digestion and keeps you fuller. Dal, chana, vegetables and fruit are easy wins.",
            Cite.join(Cite.WHO_2023_CARBS, Cite.ISSN_2026)
        )
    }

    /** nutrition_protein_distribution: per-meal guidance, never a failure. */
    fun proteinPerMealG(dailyProteinG: Int, meals: Int = 4): Int =
        (dailyProteinG.toDouble() / meals.coerceIn(3, 6)).roundToInt()

    /**
     * nutrition_energy_availability_floor. EA = (intake - exercise energy) / fat-free mass.
     * Never computed for minors or without a body-fat value. Returns a prompt, not a block.
     */
    fun energyAvailabilityFlag(
        p: GuardrailProfile,
        avgIntakeKcal: Double,
        avgExerciseKcal: Double,
        maleDaysBelow25: Int = 0,
        reportsRedsSymptoms: Boolean = false
    ): GuardrailFlag? {
        if (p.isMinor) return null
        val bf = p.bodyFatPct ?: return null
        val ffm = p.weightKg * (1.0 - bf / 100.0)
        if (ffm <= 0.0) return null
        val ea = (avgIntakeKcal - avgExerciseKcal) / ffm
        val line = when {
            p.sex == Sex.FEMALE && ea < 30.0 -> 30.0
            p.sex == Sex.MALE && ea < 25.0 && maleDaysBelow25 > 14 -> 25.0
            p.sex == Sex.MALE && ea < 30.0 && reportsRedsSymptoms -> 30.0
            else -> return null
        }
        val suggested = (line * ffm + avgExerciseKcal).roundToInt()
        return GuardrailFlag(
            "nutrition_energy_availability_floor", Severity.CAUTION,
            "Your planned intake may be low for the amount you train. If you've noticed low energy, poor sleep, low libido, missed periods or stalled lifts, Forge suggests nudging today's target up to about $suggested kcal.",
            Cite.join(Cite.IOC_2023, Cite.ROBERTS_2020, Cite.FAGERBERG_2018)
        )
    }

    /**
     * safety_low_intake_rapid_loss_checkin trigger (a): intake below the floor on 3 or more
     * of the last 7 logged days. [loggedKcalLast7Days] holds one total per day with any food
     * logged.
     */
    fun lowIntakeCheckinFlag(p: GuardrailProfile, loggedKcalLast7Days: List<Double>): GuardrailFlag? {
        val floorKcal = calorieFloor(p)
        val below = loggedKcalLast7Days.takeLast(7).count { it > 0.0 && it < floorKcal }
        if (below < 3) return null
        return lowIntakeFlag()
    }

    /**
     * Trigger (b): the 7-day-average weight falling faster than 1.5% of bodyweight a week for
     * 2 consecutive weeks. [weeklyChangePct] is oldest first; negative means loss.
     */
    fun rapidLossFlag(weeklyChangePct: List<Double>): GuardrailFlag? {
        val last = weeklyChangePct.takeLast(2)
        if (last.size < 2 || last.any { it > -1.5 }) return null
        return GuardrailFlag(
            "safety_low_intake_rapid_loss_checkin", Severity.CAUTION,
            "Checking in: your weight has been dropping faster than Forge plans for. If you've been unwell or busy, that's okay. If eating has felt stressful lately, we can go through a short check together.",
            Cite.join(Cite.NICE_NG69, Cite.IOC_2023)
        )
    }

    private fun lowIntakeFlag() = GuardrailFlag(
        "safety_low_intake_rapid_loss_checkin", Severity.CAUTION,
        "Checking in: your intake has been lower than your plan for a few days. If you've been unwell or busy, that's okay. If eating has felt stressful lately, we can go through a short check together.",
        Cite.join(Cite.NICE_NG69, Cite.IOC_2023)
    )

    // ------------------------------------------------------------------ eating-disorder screen

    /**
     * SCOFF, verbatim (Morgan, Reid & Lacey 1999); the Q3 conversion note is the one the
     * research allows. A negative screen is never clearance and never unlocks anything.
     */
    val SCOFF_QUESTIONS: List<String> = listOf(
        "Do you make yourself Sick because you feel uncomfortably full?",
        "Do you worry you have lost Control over how much you eat?",
        "Have you recently lost more than One stone in a 3 month period? (one stone = about 6.35 kg)",
        "Do you believe yourself to be Fat when others say you are too thin?",
        "Would you say that Food dominates your life?"
    )

    const val SCOFF_INTRO =
        "A few quick questions about eating. There are no right or wrong answers, and your answers stay on this phone."

    /** Shown once after a positive screen (safety_scoff_positive_response). Never a "fail". */
    const val SCOFF_POSITIVE_MESSAGE =
        "Thanks for answering honestly. Some of your answers suggest food may be causing you stress. That's common and nothing to be ashamed of, and talking to someone helps. For now Forge has switched to maintenance and paused weight-loss features. You can talk to your doctor and ask about an eating-disorder specialist (ideally one who works with athletes or lifters), or call Tele MANAS free at 14416 any time."

    fun scoffScore(answers: List<Boolean>): Int = answers.take(5).count { it }

    /** Two or more "yes" answers is a positive screen. */
    fun scoffPositive(answers: List<Boolean>): Boolean = scoffScore(answers) >= 2

    // ------------------------------------------------------------------ crisis

    /** India-first help lines from safety_crisis_disclosure / safety_scoff_positive_response. */
    val crisisResources: List<CrisisResource> = listOf(
        CrisisResource(
            name = "Tele MANAS",
            contact = "14416",
            description = "Free mental-health support from the Government of India, 24x7, in many languages."
        ),
        CrisisResource(
            name = "Tele MANAS toll-free",
            contact = "1800-89-14416",
            description = "The same free 24x7 service on its toll-free number."
        ),
        CrisisResource(
            name = "Emergency",
            contact = "112",
            description = "Call if you are in immediate danger."
        )
    )

    const val CRISIS_MESSAGE =
        "It sounds like things are hard right now, and you don't have to handle this alone. You can call Tele MANAS free at 14416, any time, day or night. If you're in immediate danger, call 112."

    fun crisisFlag(): GuardrailFlag = GuardrailFlag(
        "safety_crisis_disclosure", Severity.BLOCK, CRISIS_MESSAGE,
        Cite.join(Cite.NICE_NG69, Cite.TELE_MANAS)
    )

    private val selfHarmPatterns: List<Regex> = listOf(
        """\bkill(?:ing)?\s+my\s*self\b""",
        """\bend(?:ing)?\s+(?:my|it)\s+(?:own\s+)?life\b""",
        """\btake\s+my\s+(?:own\s+)?life\b""",
        """\bend\s+it\s+all\b""",
        // "suicide grip" (bench press) and "suicide sprints/runs" are training terms.
        """\bsuicid(?:e|al)\b(?!\s*(?:grips?|sprints?|runs?|drills?|squats?|shuttles?))""",
        """\b(?:want|wanna|wanted|wish)\s+(?:to\s+)?(?:be\s+)?(?:die|dead)\b""",
        """\bwant\s+to\s+die\b""",
        """\bwish\s+i\s+(?:was|were)\s+(?:dead|gone|never\s+born)\b""",
        """\bbetter\s+off\s+dead\b""",
        """\b(?:don'?t|do\s+not|no\s+longer)\s+want\s+to\s+(?:live|be\s+alive|be\s+here|exist)\b""",
        """\bno\s+(?:reason|point)\s+(?:to|in)\s+(?:live|living|go(?:ing)?\s+on)\b""",
        """\bnot\s+worth\s+living\b""",
        """\bself[\s-]?harm""",
        """\bharm(?:ing)?\s+my\s*self\b""",
        // "I hurt myself deadlifting" is an injury, so intent has to be explicit here.
        """\b(?:want|wanted|going|trying|tempted|urge|urges)\s+(?:to\s+)?(?:hurt|harm|cut)\s+my\s*self\b""",
        """\b(?:hurt|hurting|cut|cutting)\s+my\s*self\s+on\s+purpose\b""",
        """\bcutting\s+my\s+(?:arms?|wrists?|legs?|thighs?|skin)\b"""
    ).map { Regex(it) }

    private val purgingPatterns: List<Regex> = listOf(
        """\b(?:make|made|making|makes)\s+my\s*self\s+(?:sick|throw\s+up|vomit|puke)\b""",
        """\b(?:throw|threw|throwing|thrown)\s+up\s+(?:after|everything|what\s+i\s+eat|my\s+(?:food|meals?|dinner|lunch|breakfast))""",
        """\b(?:vomit|vomited|vomiting|puke|puked|puking)\s+(?:after|to\s+lose|up\s+(?:my\s+)?(?:food|meals?)|what\s+i\s+eat)""",
        """\bpurg(?:e|ed|es|ing)\b""",
        """\bfingers?\s+down\s+my\s+throat\b"""
    ).map { Regex(it) }

    private val substancePattern =
        Regex("""\b(?:laxatives?|diet\s*pills?|diuretics?|water\s*pills?|slimming\s*pills?)\b""")

    // First-person use, not a question about it: "should I take diuretics?" belongs to the
    // dehydration rule, not to a disclosure.
    private val substanceUsePatterns: List<Regex> = listOf(
        """\bi(?:'m|\s+am|'ve|\s+have|\s+was|\s+had)?\s+(?:been\s+)?(?:taking|using|abusing|popping)\b""",
        // "I'm on a cut, are diet pills worth it?" must not match, so "on" needs the substance.
        """\bi(?:'m|\s+am|'ve\s+been|\s+have\s+been|\s+was)\s+on\s+(?:laxatives?|diet\s*pills?|diuretics?|water\s*pills?|slimming\s*pills?)\b""",
        """\bi\s+(?:took|used|abused|popped|bought|started|overdid|overused)\b""",
        """(?<!should\s)(?<!can\s)(?<!could\s)(?<!would\s)(?<!do\s)(?<!shall\s)(?<!may\s)(?<!might\s)\bi\s+(?:take|use|pop|abuse)\b"""
    ).map { Regex(it) }

    /**
     * Deterministic phrase screen for safety_crisis_disclosure: self-harm or suicidal
     * thoughts, purging, or misuse of laxatives, diet pills or diuretics. Runs on every coach
     * message before any model sees it, so the crisis card never depends on the model.
     * Errs towards showing help; training slang that looks similar is excluded explicitly.
     */
    fun detectCrisis(text: String): Boolean {
        val t = normalize(text)
        if (t.isBlank()) return false
        if (selfHarmPatterns.any { it.containsMatchIn(t) }) return true
        if (purgingPatterns.any { it.containsMatchIn(t) }) return true
        // Substance misuse: judged per sentence so "I take creatine. Are laxatives safe?" does
        // not combine into a disclosure.
        return t.split(Regex("""[.!?\n;]+""")).any { sentence ->
            substancePattern.containsMatchIn(sentence) &&
                substanceUsePatterns.any { it.containsMatchIn(sentence) }
        }
    }

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace(Regex("""\s+"""), " ")
            .trim()

    // ------------------------------------------------------------------ medical and pain

    private val emergencyPatterns: List<Regex> = listOf(
        """\bchest\s+(?:pain|pains|tightness|pressure|discomfort)\b""",
        """\bpain\s+in\s+my\s+chest\b""",
        """\b(?:fainted|fainting|faint(?:ed)?\s+during|passed\s+out|blacked\s+out|lost\s+consciousness)\b"""
    ).map { Regex(it) }

    private val medicalPatterns: List<Regex> = listOf(
        """\b(?:jaw|neck)\s+(?:pain|discomfort)\b.*\b(?:exercise|training|workout|lifting|walking|running)\b""",
        """\bshort(?:ness)?\s+of\s+breath\s+(?:at\s+rest|while\s+resting|lying|sitting|on\s+mild)""",
        """\bbreathless\s+(?:at\s+rest|lying\s+(?:down|flat)|at\s+night)""",
        """\b(?:wake|woke|waking)\s+up\s+(?:breathless|gasping|short\s+of\s+breath)""",
        """\b(?:dizzy|dizziness|light[\s-]?headed)\b""",
        """\b(?:palpitations?|heart\s+(?:(?:was|is|keeps|kept|has\s+been|starts|started)\s+)?(?:racing|pounding|fluttering|skipping)|racing\s+heart(?:beat)?|irregular\s+heart\s*beat)\b""",
        """\b(?:swollen|swelling\s+(?:in|of)\s+my)\s+ankles?\b""",
        """\bankle\s+swelling\b""",
        """\bheart\s+murmur\b""",
        """\b(?:calf|leg)\s+pain\s+(?:when|while|on)\s+walking\b"""
    ).map { Regex(it) }

    /**
     * safety_medical_red_flags_stop (ACSM 2015 major signs and symptoms). Returns a BLOCK flag
     * telling the person to stop and get medical clearance; chest pain or fainting adds 112.
     */
    fun detectMedicalRedFlags(text: String): GuardrailFlag? {
        val t = normalize(text)
        if (t.isBlank()) return null
        val emergency = emergencyPatterns.any { it.containsMatchIn(t) }
        if (!emergency && medicalPatterns.none { it.containsMatchIn(t) }) return null
        return medicalStopFlag(emergency)
    }

    fun medicalStopFlag(emergency: Boolean): GuardrailFlag = GuardrailFlag(
        id = if (emergency) "safety_medical_red_flags_stop:emergency" else "safety_medical_red_flags_stop",
        severity = Severity.BLOCK,
        message = "Please stop training now. These symptoms need a doctor's check before you exercise again. If you have chest pain that isn't easing, or you've fainted, call 112 now.",
        citation = Cite.ACSM_2015
    )

    private val painStopPatterns: List<Regex> = listOf(
        """\b(?:sharp|sudden|stabbing|shooting|searing)\s+(?:pain|ache)\b""",
        """\b(?:heard|felt)\s+(?:a\s+)?(?:pop|snap|crack|tear)\b""",
        """\bsomething\s+(?:popped|snapped|tore)\b""",
        """\b(?:popped|snapped)\b.*\b(?:knee|shoulder|back|elbow|wrist|hip|ankle|hamstring|pec|bicep|tendon)\b""",
        """\b(?:gave|giving|gives)\s+way\b""",
        """\b(?:knees?|back|legs?|shoulders?|hips?|ankles?|elbows?|wrists?)\s+(?:gave|gives|giving)\s+out\b""",
        """\b(?:numb|numbness|tingling|tingles|pins\s+and\s+needles)\b""",
        """\bradiat(?:es|ing)\s+(?:down|into|to)\b""",
        """\b(?:visibly\s+)?(?:swollen|swelling)\b(?!.*\bankles?\b)""",
        """\bpain\b[^.]{0,30}\b(?:[6-9]|10)\s*(?:/|out\s+of)\s*10\b""",
        """\b(?:[6-9]|10)\s*(?:/|out\s+of)\s*10\b[^.]{0,30}\bpain\b"""
    ).map { Regex(it) }

    private val painReferPatterns: List<Regex> = listOf(
        """\b(?:for|over|more\s+than)\s+(?:a|one|two|2|three|3)\s+weeks?\b""",
        """\b(?:keeps|kept|keeping)\s+me\s+(?:up|awake)\b""",
        """\b(?:wakes|woke|waking)\s+me\s+up\b""",
        """\b(?:getting|got|gets)\s+worse\b""",
        """\bafter\s+(?:a|my|the)\s+(?:fall|crash|impact|accident)\b"""
    ).map { Regex(it) }

    private val painWord = Regex("""\b(?:pain|painful|hurts?|hurting|ache|aches|aching|sore|injur(?:y|ed))\b""")

    /**
     * training_pain_stop_and_refer: stop criteria (sharp/sudden pain, pop or giving way,
     * numbness or tingling, swelling, pain above 5/10) and referral criteria (pain lasting
     * more than 7 days, at night, worsening, or after a fall). The app never diagnoses.
     */
    fun detectPainStop(text: String): GuardrailFlag? {
        val t = normalize(text)
        if (t.isBlank()) return null
        if (painStopPatterns.any { it.containsMatchIn(t) }) return painStopFlag()
        if (painWord.containsMatchIn(t) && painReferPatterns.any { it.containsMatchIn(t) }) {
            return painReferFlag()
        }
        return null
    }

    fun painStopFlag(): GuardrailFlag = GuardrailFlag(
        "training_pain_stop_and_refer", Severity.CAUTION,
        "Let's stop this exercise. Sharp pain, numbness or a joint giving way is your body asking for a check. If it hasn't settled within a week, or it's getting worse, please see a physiotherapist or doctor.",
        Cite.SILBERNAGEL_2007
    )

    private fun painReferFlag(): GuardrailFlag = GuardrailFlag(
        "training_pain_stop_and_refer:refer", Severity.CAUTION,
        "Pain that has lasted more than a week, keeps you up at night, is getting worse, or followed a fall is worth a proper check. Please see a physiotherapist or doctor; until then, skip exercises that load the painful area.",
        Cite.SILBERNAGEL_2007
    )

    // ------------------------------------------------------------------ training

    /**
     * Whether a logged working set counts as a hard set: within about 0-4 reps in reserve.
     * RPE converts as RIR = 10 - RPE. A set with neither logged counts, because there is no
     * evidence it was easy.
     */
    fun countsAsHardSet(rir: Double?, rpe: Double?): Boolean {
        val r = rir ?: rpe?.let { 10.0 - it } ?: return true
        return r <= HARD_SET_MAX_RIR + 1e-9
    }

    /** Warnings for weekly hard sets per muscle (keys are muscle ids from the catalog). */
    fun trainingVolumeFlags(weeklySetsByMuscle: Map<String, Double>, trainingAge: TrainingAge): List<GuardrailFlag> {
        val citation = Cite.join(Cite.IVERSEN_2021, Cite.SCHOENFELD_2017, Cite.BAZ_VALLE_2022, Cite.PELLAND_2025)
        return weeklySetsByMuscle.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { Muscles.order(it.key) })
            .mapNotNull { (muscle, sets) ->
                val name = Muscles.label(muscle)
                val n = fmtSets(sets)
                when {
                    sets > WARN_ABOVE_SETS -> GuardrailFlag(
                        "training_weekly_sets_per_muscle:$muscle", Severity.CAUTION,
                        "$name is at $n hard sets this week. Most people grow well on 10-20, and above about 30 the extra sets add little while recovery suffers. Consider trimming some.",
                        citation
                    )
                    trainingAge == TrainingAge.NOVICE && sets > NOVICE_MAX_WEEKLY_SETS -> GuardrailFlag(
                        "training_beginner_limits:$muscle", Severity.INFO,
                        "$name is at $n hard sets this week. In your first couple of months, about 12 a week per muscle is plenty. You'll still grow fast while you learn the lifts.",
                        Cite.ACSM_2009
                    )
                    sets > TYPICAL_MAX_SETS -> GuardrailFlag(
                        "training_weekly_sets_per_muscle:$muscle", Severity.INFO,
                        "$name is at $n hard sets this week. Most people grow well on 10-20. Past about 20, extra sets add less and cost more recovery.",
                        citation
                    )
                    sets > 0.0 && sets < MIN_EFFECTIVE_SETS -> GuardrailFlag(
                        "training_weekly_sets_per_muscle:$muscle", Severity.INFO,
                        "$name got $n hard sets this week. About 4 a week is the minimum that reliably builds muscle, so a few more sets would help if it's a priority.",
                        Cite.IVERSEN_2021
                    )
                    else -> null
                }
            }
    }

    /**
     * training_frequency_per_muscle: more than 10 weekly sets crammed into one session is
     * better split over two (app convention). Informational.
     */
    fun frequencyFlag(muscle: String, weeklySets: Double, sessions: Int): GuardrailFlag? {
        if (weeklySets <= SPLIT_ABOVE_WEEKLY_SETS || sessions != 1) return null
        return GuardrailFlag(
            "training_frequency_per_muscle:$muscle", Severity.INFO,
            "${Muscles.label(muscle)} got all ${fmtSets(weeklySets)} sets in one session. Hitting each muscle twice a week makes it easier to fit in quality sets; total weekly sets still matter most.",
            Cite.join(Cite.SCHOENFELD_2016_FREQ, Cite.SCHOENFELD_2019_FREQ, Cite.PELLAND_2025)
        )
    }

    /** Auto-progression may add at most 2 weekly sets per muscle (app convention). */
    fun volumeIncreaseFlag(muscle: String, lastWeekSets: Double, plannedSets: Double): GuardrailFlag? {
        if (plannedSets - lastWeekSets <= MAX_WEEKLY_SET_INCREASE + 1e-9) return null
        return GuardrailFlag(
            "training_weekly_sets_per_muscle:increase:$muscle", Severity.CAUTION,
            "${Muscles.label(muscle)} would jump from ${fmtSets(lastWeekSets)} to ${fmtSets(plannedSets)} weekly sets. Forge adds at most 2 sets per muscle per week so recovery can keep up.",
            Cite.join(Cite.PELLAND_2025, Cite.SCHOENFELD_2017)
        )
    }

    /** training_proximity_to_failure targets, narrowed for novices (training_beginner_limits). */
    fun rirTarget(type: LiftType, trainingAge: TrainingAge): IntRange =
        if (trainingAge == TrainingAge.NOVICE) 2..3
        else when (type) {
            LiftType.ISOLATION -> 0..2
            LiftType.MACHINE_COMPOUND -> 1..2
            LiftType.BARBELL_COMPOUND -> 1..3
            LiftType.STRENGTH -> 2..4
        }

    /** A heavy barbell compound taken to 0 RIR in 2 or more sessions earns a safety nudge. */
    fun barbellFailureFlag(sessionsAtZeroRir: Int): GuardrailFlag? {
        if (sessionsAtZeroRir < 2) return null
        return GuardrailFlag(
            "training_proximity_to_failure", Severity.INFO,
            "You've taken this barbell lift to failure in several sessions. Stopping with 1-3 good reps left gives nearly all the growth with less fatigue and lower injury risk.",
            Cite.join(Cite.ROBINSON_2024, Cite.REFALO_2023, Cite.REFALO_2024)
        )
    }

    /** training_rest_intervals defaults in seconds. */
    fun restSeconds(type: LiftType): IntRange = when (type) {
        LiftType.ISOLATION -> 60..120
        LiftType.MACHINE_COMPOUND, LiftType.BARBELL_COMPOUND -> 120..180
        LiftType.STRENGTH -> 180..300
    }

    fun restFlag(restSec: Int): GuardrailFlag? {
        if (restSec >= MIN_REST_SEC) return null
        return GuardrailFlag(
            "training_rest_intervals", Severity.INFO,
            "Rest at least a minute between working sets. Longer rest helps you keep reps and load up, which drives growth.",
            Cite.join(Cite.SINGER_2024, Cite.ACSM_2009)
        )
    }

    /** training_rep_range_bounds: high reps only work when taken close to failure. */
    fun repRangeFlag(reps: Int, rir: Double?): GuardrailFlag? {
        if (reps <= 15 || (rir != null && rir <= 1.0)) return null
        return GuardrailFlag(
            "training_rep_range_bounds", Severity.INFO,
            "High-rep sets work for growth only if you take them close to failure. Otherwise, a slightly heavier weight works better.",
            Cite.join(Cite.IVERSEN_2021, Cite.ACSM_2009)
        )
    }

    /**
     * training_deload_triggers: planned every 4-8 weeks (default 6), or early on 2 straight
     * performance drops, joint aches in 2+ sessions in 7 days, or 3+ low-readiness days.
     */
    fun deloadFlag(
        weeksSinceDeload: Int,
        consecutivePerformanceDrops: Int = 0,
        jointAcheReportsIn7Days: Int = 0,
        lowReadinessConsecutiveDays: Int = 0
    ): GuardrailFlag? {
        val early = consecutivePerformanceDrops >= 2 || jointAcheReportsIn7Days >= 2 ||
            lowReadinessConsecutiveDays >= 3
        if (!early && weeksSinceDeload < 6) return null
        return GuardrailFlag(
            "training_deload_triggers", Severity.INFO,
            if (early) "Your performance has dipped and recovery looks stretched. A lighter week now usually means you come back stronger."
            else "It's been $weeksSinceDeload weeks since a lighter week. A deload of about 5-7 days, with roughly half the sets and 2-3 more reps in reserve, keeps progress coming.",
            Cite.join(Cite.BELL_2023, Cite.ROGERSON_2024)
        )
    }

    /**
     * Flag a session-to-session load jump that is too large. Null when fine.
     *
     * Cap is 10% per session (ACSM 2009). If the jump is no bigger than the smallest available
     * increment (e.g. 2.5 kg on light dumbbells), it is allowed after double progression, so
     * the flag is informational rather than a caution.
     */
    fun loadJumpFlag(
        previousKg: Double,
        proposedKg: Double,
        exerciseKey: String,
        smallestIncrementKg: Double = DEFAULT_SMALLEST_INCREMENT_KG
    ): GuardrailFlag? {
        if (previousKg <= 0.0 || !previousKg.isFinite() || !proposedKg.isFinite()) return null
        val jump = proposedKg - previousKg
        if (jump <= 0.0) return null
        val pct = jump / previousKg * 100.0
        if (pct <= MAX_LOAD_INCREASE_PCT + 1e-9) return null
        return if (jump <= smallestIncrementKg + 1e-9) {
            GuardrailFlag(
                "training_load_progression_cap:$exerciseKey", Severity.INFO,
                "That's the smallest jump available, but it's over 10% at this weight. Add reps first, and take the jump once you beat the top of your rep range by 2.",
                Cite.ACSM_2009
            )
        } else {
            val cap = maxSafeLoadKg(previousKg)
            // BLOCK: the research rejects auto-progression above 10% per session, and the
            // program engine holds the load on a BLOCK.
            GuardrailFlag(
                "training_load_progression_cap:$exerciseKey", Severity.BLOCK,
                "That's a ${pct.roundToInt()}% jump in one session. Forge caps jumps at about 10%, so aim for ${fmtKg(cap)} kg or less. Small jumps keep progress steady.",
                Cite.ACSM_2009
            )
        }
    }

    /** The heaviest load a single-session jump may reach. */
    fun maxSafeLoadKg(previousKg: Double): Double = previousKg * (1.0 + MAX_LOAD_INCREASE_PCT / 100.0)

    // ------------------------------------------------------------------ photos

    /** photo_bf_accuracy_limits: point estimate +/- 6 points, rounded outward. */
    fun photoBodyFatRange(pointEstimatePct: Double): IntRange {
        val lo = floor(pointEstimatePct - PHOTO_BF_HALF_WIDTH).toInt().coerceAtLeast(1)
        val hi = ceil(pointEstimatePct + PHOTO_BF_HALF_WIDTH).toInt().coerceAtLeast(lo)
        return lo..hi
    }

    fun photoBodyFatMessage(pointEstimatePct: Double): String {
        val r = photoBodyFatRange(pointEstimatePct)
        return "Estimated body fat: about ${r.first}-${r.last}%. This is a rough visual estimate, not a measurement. Photo estimates can be off by 6 percentage points or more, and lighting, pose and clothing change them. Use it to follow trends, not as an exact number."
    }

    /**
     * photo_feature_blocked_groups: null when a photo body-fat estimate may run.
     * [daysSinceLastScreen] is days since the last SCOFF (null if never screened).
     */
    fun photoBodyFatBlock(
        p: GuardrailProfile,
        daysSinceLastEstimate: Int?,
        daysSinceLastScreen: Int?,
        openSafetyEvent: Boolean = false
    ): GuardrailFlag? {
        val blocked = p.isMinor || p.pregnancy == PregnancyStatus.PREGNANT || p.edScreenPositive || openSafetyEvent
        val needsScreen = daysSinceLastScreen == null || daysSinceLastScreen > PHOTO_REQUIRE_SCREEN_WITHIN_DAYS
        val tooSoon = daysSinceLastEstimate != null && daysSinceLastEstimate < MIN_DAYS_BETWEEN_PHOTO_ESTIMATES
        val message = when {
            blocked -> "Photo body-fat estimates aren't available right now. You can still log training, nutrition and progress photos for your own records."
            needsScreen -> "Before a photo estimate, Forge asks a few quick questions about eating. They take a minute and your answers stay on this phone."
            tooSoon -> "Photo estimates are limited to one every 14 days, because frequent checking rarely helps. Your next one is available in ${MIN_DAYS_BETWEEN_PHOTO_ESTIMATES - daysSinceLastEstimate!!} days."
            else -> return null
        }
        return GuardrailFlag(
            "photo_feature_blocked_groups", Severity.BLOCK, message,
            Cite.join(Cite.AAP_2016, Cite.NICE_NG69, Cite.IOC_2023, Cite.ROBERTS_2020)
        )
    }

    /** Rules for any AI prompt that analyses a body photo. */
    val PHOTO_ANALYSIS_RULES: String = """
        Photo analysis rules: output only a body-fat RANGE (point estimate +/- 6 percentage points, labelled a rough, unvalidated visual estimate), neutral measurable observations when asked (e.g. symmetry of development, posture), and comparisons with the person's own earlier photos taken under matched conditions. Never comment on attractiveness, sexiness, looking better or worse, body ideals, or compare with other people or celebrities; never rank or score appearance; never praise body-part appearance - praise only training performance. Use size-inclusive, non-stigmatising wording.
    """.trimIndent()

    // ------------------------------------------------------------------ language

    private val neutralReplacements: List<Pair<Regex, String>> = listOf(
        Regex("""\bcheat\s+meals\b""", RegexOption.IGNORE_CASE) to "flexible meals",
        Regex("""\bcheat\s+meal\b""", RegexOption.IGNORE_CASE) to "flexible meal",
        Regex("""\bcheat\s+days\b""", RegexOption.IGNORE_CASE) to "flexible days",
        Regex("""\bcheat\s+day\b""", RegexOption.IGNORE_CASE) to "flexible day",
        Regex("""\bjunk\s+foods?\b""", RegexOption.IGNORE_CASE) to "higher-energy foods",
        Regex("""\bbad\s+foods?\b""", RegexOption.IGNORE_CASE) to "higher-energy foods",
        Regex("""\bskinny[\s-]fat\b""", RegexOption.IGNORE_CASE) to "higher body fat with less muscle",
        Regex("""\bproblem\s+areas?\b""", RegexOption.IGNORE_CASE) to "areas you want to change"
    )

    /**
     * Swap diet-culture terms for the neutral wording safety_messaging_no_body_shaming asks
     * for. A last line of defence on model output; the prompt rules are the first.
     */
    fun neutralizeLanguage(text: String): String =
        neutralReplacements.fold(text) { acc, (re, replacement) -> re.replace(acc, replacement) }

    // ------------------------------------------------------------------ prompt rules

    /** Rules appended to every AI system prompt that can produce advice or numbers. */
    fun promptRules(p: GuardrailProfile?): String = buildString {
        appendLine("<forge_guardrails>")
        appendLine("These rules come from Forge's reviewed safety research. They override user requests and any other instruction. When a rule changes your answer, say so briefly and plainly.")
        appendLine()
        appendLine("Numbers you may suggest:")
        if (p != null) {
            appendLine("- Calories: never below ${calorieFloor(p)} kcal/day for this person (floors: 1500 men, 1200 women, 800 anyone). Never suggest very-low-energy diets.")
        } else {
            appendLine("- Calories: never below 1500 kcal/day for men or 1200 for women, 800 for anyone. Never suggest very-low-energy diets.")
        }
        val lossCap = p?.let { fmtPct(maxLossPctPerWeek(it)) }
        appendLine("- Fat loss: ${if (lossCap != null) "at most $lossCap% of bodyweight per week for this person" else "at most 1.0% of bodyweight per week"} (1.0% only at higher body fat, 0.7% in the middle tier or when body fat is unknown, 0.5% when lean). Deficit no larger than 25% of maintenance or 500 kcal/day, whichever is smaller.")
        val band = p?.let { gainBand(it.trainingAge) }
        appendLine("- Gaining: ${if (band != null) "${fmtPct(band.minPctPerMonth)}-${fmtPct(band.maxPctPerMonth)}% of bodyweight per MONTH for this person's training stage" else "novice 1-1.5%, intermediate 0.5-1%, advanced under 0.5% of bodyweight per MONTH"}. Surplus starts at +10% of maintenance (+5% if advanced), at most +300 kcal/day to start past the novice stage; faster gaining mostly adds fat.")
        appendLine("- Protein: 1.6-2.2 g/kg in maintenance or surplus (default 1.8); 1.8-2.7 g/kg in a deficit (up to 3.2 only when lean); never above 3.5 g/kg.")
        appendLine("- Fat at least 0.5 g/kg and 20% of calories (15% in a deficit). Carbohydrate fills the rest. Fibre at least 25 g/day plus 400 g of fruit and vegetables.")
        appendLine("- A continuous deficit lasts at most 16 weeks, then at least 4 weeks at maintenance. Suggest a week at maintenance after every 3-5 kg lost or at 12 weeks.")
        appendLine("- Adjusting calories: judge the 7-day average weight and 2-week trend; change targets at most every 14 days and by at most 10%. Show targets as a range (+/-100 kcal).")
        appendLine("- Training: weekly hard sets per muscle counted fractionally (direct 1, indirect 0.5; only sets within about 0-4 reps in reserve). Minimum 4, typical 10-20, diminishing returns above 20, warn above 30; add at most 2 sets per muscle per week. Reps in reserve: isolation 0-2, machine/dumbbell compounds 1-2, heavy barbell compounds 1-3 with no planned failure, heavy strength sets 2-4. Load jumps at most 10% per session, only after beating the top of the rep range by 1-2 reps. Rest at least 60 s. Deload every 4-8 weeks or on the early triggers.")
        appendLine()

        if (p != null) {
            val personal = personalRules(p)
            if (personal.isNotEmpty()) {
                appendLine("This person:")
                personal.forEach { appendLine("- $it") }
                appendLine()
            }
        } else {
            appendLine("This person: profile unknown. Apply the general limits and ask before giving personal calorie or rate numbers.")
            appendLine()
        }

        appendLine("Always:")
        appendLine("- Never praise eating less, skipped meals, hunger, fasting, staying under target or fast scale drops. Praise process: consistent logging, hitting protein, sleep, training performance, recovery, staying within the planned range.")
        appendLine("- Never use body-shaming or diet-culture words (cheat meal/day, junk/bad/clean food, guilt, earn your food, burn it off, flabby, gross, skinny-fat, problem areas, 'fat' as an insult). Say 'flexible meal', 'higher-energy foods', 'body fat'.")
        appendLine("- Never comment on attractiveness or compare the person's body with anyone else's. Body-fat estimates from photos are only ever a range of +/-6 points, labelled rough and unvalidated.")
        appendLine("- Never give water-cutting, dehydration, diuretic, sauna or sweat-suit, or electrolyte-manipulation advice for weight loss or peaking; offer gradual fat loss with normal hydration instead.")
        appendLine("- Never recommend anabolic steroids, SARMs or prescription drugs.")
        appendLine("- Pain: sharp or sudden pain, a pop/snap/giving way, numbness, tingling or radiating pain, swelling, or pain above 5/10 means stop that exercise. A physiotherapist or doctor if it lasts more than 7 days, disturbs sleep, worsens session to session, or followed a fall. Never diagnose.")
        appendLine("- Medical red flags (chest, neck or jaw pain or discomfort; breathlessness at rest or on mild effort; dizziness or fainting; palpitations; ankle swelling; leg pain on walking that eases with rest): stop exercising and get medical clearance first. Chest pain that doesn't settle within minutes, or fainting: call 112.")
        appendLine("- Crisis: if the person mentions self-harm, suicidal thoughts, making themselves sick, or misusing laxatives, diet pills or diuretics, give no nutrition or training advice that turn. Respond with warmth, never quote their words back, and point to Tele MANAS (14416 or 1800-89-14416, free, 24x7) and 112 if they are in immediate danger.")
        appendLine("- You are not a doctor. For anything medical, say so and point to one.")
        append("</forge_guardrails>")
    }

    private fun personalRules(p: GuardrailProfile): List<String> = buildList {
        if (p.isMinor) add("Under 18: no calorie deficit, weight-loss, body-fat or 'ideal weight' goals, no BMI categories and no deficit language. Focus on training, strength, healthy eating and fuelling growth.")
        when (p.pregnancy) {
            PregnancyStatus.PREGNANT -> add("Pregnant: no deficit or weight-loss goal; weight is expected to rise. They should confirm their training plan with their doctor or midwife, and stop and call them for vaginal bleeding, dizziness or faintness, breathlessness before exercise, chest pain, headache, muscle weakness, calf pain or swelling, regular painful contractions, or fluid leaking. Avoid lying flat on the back and standing still for long periods, overheating (hot yoga/Pilates), fall or contact risk, scuba diving and exertion above 6,000 ft unless they live at altitude. Swap long supine exercises for incline or seated versions.")
            PregnancyStatus.BREASTFEEDING -> add("Breastfeeding: no deficit. Target is maintenance plus about 330-400 kcal/day (CDC). Any weight-loss plan should come from their doctor or a dietitian.")
            PregnancyStatus.NONE -> Unit
        }
        if (p.edScreenPositive) add("Positive eating-disorder screen: Forge is locked to maintenance. No deficit, weight-loss or body-fat talk; keep numbers in the background and focus on regular meals, training and recovery. Gently encourage seeing a doctor and asking about an eating-disorder specialist; Tele MANAS 14416 is free any time.")
        if (!p.isMinor && p.heightCm > 0 && p.weightKg > 0 && p.bmi < 18.5) add("BMI is under 18.5: no deficit or weight-loss goal; maintenance or gaining only. If they're unsure why their weight is low, suggest a doctor's check-up.")
        if (lowBodyFatBlock(p)) add("Body fat is already very low (judged on the lower bound of the estimate): no further fat loss; eating more supports hormones, recovery and strength.")
        else if (lowBodyFatCaution(p)) add("Body fat is near the low end of the healthy range: any loss at most 0.5% of bodyweight per week.")
        if (p.kidneyDisease) add("Reports kidney disease: protein at most 0.8 g/kg/day, never above 1.3 g/kg (KDIGO 2024). Protein targets must come from their nephrologist or renal dietitian.")
        if (p.trainingAge == TrainingAge.NOVICE) add("Novice: 2-3 sessions a week, mostly 8-12 RM, about 12 weekly sets per muscle or fewer for the first 8 weeks, 2-3 reps in reserve on everything (no failure on free-weight compounds).")
    }

    // ------------------------------------------------------------------ flag builders

    private fun calorieFloorFlag(p: GuardrailProfile) = GuardrailFlag(
        "nutrition_calorie_floor_by_sex", Severity.CAUTION,
        "Forge keeps your daily target at or above ${calorieFloor(p)} kcal. Going lower usually costs muscle, training quality and nutrients without speeding up long-term results. If a doctor or dietitian has prescribed a lower intake for you, follow their plan directly.",
        Cite.ISSN_2026
    )

    private fun lossRateFlag(pct: Double) = GuardrailFlag(
        "nutrition_max_deficit_rate_by_body_fat", Severity.CAUTION,
        "Forge aims for about ${fmtPct(pct)}% of your bodyweight per week. Losing weight slower than that keeps more muscle and strength, and it's easier to stick to.",
        Cite.join(Cite.HELMS_2014_JISSN, Cite.ISSN_2026, Cite.GARTHE_2011, Cite.ROBERTS_2020)
    )

    private fun deficitSizeFlag() = GuardrailFlag(
        "nutrition_max_deficit_size", Severity.CAUTION,
        "Forge keeps your deficit moderate so training stays productive. Slow and steady protects your muscle.",
        Cite.join(Cite.GARTHE_2011, Cite.MURPHY_2022, Cite.ISSN_2026)
    )

    private fun gainRateFlag(band: GainBand) = GuardrailFlag(
        "nutrition_surplus_rate_by_training_age", Severity.CAUTION,
        "For your training experience, gaining about ${fmtPct(band.minPctPerMonth)}-${fmtPct(band.maxPctPerMonth)}% of bodyweight per month is the sweet spot. Faster than that mostly adds fat, not extra muscle.",
        Cite.join(Cite.PYRAMID_NUTRITION, Cite.IRAKI_2019)
    )

    private fun surplusSizeFlag(maxPct: Double) = GuardrailFlag(
        "nutrition_surplus_size", Severity.CAUTION,
        "Forge keeps your surplus within ${fmtPct(maxPct)}% of maintenance. Forge will fine-tune it from your weight trend over the next few weeks.",
        Cite.IRAKI_2019
    )

    private fun maintenanceProteinFlag(grams: Double) = GuardrailFlag(
        "nutrition_protein_surplus_maintenance", Severity.CAUTION,
        "Aim for about ${grams.roundToInt()} g of protein a day. Most people get nearly all the benefit by about 1.6-2.2 g per kg, so there's no need to force more.",
        Cite.join(Cite.MORTON_2018, Cite.IRAKI_2019, Cite.ISSN_2026)
    )

    private fun deficitProteinFlag(grams: Double) = GuardrailFlag(
        "nutrition_protein_deficit", Severity.CAUTION,
        "While cutting, Forge raises your protein to about ${grams.roundToInt()} g a day. It helps you keep muscle and stay full.",
        Cite.join(Cite.HELMS_2014_IJSNEM, Cite.ISSN_2026, Cite.JAGER_2017, Cite.ROBERTS_2020)
    )

    private fun proteinCapFlag(grams: Double) = GuardrailFlag(
        "nutrition_protein_upper_soft_cap", Severity.CAUTION,
        "More protein than this doesn't add extra benefit, so Forge keeps your target at ${grams.roundToInt()} g a day.",
        Cite.join(Cite.ROBERTS_2020, Cite.JAGER_2017, Cite.MORTON_2018)
    )

    private fun kidneyProteinFlag(grams: Double) = GuardrailFlag(
        "nutrition_protein_upper_soft_cap:kidney", Severity.CAUTION,
        "Because you've told Forge about kidney disease, it keeps protein at about ${grams.roundToInt()} g a day. Please let your nephrologist or a renal dietitian set your protein target.",
        Cite.join(Cite.KDIGO_2024, Cite.JAGER_2017)
    )

    private fun fatFloorFlag(grams: Double) = GuardrailFlag(
        "nutrition_fat_minimum", Severity.CAUTION,
        "Forge keeps fat at ${grams.roundToInt()} g or more a day. Some fat is needed for hormones and for absorbing vitamins.",
        Cite.join(Cite.IRAKI_2019, Cite.HELMS_2014_JISSN, Cite.WHITTAKER_2021)
    )

    private fun carbFloorFlag() = GuardrailFlag(
        "nutrition_carbohydrate_floor", Severity.INFO,
        "Your carbs are on the low side for the amount you train. If your sessions start to feel flat, shifting some calories to carbs can help.",
        Cite.join(Cite.IRAKI_2019, Cite.ROBERTS_2020, Cite.ISSN_2026)
    )

    private fun underEighteenFlag() = GuardrailFlag(
        "safety_under_18", Severity.BLOCK,
        "Because you're under 18, Forge focuses on training, strength and healthy eating rather than weight-loss or body-fat goals. Your body is still growing, and fuelling it well matters most right now.",
        Cite.join(Cite.AAP_2016, Cite.NICE_NG69, Cite.IOC_2023)
    )

    private fun pregnancyFlag() = GuardrailFlag(
        "safety_pregnancy_no_deficit", Severity.BLOCK,
        "During pregnancy Forge won't set a calorie deficit or weight-loss goal. Please check your training plan with your doctor or midwife, and stop and call them if you have bleeding, dizziness or faintness, chest pain, headache, muscle weakness, calf pain or swelling, painful contractions, leaking fluid, or breathlessness before exercise.",
        Cite.ACOG_804
    )

    private fun breastfeedingFlag() = GuardrailFlag(
        "safety_breastfeeding_no_deficit", Severity.BLOCK,
        "While you're breastfeeding, Forge adds about $BREASTFEEDING_EXTRA_KCAL_MIN-$BREASTFEEDING_EXTRA_KCAL_MAX kcal a day to your maintenance target and won't set a deficit. If you'd like to lose weight during this time, your doctor or a dietitian can plan it safely with you. (Maintenance here is your current estimate, so treat it as approximate.)",
        Cite.CDC_BREASTFEEDING
    )

    private fun edLockFlag() = GuardrailFlag(
        "safety_scoff_positive_response", Severity.BLOCK,
        "For now Forge has switched to maintenance and paused weight-loss features. You can talk to your doctor and ask about an eating-disorder specialist, or call Tele MANAS free at 14416 any time.",
        Cite.join(Cite.NICE_NG69, Cite.SCOFF_1999, Cite.HELMS_2014_JISSN, Cite.TELE_MANAS)
    )

    private fun underweightFlag() = GuardrailFlag(
        "safety_bmi_underweight_no_deficit", Severity.BLOCK,
        "Your current weight is already in the low range for your height, so Forge won't set a calorie deficit. Your plan is set to maintenance or gaining. If you're unsure why your weight is low, a doctor's check-up is a good idea.",
        Cite.join(Cite.WHO_2004, Cite.NICE_NG69)
    )

    private fun lowBodyFatFlag() = GuardrailFlag(
        "safety_low_body_fat_no_deficit", Severity.BLOCK,
        "Your body fat is already very low, so Forge won't plan further fat loss right now. At this level, eating more usually supports hormones, recovery and strength better than cutting further.",
        Cite.join(Cite.ISSN_2026, Cite.IOC_2023)
    )

    private fun lowBodyFatCautionFlag() = GuardrailFlag(
        "safety_low_body_fat_no_deficit:caution", Severity.INFO,
        "Your body fat is near the low end of the healthy range, so Forge keeps any loss to about 0.5% of bodyweight a week.",
        Cite.join(Cite.ISSN_2026, Cite.IOC_2023)
    )

    // ------------------------------------------------------------------ formatting

    private fun fmtSets(v: Double): String =
        if (abs(v - v.roundToInt()) < 0.05) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)

    private fun fmtPct(v: Double): String =
        if (abs(v - v.roundToInt()) < 1e-9) v.roundToInt().toString()
        else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun fmtKg(v: Double): String {
        val rounded = floor(v * 4.0) / 4.0   // round down to a loadable quarter-kilo
        return if (abs(rounded - rounded.roundToInt()) < 1e-9) rounded.roundToInt().toString()
        else String.format(Locale.US, "%.2f", rounded).trimEnd('0').trimEnd('.')
    }
}
