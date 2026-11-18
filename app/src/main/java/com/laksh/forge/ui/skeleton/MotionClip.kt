package com.laksh.forge.ui.skeleton

import com.laksh.forge.domain.exercise.ExerciseMotion
import com.laksh.forge.domain.exercise.MotionKeyframe
import com.laksh.forge.domain.exercise.PhaseKind

/**
 * Keyframe channels with monotone cubic (Fritsch-Carlson) interpolation.
 *
 * Keyframe times in the data are already spaced by the tempo (2 s down, 1 s up and so on), so
 * time itself carries the tempo. The interpolation supplies the easing: tangents fall to zero
 * wherever a channel turns around or holds, so every eccentric/concentric phase eases in and
 * out of its turnaround and pauses really pause, while intermediate keyframes are passed
 * through smoothly instead of with a visible kink. Monotone tangents never overshoot, so a
 * joint never bends past the range the data gives.
 */
class MotionClip(private val motion: ExerciseMotion) {

    companion object {
        const val TRUNK = 0
        const val SPINE = 1
        const val HIP = 2        // + side
        const val KNEE = 4       // + side
        const val ANKLE = 6      // + side
        const val ABD = 8        // + side
        const val UPPER_ARM = 10 // + side * 3 + axis
        const val HAND = 16      // + side * 3 + axis
        const val SCAP_R = 22
        const val SCAP_E = 23
        const val GRIP = 24
        const val CHANNELS = 25
    }

    val times: FloatArray
    private val values: Array<FloatArray>
    private val tangents: Array<FloatArray>
    private val hasGrip: Boolean

    init {
        // Ensure the loop is closed: the data's last frame equals the first, but guard anyway.
        val src = ArrayList<MotionKeyframe>(motion.keyframes.sortedBy { it.t })
        require(src.isNotEmpty()) { "Motion has no keyframes" }
        val dedup = ArrayList<MotionKeyframe>()
        for (k in src) {
            if (dedup.isNotEmpty() && kotlin.math.abs(dedup.last().t - k.t) < 1e-5f) dedup[dedup.size - 1] = k else dedup += k
        }
        val frames = ArrayList<Pair<Float, MotionKeyframe>>()
        if (dedup.first().t > 1e-4f) frames += 0f to dedup.last()
        dedup.forEach { frames += it.t to it }
        if (frames.last().first < 1f - 1e-4f) frames += 1f to frames.first().second
        if (frames.size == 1) frames += 1f to frames.first().second

        val n = frames.size
        times = FloatArray(n) { frames[it].first }
        hasGrip = frames.any { !it.second.grip.isNaN() }
        values = Array(CHANNELS) { FloatArray(n) }
        for (k in 0 until n) {
            val f = frames[k].second
            val v = values
            v[TRUNK][k] = f.trunk
            v[SPINE][k] = f.spine
            for (s in 0..1) {
                v[HIP + s][k] = f.hip[s]
                v[KNEE + s][k] = f.knee[s]
                v[ANKLE + s][k] = f.ankle[s]
                v[ABD + s][k] = f.abduction[s]
                for (a in 0..2) {
                    v[UPPER_ARM + s * 3 + a][k] = f.upperArm[s * 3 + a]
                    v[HAND + s * 3 + a][k] = f.hand[s * 3 + a]
                }
            }
            v[SCAP_R][k] = f.scapRetraction
            v[SCAP_E][k] = f.scapElevation
            v[GRIP][k] = if (f.grip.isNaN()) (motion.gripWidth ?: 1f) else f.grip
        }
        tangents = Array(CHANNELS) { c -> monotoneTangents(times, values[c]) }
    }

    /** Samples every channel at normalised rep time [t] (wrapped into 0..1) into [out]. */
    fun sample(t: Float, out: FloatArray) {
        var tt = t - kotlin.math.floor(t)
        if (tt < 0f) tt = 0f
        val n = times.size
        var k = 0
        while (k < n - 2 && tt > times[k + 1]) k++
        val t0 = times[k]
        val h = (times[k + 1] - t0).coerceAtLeast(1e-6f)
        val s = ((tt - t0) / h).coerceIn(0f, 1f)
        val s2 = s * s
        val s3 = s2 * s
        val h00 = 2f * s3 - 3f * s2 + 1f
        val h10 = s3 - 2f * s2 + s
        val h01 = -2f * s3 + 3f * s2
        val h11 = s3 - s2
        for (c in 0 until CHANNELS) {
            val v = values[c]
            val m = tangents[c]
            out[c] = h00 * v[k] + h10 * h * m[k] + h01 * v[k + 1] + h11 * h * m[k + 1]
        }
        if (!hasGrip) out[GRIP] = motion.gripWidth ?: 1f
    }

    /** Index into [ExerciseMotion.phases] for rep time [t], or -1. */
    fun phaseIndexAt(t: Float): Int {
        val tt = t - kotlin.math.floor(t)
        val phases = motion.phases
        for (i in phases.indices) if (tt >= phases[i].start && tt <= phases[i].end) return i
        return -1
    }

    /**
     * Muscle glow intensity at rep time [t]: it swells through the concentric phase (the
     * lifting effort) and rests at a low steady level otherwise.
     */
    fun effortAt(t: Float): Float {
        val i = phaseIndexAt(t)
        if (i < 0) return 0.35f
        val p = motion.phases[i]
        return when (p.kind) {
            PhaseKind.CONCENTRIC -> {
                val x = ((t - kotlin.math.floor(t) - p.start) / (p.end - p.start).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
                0.35f + 0.65f * kotlin.math.sin(x * Math.PI.toFloat())
            }
            PhaseKind.HOLD -> if (motion.phases.size == 1) 0.6f else 0.45f
            PhaseKind.MOVE -> 0.5f
            PhaseKind.ECCENTRIC -> 0.35f
        }
    }

    private fun monotoneTangents(t: FloatArray, v: FloatArray): FloatArray {
        val n = t.size
        val m = FloatArray(n)
        if (n < 2) return m
        val h = FloatArray(n - 1) { (t[it + 1] - t[it]).coerceAtLeast(1e-6f) }
        val d = FloatArray(n - 1) { (v[it + 1] - v[it]) / h[it] }
        fun blend(dPrev: Float, dNext: Float, hPrev: Float, hNext: Float): Float {
            if (dPrev * dNext <= 0f) return 0f
            // Fritsch-Butland weighted harmonic mean: stays between the neighbouring slopes.
            return 3f * (hPrev + hNext) / ((2f * hNext + hPrev) / dPrev + (hNext + 2f * hPrev) / dNext)
        }
        for (k in 1 until n - 1) m[k] = blend(d[k - 1], d[k], h[k - 1], h[k])
        // The rep loops, so the first/last tangent looks across the seam.
        val seam = blend(d[n - 2], d[0], h[n - 2], h[0])
        m[0] = seam
        m[n - 1] = seam
        return m
    }
}
