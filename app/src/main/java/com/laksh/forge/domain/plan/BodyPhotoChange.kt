package com.laksh.forge.domain.plan

import com.laksh.forge.data.ai.AiPart
import com.laksh.forge.data.ai.ForgeAi
import com.laksh.forge.data.db.BodyMeasurement
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.PregnancyStatus
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.remote.Task
import com.laksh.forge.domain.guardrails.GuardrailProfile
import com.laksh.forge.domain.guardrails.Guardrails
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------- gate

/** Whether "Estimate my change" may run, and if not, the calm reason shown instead. */
data class PhotoGate(
    val allowed: Boolean,
    val reason: String? = null,
    /** The only thing missing is a recent eating screen (Profile settings can re-ask it). */
    val needsRescreen: Boolean = false
)

/**
 * guardrails.final.json photo_feature_blocked_groups, applied to the progress-photo change
 * estimate. The blocked groups (under 18, pregnant, a positive SCOFF or crisis disclosure
 * still inside its 30-day lock, an open low-intake safety event) and the screen-recency and
 * 14-day rules all come from [Guardrails.photoBodyFatBlock]; this only gathers the inputs
 * from settings and the plan profile so the screen and the tests share one decision.
 */
object BodyPhotoGate {

    private const val DAY_MS = 86_400_000L

    fun evaluate(s: ForgeSettings, profile: PlanProfile, now: Long = System.currentTimeMillis()): PhotoGate {
        // A lock that has run its 30 days stays on until a negative re-screen, so the stored
        // flag counts as well as the window (safety_scoff_positive_response).
        val eatingConcern = s.edScreenPositive || edScreenLockActive(s, now) || profile.crisisLockActive(now)
        val guardrail = Guardrails.profileFrom(s, 0.0, null).copy(edScreenPositive = eatingConcern)
        val daysSinceScreen = s.edScreenAt.takeIf { it > 0 }?.let { ((now - it) / DAY_MS).toInt() }
        val daysSinceEstimate = profile.lastPhotoEstimateAt.takeIf { it > 0 }?.let { ((now - it) / DAY_MS).toInt() }
        val block = Guardrails.photoBodyFatBlock(
            guardrail,
            daysSinceLastEstimate = daysSinceEstimate,
            daysSinceLastScreen = daysSinceScreen,
            openSafetyEvent = profile.safetyEventOpen(now)
        )
        if (block != null) {
            val hardBlocked = guardrail.isMinor ||
                guardrail.pregnancy == PregnancyStatus.PREGNANT ||
                eatingConcern || profile.safetyEventOpen(now)
            val screenMissing = daysSinceScreen == null || daysSinceScreen > Guardrails.PHOTO_REQUIRE_SCREEN_WITHIN_DAYS
            return PhotoGate(allowed = false, reason = block.message, needsRescreen = !hardBlocked && screenMissing)
        }
        // The person asked not to see body numbers; a body-fat estimate would ignore that.
        if (s.hideNumbers) {
            return PhotoGate(
                allowed = false,
                reason = "You chose to hide numbers, so Forge doesn't estimate body fat. Comparing photos side by side still works."
            )
        }
        return PhotoGate(allowed = true)
    }
}

// ---------------------------------------------------------------------------- ranges

/**
 * photo_bf_accuracy_limits: a photo estimate is shown only as the point estimate +/-6 points,
 * rounded outward to whole percentages, and never narrower than the model's own range.
 */
object PhotoBodyFat {

    /** The tape formula's sanity window; anything outside it is a misread, not a body. */
    val PLAUSIBLE = 2.0..70.0

