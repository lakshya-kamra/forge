package com.laksh.forge.domain.exercise

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Maps free text ("BB Bench (paused)", "lat pull-down", "3. RDLs 3x8") to a catalog id.
 *
 * Program PDFs and hand-typed logs spell the same lift a dozen ways, so matching runs in
 * decreasing order of confidence and stops at the first unambiguous answer:
 *  1. exact match on a normalised name or alias (spacing, plurals, abbreviations folded),
 *  2. a small typo distance on the whole name ("romanain deadlift"); this runs before the
 *     containment step so a misspelt qualifier can't fall through to a shorter name that
 *     happens to be spelt right ("deadlift"),
 *  3. a known name appearing inside the text ("Paused barbell bench press"),
 *  4. token overlap, only when one candidate clearly wins.
 * Anything ambiguous returns null: a wrong demo is worse than a search link.
 */
class ExerciseNameMatcher(entries: List<Pair<String, List<String>>>) {

    private class Key(val id: String, val tokens: List<String>, val compact: String)

    private val exact = HashMap<String, String>()
    private val compact = HashMap<String, String>()
    private val keys = ArrayList<Key>()

    init {
        for ((id, names) in entries) {
            for (raw in names) {
                val tokens = tokens(raw)
                if (tokens.isEmpty()) continue
                val joined = tokens.joinToString(" ")
                val comp = tokens.joinToString("")
                exact.putIfAbsent(joined, id)
                compact.putIfAbsent(comp, id)
                keys += Key(id, tokens, comp)
            }
        }
    }

    fun match(text: String): String? {
        val variants = LinkedHashSet<List<String>>()
        variants += tokens(text)
        variants += tokens(PARENS.replace(text, " "))
        variants.removeAll { it.isEmpty() }
        if (variants.isEmpty()) return null

        for (v in variants) {
            exact[v.joinToString(" ")]?.let { return it }
            compact[v.joinToString("")]?.let { return it }
        }
        for (v in variants) typo(v)?.let { return it }
        for (v in variants) containment(v)?.let { return it }
        for (v in variants) overlap(v)?.let { return it }
        return null
    }

    /** Longest known name that appears as a contiguous run of tokens in the text. */
    private fun containment(q: List<String>): String? {
        var bestId: String? = null
        var bestLen = 0
        var bestEnd = -1
        var tie = false
        for (k in keys) {
            val n = k.tokens.size
            if (n > q.size) continue
            if (n == 1 && (k.tokens[0].length < 3 || k.tokens[0] in WEAK_SINGLE)) continue
            var end = -1
            for (start in q.size - n downTo 0) {
                var ok = true
                for (i in 0 until n) if (q[start + i] != k.tokens[i]) { ok = false; break }
                if (ok) { end = start + n - 1; break }
            }
            if (end < 0) continue
            // Longer names win; on equal length the later one wins because English puts the
            // head noun last ("leg press calf raise" is a calf raise).
            when {
                n > bestLen || (n == bestLen && end > bestEnd) -> {
                    bestId = k.id; bestLen = n; bestEnd = end; tie = false
                }
                n == bestLen && end == bestEnd && k.id != bestId -> tie = true
            }
        }
        return if (tie) null else bestId
    }

    private fun typo(q: List<String>): String? {
        val s = q.joinToString("")
        if (s.length < 6) return null
        val allowed = if (s.length >= 12) 2 else 1
        var best: String? = null
        var bestD = Int.MAX_VALUE
        var tie = false
        for (k in keys) {
            if (abs(k.compact.length - s.length) > allowed) continue
            val d = levenshtein(s, k.compact, allowed)
            if (d > allowed) continue
            when {
                d < bestD -> { best = k.id; bestD = d; tie = false }
                d == bestD && k.id != best -> tie = true
            }
        }
        return if (tie) null else best
    }

    private fun overlap(q: List<String>): String? {
        val qs = q.toSet()
        var best: String? = null
        var bestScore = 0.0
        var second = 0.0
        for (k in keys) {
            val ks = k.tokens.toSet()
            val inter = ks.count { it in qs }
            if (inter == 0) continue
            val dice = 2.0 * inter / (ks.size + qs.size)
            if (dice > bestScore) {
                if (k.id != best) second = bestScore
                best = k.id; bestScore = dice
            } else if (dice > second && k.id != best) {
                second = dice
            }
        }
        return if (bestScore >= 0.8 && bestScore - second >= 0.1) best else null
    }

    companion object {
        private val PARENS = Regex("\\([^)]*\\)|\\[[^]]*]")
        private val LIST_MARKER = Regex("^\\s*(?:[a-z]?\\d{1,2}[a-z]?|[a-z])[.):]\\s+")
        private val NON_WORD = Regex("[^a-z0-9]+")
        private val SCHEME = Regex("^\\d+(?:x\\d+)*$|^\\d+x$|^x\\d+$")

        private val ABBREVIATIONS = mapOf(
            "db" to "dumbbell", "dbs" to "dumbbell", "bb" to "barbell", "kb" to "kettlebell",
            "ezbar" to "ez", "flye" to "fly", "flys" to "fly", "flyes" to "fly", "w" to "with",
            "tri" to "tricep", "bi" to "bicep", "sl" to "single"
        )
        private val NOISE = setOf("x", "rep", "reps", "set", "sets", "kg", "kgs", "lb", "lbs", "rpe", "rir", "each", "per", "the", "an", "of", "and")
        /** Single words too generic to identify a lift on their own when embedded in other text. */
        private val WEAK_SINGLE = setOf("bench", "sumo", "hyper", "lateral", "clean", "nordic", "adductor", "rollout")

        fun tokens(text: String): List<String> {
            var s = text.lowercase()
                .replace('’', '\'')
                .replace("'", "")
                .replace("&", " and ")
            s = LIST_MARKER.replace(s, "")
            return NON_WORD.split(s)
                .asSequence()
                .filter { it.isNotEmpty() }
                .map { ABBREVIATIONS[it] ?: it }
                .filter { it !in NOISE && !SCHEME.matches(it) }
                .map(::singular)
                .toList()
        }

        /** Crude English singular: enough to fold "squats", "presses", "flies", "crunches". */
        fun singular(w: String): String = when {
            w.length <= 2 -> w
            w.endsWith("sses") -> w.dropLast(2)
            w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
            w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes") -> w.dropLast(2)
            w.endsWith("ss") || w.endsWith("us") || w.endsWith("is") -> w
            w.endsWith("s") -> w.dropLast(1)
            else -> w
        }

        /** Levenshtein distance with an early exit once every path exceeds [limit]. */
        fun levenshtein(a: String, b: String, limit: Int): Int {
            if (a == b) return 0
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                var rowMin = cur[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                    rowMin = min(rowMin, cur[j])
                }
                if (rowMin > limit) return limit + 1
                val t = prev; prev = cur; cur = t
            }
            return max(0, prev[b.length])
        }
    }
}
