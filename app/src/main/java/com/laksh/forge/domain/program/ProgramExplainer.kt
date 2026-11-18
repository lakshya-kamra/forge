package com.laksh.forge.domain.program

/**
 * Plain-language summary of how a program progresses. Every number comes from the template's
 * engine block, which the converter read from the program's own text; the author's words are
 * shown separately, verbatim, so the user can always check this summary against the source.
 */
object ProgramExplainer {

    fun explain(p: LibraryProgram): List<String> {
        val e = p.engine
        val inc = p.progression.incrementKg
        val out = mutableListOf<String>()
        fun pct(v: Double) = "${Strength.fmt(v * 100)}%"
        val incText = when {
            inc?.upper != null && inc.lower != null && inc.upper != inc.lower ->
                "${Strength.fmt(inc.upper)} kg on upper-body lifts and ${Strength.fmt(inc.lower)} kg on the big lower-body lifts"
            inc?.upper != null -> "${Strength.fmt(inc.upper)} kg"
            else -> null
        }
        when (e.model) {
            "LINEAR" -> {
                val cadence = if (e.cadence == "WEEK") "each week" else "each session"
                out += "Add weight $cadence${incText?.let { " ($it)" }.orEmpty()} as long as you complete every prescribed rep."
                if (e.failLimit != null && e.deloadPct != null) {
                    out += "Miss reps and you repeat the weight. After ${e.failLimit} misses in a row at one weight, that lift drops ${pct(e.deloadPct)} and works back up."
                }
                if (e.bounceBack) out += "After the lighter session you go straight back to the weight you missed."
                if (e.scope == "DAY_SLOT") out += "Each training day progresses on its own, so the same lift can use different loads on different days."
                if (p.days.any { d -> d.exercises.any { it.rule == Rules.DOUBLE } }) {
                    out += "Accessories use double progression: add reps until every set reaches the top of the range, then add weight."
                }
            }
            "DOUBLE" -> {
                out += "Double progression: keep the weight until every set reaches the top of its rep range, then add${incText?.let { " $it" } ?: " a small step"} and build the reps back up."
                if (p.days.any { d -> d.exercises.any { it.perSet } }) out += "Each set of the pyramid has its own rep range and progresses on its own."
                if (e.deloadWeeks.isNotEmpty() && e.deloadSets != null) {
                    out += "Weeks ${e.deloadWeeks.joinToString(" and ")} are deloads: ${e.deloadSets} set per exercise at the same effort."
                }
                if (p.days.any { d -> d.exercises.any { it.rule == Rules.RIR } }) {
                    out += "Top sets are run by effort: log your reps in reserve and the next load moves with it."
                }
            }
            "GZCLP" -> {
                out += "T1 lifts run 5×3 with an AMRAP last set, adding weight each time until you miss 15 total reps; then 6×2+ at the same weight, then 10×1+. After that, test a new 5RM and restart at 85% of it."
                out += "T2 lifts run 3×10, then 3×8, then 3×6, adding weight while the reps are there; after 3×6 stalls, restart 3×10 slightly heavier than before."
                out += "T3 lifts are 3×15 with an AMRAP last set; add weight once that set reaches 25 reps."
            }
            "WENDLER" -> {
                out += "Every percentage is of a training max (TM) set at ${e.tmOfMax?.let { pct(it) } ?: "a share"} of your max."
                out += "Weeks run 5s, 3s, then 5/3/1, with the last set of each main lift for as many good reps as possible. Week 4 is an optional deload."
                out += "After each cycle the TM rises${incText?.let { " by $it" }.orEmpty()} - unless a rep was missed, then it holds."
            }
            "NSUNS" -> {
                out += "Every set is a percentage of a training max (TM) set at ${e.tmOfMax?.let { pct(it) } ?: "a share"} of your 1RM."
                val rules = e.amrapRules.joinToString("; ") { r ->
                    val range = when {
                        r.maxReps == null -> "${r.minReps}+ reps"
                        r.minReps == r.maxReps -> "${r.minReps} reps"
                        else -> "${r.minReps}-${r.maxReps} reps"
                    }
                    "$range → +${Strength.fmt(r.addKg)} kg"
                }
                out += "The 95% \"1+\" set on each lift's main day decides next week's TM: $rules (the app uses the low end of each range in the sheet)."
                if (e.allRepsKeys.isNotEmpty()) {
                    out += "${e.allRepsKeys.joinToString { ProgressionAdvance.liftName(it) }} has no 1+ set here, so its TM rises when every rep is completed."
                }
            }
            "CANDITO" -> {
                out += "Main lifts are percentages of the 1RM you enter, across five themed weeks: conditioning, hypertrophy, linear max overload, heavy acclimation, then intense strength."
                out += "Missing a required rep cuts that lift's max by ${e.missedRepCutPct?.let { pct(it) } ?: "a little"} (the author's PDF says 5%; the spreadsheet's 2.5% is used)."
                out += "The week-5 test set projects next cycle's max: ×1.03 for 2 reps, ×1.06 for 3, ×1.09 for 4."
            }
            "TEXAS" -> {
                out += "Monday is volume: 5×5 at about 90% of your 5-rep max. Wednesday is light (about 80% of Monday). Friday you go for a new 5-rep max."
                out += "Hit 5 reps on Friday and that becomes your new 5RM, which lifts Monday and Wednesday the following week."
            }
            "MADCOW" -> {
                out += "Each main lift ramps up in 12.5% steps to a top set of 5. Friday adds a triple 2.5% heavier, which becomes next Monday's top set."
                out += "Weeks start below your current 5RM (you hit it in week 4) so the first weeks build momentum. Miss reps and the weight holds a week."
            }
            "WAVE" -> {
                out += "Wave loading: each week of a block adds load and drops a rep per set (two for 8-12 ranges)${incText?.let { " - $it per step" }.orEmpty()}."
                out += "Week 4 is an optional deload at week-1 loads with about a third fewer sets; a short checklist decides whether you take it."
                out += "Each new block starts one step heavier than the last block's week 1. 12-15 rep isolation work uses double progression."
            }
            "JT2" -> {
                out += "T1: work up to the week's rep max, then drop sets - percentages of a training max in block 1, of that day's rep max in block 2."
                out += "T2a lifts are percentages of their own training max; T2b, T2c and T3 find a rep max, then three more sets with 1-2 reps in reserve."
                out += "Weeks 6 and 12 end with a 1RM test; tested singles become the new training maxes."
            }
            else -> out += "Follow the author's rules below; the app tracks every set and fills in last session's numbers."
        }
        if (e.incrementIsAppDefault) {
            out += "The author doesn't give a load step, so the app uses 2.5 kg (or 1.25 kg if you have small plates)."
        }
        out += "Every suggested jump is checked against Forge's load safety limits before you see it."
        return out
    }
}