    fun range(estimatePct: Double, modelLow: Double? = null, modelHigh: Double? = null): IntRange {
        val base = Guardrails.photoBodyFatRange(estimatePct)
        // A model range is only allowed to widen ours, and only within reason: a 2-70% "range"
        // is a non-answer that would make the trend look flat.
        val lo = min(base.first, modelLow?.takeIf { it.isFinite() }?.let { floor(max(it, estimatePct - 12)).toInt() } ?: base.first)
        val hi = max(base.last, modelHigh?.takeIf { it.isFinite() }?.let { ceil(min(it, estimatePct + 12)).toInt() } ?: base.last)
        return lo.coerceAtLeast(1)..hi.coerceAtLeast(lo.coerceAtLeast(1))
    }

    fun label(r: IntRange): String = "${r.first}-${r.last}%"
}

// ---------------------------------------------------------------------------- selection

/** One date's photos as the estimate sees them; [T] is whatever the caller holds per photo. */
data class DateGroup<T>(val date: LocalDate, val items: List<T>)

object PhotoSelection {

    const val MAX_PHOTOS = 8
    const val MIN_DATES = 2

    /** Oldest first, so "change" always reads from the first set to the latest. */
    fun <T> groupByDate(items: List<Pair<LocalDate, T>>): List<DateGroup<T>> =
        // sortedBy, not toSortedMap: LocalDate is Comparable<ChronoLocalDate>, which would
        // widen the key type.
        items.groupBy({ it.first }, { it.second })
            .entries
            .sortedBy { it.key }
            .map { DateGroup(it.key, it.value) }

    /** Why the chosen photos can't be estimated yet, or null when they can. */
    fun problem(dates: List<LocalDate?>, today: LocalDate): String? = when {
        dates.size > MAX_PHOTOS -> "Choose up to $MAX_PHOTOS photos - for example front and side from a few months."
        dates.any { it == null } -> "Set the date for every photo first, so Forge knows which set came first."
        dates.any { it!!.isAfter(today) } -> "One of the dates is in the future - check it and try again."
        dates.filterNotNull().distinct().size < MIN_DATES -> "Add photos from at least two different dates to see a change."
        else -> null
    }

    /**
     * The tape measurement closest to [date] within [maxDays], for context. Tape is the more
     * reliable signal, so the model sees it next to the photos from the same week.
     */
    fun nearestMeasurement(date: LocalDate, all: List<BodyMeasurement>, maxDays: Long = 10): BodyMeasurement? =
        all.mapNotNull { m ->
            val d = runCatching { LocalDate.parse(m.localDate) }.getOrNull() ?: return@mapNotNull null
            if (m.waistCm == null && m.neckCm == null && m.hipsCm == null) return@mapNotNull null
            val gap = abs(ChronoUnit.DAYS.between(d, date))
            if (gap > maxDays) null else m to gap
        }.minByOrNull { it.second }?.first

    fun tapeText(m: BodyMeasurement): String = listOfNotNull(
        m.waistCm?.let { "waist ${"%.1f".format(it)} cm" },
        m.neckCm?.let { "neck ${"%.1f".format(it)} cm" },
        m.hipsCm?.let { "hips ${"%.1f".format(it)} cm" }
    ).joinToString(", ")
}

// ---------------------------------------------------------------------------- result

enum class ChangeDirection(val label: String) {
    DOWN("Likely down"),
    UP("Likely up"),
    STABLE("About the same"),
    UNCLEAR("Unclear");

    companion object {
        fun from(raw: String?): ChangeDirection = entries.firstOrNull { it.name == raw } ?: UNCLEAR
    }
}

data class DatedPhotoEstimate(
    val date: LocalDate,
    /** The model's point estimate; kept for saving, never shown on its own. */
    val estimatePct: Double,
    val range: IntRange
)

data class BodyChangeEstimate(
    /** Oldest first. */
    val perDate: List<DatedPhotoEstimate>,
    val direction: ChangeDirection,
    /** Latest minus first, from the per-date estimates; whole points. */
    val changePoints: Int,
    /** LOW | MEDIUM | HIGH */
    val confidence: String,
    val photoIssues: String
)

