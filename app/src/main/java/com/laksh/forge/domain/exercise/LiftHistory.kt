package com.laksh.forge.domain.exercise

/** The minimum a logged set needs to say for the history charts. */
data class LiftSet(
    val sessionId: Long,
    val weightKg: Double,
    val reps: Int,
    val timestamp: Long
)

/** One session's best work on a lift. */
data class SessionBest(
    val sessionId: Long,
    val timestamp: Long,
    /** Best Epley estimate in the session, or null when no set qualified. */
    val bestE1rmKg: Double?,
    val topWeightKg: Double,
    val topWeightReps: Int,
    val maxReps: Int,
    val sets: Int,
    val volumeKg: Double
)

data class LiftRecord(
    val weightKg: Double,
    val reps: Int,
    val timestamp: Long,
    /** The figure being recorded: e1RM, weight, reps or volume depending on the record. */
    val value: Double
)

data class LiftHistory(
    val sessions: List<SessionBest>,
    val totalSets: Int,
    val bestE1rm: LiftRecord?,
    val heaviest: LiftRecord?,
    val mostReps: LiftRecord?,
    val bestSetVolume: LiftRecord?,
    /** True when no set carried external load (pull-ups, planks logged as reps only). */
    val bodyweightOnly: Boolean
) {
    val isEmpty: Boolean get() = totalSets == 0
}

object LiftMath {

    /** Epley loses accuracy beyond roughly a dozen reps, so higher-rep sets don't count. */
    const val MAX_E1RM_REPS = 12

    /** Epley estimate: w x (1 + reps / 30); a single is its own 1RM. */
    fun epley(weightKg: Double, reps: Int): Double? = when {
        weightKg <= 0.0 || reps <= 0 || reps > MAX_E1RM_REPS -> null
        reps == 1 -> weightKg
        else -> weightKg * (1.0 + reps / 30.0)
    }

    fun summarize(sets: List<LiftSet>): LiftHistory {
        val valid = sets.filter { it.reps > 0 }
        if (valid.isEmpty()) {
            return LiftHistory(emptyList(), 0, null, null, null, null, bodyweightOnly = false)
        }
        val sessions = valid.groupBy { it.sessionId }.map { (id, s) ->
            val top = s.maxWith(compareBy<LiftSet> { it.weightKg }.thenBy { it.reps })
            SessionBest(
                sessionId = id,
                timestamp = s.minOf { it.timestamp },
                bestE1rmKg = s.mapNotNull { epley(it.weightKg, it.reps) }.maxOrNull(),
                topWeightKg = top.weightKg,
                topWeightReps = top.reps,
                maxReps = s.maxOf { it.reps },
                sets = s.size,
                volumeKg = s.sumOf { it.weightKg * it.reps }
            )
        }.sortedBy { it.timestamp }

        // Ties go to the earliest set: a record is the day you first did it.
        fun best(selector: (LiftSet) -> Double?): LiftRecord? {
            var rec: LiftRecord? = null
            for (s in valid.sortedBy { it.timestamp }) {
                val v = selector(s) ?: continue
                if (rec == null || v > rec.value + 1e-9) rec = LiftRecord(s.weightKg, s.reps, s.timestamp, v)
            }
            return rec
        }

        val loaded = valid.any { it.weightKg > 0.0 }
        return LiftHistory(
            sessions = sessions,
            totalSets = valid.size,
            bestE1rm = best { epley(it.weightKg, it.reps) },
            heaviest = if (loaded) best { it.weightKg.takeIf { w -> w > 0.0 } } else null,
            mostReps = best { it.reps.toDouble() },
            bestSetVolume = if (loaded) best { (it.weightKg * it.reps).takeIf { v -> v > 0.0 } } else null,
            bodyweightOnly = !loaded
        )
    }
}
