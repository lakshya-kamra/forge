package com.laksh.forge.domain.plan

import com.laksh.forge.data.db.PhasePlan
import com.laksh.forge.domain.guardrails.GuardrailFlag
import com.laksh.forge.domain.guardrails.Guardrails
import com.laksh.forge.domain.guardrails.Phases
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * nutrition_max_cut_length from the plan history. Consecutive CUT plans count as one cut, so
 * re-planning a cut (even at week 15) doesn't restart the 16-week clock; any other phase ends
 * the run. Plans are never deleted when replaced, only deactivated, so the next plan's start
 * date is when the previous one ended.
 */
data class CutHistory(
    /** Weeks the current cut has run, counting chained CUT plans; null when no cut is active. */
    val activeCutWeeks: Int?,
    /** Length of the most recent cut (active or finished); null when there was none. */
    val lastCutWeeks: Int?,
    /** Whole weeks out of a deficit since that cut ended; 0 while it is still active. */
    val maintenanceWeeksSince: Int?
) {
    /** BLOCK flag while a new cut must wait for its maintenance weeks; null when one may start. */
    val newCutFlag: GuardrailFlag? get() = Guardrails.newCutFlag(lastCutWeeks, maintenanceWeeksSince)

    /** The active cut has reached the limit and must end now. */
    val cutLimitReached: Boolean
        get() = (activeCutWeeks ?: 0) >= Guardrails.MAX_CONTINUOUS_DEFICIT_WEEKS

    companion object {
        fun of(plans: List<PhasePlan>, today: LocalDate): CutHistory {
            val ordered = plans.sortedBy { it.createdAt }
            var runStart: LocalDate? = null
            var runEnd: LocalDate? = null
            var lastStart: LocalDate? = null
            var lastEnd: LocalDate? = null
            var activeRun = false
            for ((i, p) in ordered.withIndex()) {
                val start = parse(p.startDate) ?: continue
                val nextStart = ordered.getOrNull(i + 1)?.let { parse(it.startDate) }
                if (p.phase == Phases.CUT) {
                    if (runStart == null) runStart = start
                    runEnd = nextStart ?: if (p.isActive) today else parse(p.endDate) ?: today
                    activeRun = p.isActive
                } else if (runStart != null) {
                    lastStart = runStart; lastEnd = runEnd
                    runStart = null; runEnd = null; activeRun = false
                }
            }
            if (runStart != null) {
                lastStart = runStart; lastEnd = runEnd
            }
            if (lastStart == null || lastEnd == null) return CutHistory(null, null, null)
            val weeks = (ChronoUnit.DAYS.between(lastStart, lastEnd) / 7).toInt()
            val since = if (activeRun) 0 else (ChronoUnit.DAYS.between(lastEnd, today) / 7).toInt().coerceAtLeast(0)
            return CutHistory(
                activeCutWeeks = if (activeRun) weeks else null,
                lastCutWeeks = weeks,
                maintenanceWeeksSince = since
            )
        }

        private fun parse(d: String?): LocalDate? = d?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }
}