/** Input for one date: its photos (base64 JPEG, built just before the call) and any tape. */
class ChangeDateInput(val date: LocalDate, val imagesBase64: List<String>, val tape: String?)

/**
 * The progress-photo change estimate: one Task.BODY structured call with every date's photos,
 * oldest first. The model returns a range per date and an overall direction; Forge widens each
 * range to +/-6 (rounded outward) and derives the change from the per-date numbers, so the
 * headline can never disagree with the ranges shown under it.
 */
object BodyChangeAi {

    /** Under this many points, two estimates are within the noise of matched photos. */
    const val STABLE_WITHIN = 2

    private val SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject("usable") { put("type", "boolean") }
            putJsonObject("per_date") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    put("additionalProperties", false)
                    putJsonObject("properties") {
                        putJsonObject("date") { put("type", "string") }
                        putJsonObject("estimate_pct") { put("type", "number") }
                        putJsonObject("low_pct") { put("type", "number") }
                        putJsonObject("high_pct") { put("type", "number") }
                    }
                    putJsonArray("required") { add("date"); add("estimate_pct"); add("low_pct"); add("high_pct") }
                }
            }
            putJsonObject("direction") {
                put("type", "string")
                putJsonArray("enum") { add("DOWN"); add("UP"); add("STABLE"); add("UNCLEAR") }
            }
            putJsonObject("change_points") { put("type", "number") }
            putJsonObject("confidence") {
                put("type", "string")
                putJsonArray("enum") { add("LOW"); add("MEDIUM"); add("HIGH") }
            }
            putJsonObject("photo_issues") { put("type", "string") }
        }
        putJsonArray("required") {
            add("usable"); add("per_date"); add("direction"); add("change_points"); add("confidence"); add("photo_issues")
        }
    }

    suspend fun estimate(
        ai: ForgeAi,
        /** Null when no weight is logged yet; the rules then go in without person-specific numbers. */
        profile: GuardrailProfile?,
        sex: Sex,
        age: Int,
        heightCm: Double,
        dates: List<ChangeDateInput>
    ): BodyChangeEstimate {
        val ordered = dates.sortedBy { it.date }
        require(ordered.map { it.date }.distinct().size >= PhotoSelection.MIN_DATES) { "Photos from at least two dates are needed." }

        val system = buildString {
            appendLine("You compare one adult's own progress photos taken on different dates, for a fitness app.")
            appendLine("For each date give a body-fat estimate with your own low/high range; then the overall direction from the first date to the last, the approximate change in percentage points (last minus first), your confidence, and short neutral notes about photo conditions.")
            appendLine("Rules:")
            appendLine("- Judge each date on its own, then compare. Differences in lighting, pose, distance, clothing, pump or time of day can look like body change: when conditions differ, lower your confidence and say which condition differed in photo_issues.")
            appendLine("- If the photos are not of one adult's whole or upper body, or clearly show different people, set usable=false.")
            appendLine("- Tape measurements, when given, are more reliable than photos; let them inform the direction.")
            appendLine("- Never comment on attractiveness, appearance quality, body ideals or comparisons with other people, and never rank or score appearance. photo_issues is only about capture conditions, never about the body.")
            appendLine("- Visual estimates are unvalidated and can be off by 6 or more points; give honest ranges.")
            appendLine(Guardrails.PHOTO_ANALYSIS_RULES)
            appendLine(Guardrails.promptRules(profile))
        }
        val parts = buildList<AiPart> {
            add(
                AiPart.Text(
                    "Person: ${sex.name.lowercase()}, age $age, height ${heightCm.roundToInt()} cm. " +
                        "Photos from ${ordered.size} dates follow, oldest first. Use these exact dates (yyyy-MM-dd) in per_date."
                )
            )
            ordered.forEach { d ->
                add(AiPart.Text("Date ${d.date}" + (d.tape?.takeIf { it.isNotBlank() }?.let { " (tape that week: $it)" } ?: "") + ":"))
                d.imagesBase64.forEach { add(AiPart.Image(it, "image/jpeg")) }
            }
        }
        // Thinking counts toward the cap, and several dates of photos take longer to weigh
        // than the single onboarding set.
        val obj = ai.structured(Task.BODY, system, parts, "body_fat_change", SCHEMA, maxTokens = 16000)
        return parse(obj, ordered.map { it.date })
    }

    /** Pure, so the widening and the reconciliation can be tested without a model. */
    fun parse(obj: JsonObject, dates: List<LocalDate>): BodyChangeEstimate {
        val issuesRaw = obj["photo_issues"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val issues = if (PlanAi.hasBlockedLanguage(issuesRaw)) "" else issuesRaw
        if (obj["usable"]?.jsonPrimitive?.booleanOrNull != true) {
            throw PlanAiRejected("These photos couldn't be compared" + (if (issues.isNotBlank()) ": $issues" else "."))
        }
        val rows = runCatching { obj["per_date"]!!.jsonArray.map { it.jsonObject } }.getOrDefault(emptyList())
        val byDate = rows.mapNotNull { r ->
            val date = r["date"]?.jsonPrimitive?.contentOrNull?.let { runCatching { LocalDate.parse(it.trim().take(10)) }.getOrNull() }
            val est = r["estimate_pct"]?.jsonPrimitive?.doubleOrNull
            if (date == null || est == null) null
            else date to Triple(est, r["low_pct"]?.jsonPrimitive?.doubleOrNull, r["high_pct"]?.jsonPrimitive?.doubleOrNull)
        }.toMap()

        val perDate = dates.distinct().sortedBy { it }.map { date ->
            val (est, low, high) = byDate[date]
                ?: throw PlanAiRejected("The estimate didn't cover every date, so Forge ignored it. Try again.")
            if (est !in PhotoBodyFat.PLAUSIBLE) {
                throw PlanAiRejected("An estimate was outside a plausible range, so Forge ignored the result.")
            }
            DatedPhotoEstimate(date, est, PhotoBodyFat.range(est, low?.let { min(it, high ?: it) }, high?.let { max(it, low ?: it) }))
        }

        val delta = perDate.last().estimatePct - perDate.first().estimatePct
        val change = delta.roundToInt()
        val numeric = when {
            change <= -STABLE_WITHIN -> ChangeDirection.DOWN
            change >= STABLE_WITHIN -> ChangeDirection.UP
            else -> ChangeDirection.STABLE
        }
        val model = ChangeDirection.from(obj["direction"]?.jsonPrimitive?.contentOrNull)
        // A headline that contradicts the per-date numbers would be the one thing people read,
        // so a disagreement is reported as unclear rather than picking a side.
        val direction = if (model == ChangeDirection.UNCLEAR || model != numeric) ChangeDirection.UNCLEAR else numeric
        val confidence = obj["confidence"]?.jsonPrimitive?.contentOrNull?.takeIf { it in setOf("LOW", "MEDIUM", "HIGH") } ?: "LOW"
        return BodyChangeEstimate(perDate, direction, change, confidence, issues)
    }

    /** The headline, always with the caveat attached. */
    fun summary(e: BodyChangeEstimate): String {
        val points = abs(e.changePoints)
        val conf = e.confidence.lowercase()
        return when (e.direction) {
            ChangeDirection.DOWN -> "Likely down, roughly $points point${if (points == 1) "" else "s"} of body fat ($conf confidence)."
            ChangeDirection.UP -> "Likely up, roughly $points point${if (points == 1) "" else "s"} of body fat ($conf confidence)."
            ChangeDirection.STABLE -> "About the same - any change is smaller than these photos can show ($conf confidence)."
            ChangeDirection.UNCLEAR -> "Unclear - the photos don't show a consistent change. Matching the light and pose more closely usually helps."
        }
    }
}
