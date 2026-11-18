package com.laksh.forge.ui.skeleton

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Analytic two-bone IK (hip-knee-ankle, shoulder-elbow-wrist).
 *
 * The law of cosines gives how far along the root-to-target line the middle joint sits and
 * how far it bends off that line; the pole point picks which way it bends. Closed form, so
 * it costs the same every frame and never jitters the way iterative solvers can.
 */
object TwoBoneIk {

    /**
     * Solves the chain rooted at joint [root] in [pos] (flat xyz array) so that joint [end]
     * reaches (tx, ty, tz), writing [mid] and [end] in place. Bone lengths [l1] (root-mid) and
     * [l2] (mid-end) are always preserved exactly. The middle joint bends towards the pole
     * point (px, py, pz). Returns false when the target was out of reach; the chain then
     * points straight at it, fully extended (or folded, if the target is too close).
     */
    fun solve(
        pos: FloatArray, root: Int, mid: Int, end: Int,
        tx: Float, ty: Float, tz: Float,
        px: Float, py: Float, pz: Float,
        l1: Float, l2: Float
    ): Boolean {
        val rx = pos[root * 3]
        val ry = pos[root * 3 + 1]
        val rz = pos[root * 3 + 2]
        var dx = tx - rx
        var dy = ty - ry
        var dz = tz - rz
        var d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d < 1e-6f) {
            // Target on the root: aim at the pole so the direction is at least defined.
            dx = px - rx; dy = py - ry; dz = pz - rz
            d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < 1e-6f) { dx = 0f; dy = -1f; dz = 0f; d = 1f }
            dx /= d; dy /= d; dz /= d
            d = 0f
        } else {
            dx /= d; dy /= d; dz /= d
        }

        val maxReach = l1 + l2
        val minReach = abs(l1 - l2)
        val reachable = d <= maxReach * (1f + 1e-5f) && d >= minReach * (1f - 1e-5f)
        val dc = d.coerceIn(minReach + 1e-6f, maxReach - 1e-6f)

        // Component of the pole direction perpendicular to the chain axis.
        var wx = px - rx
        var wy = py - ry
        var wz = pz - rz
        val along = wx * dx + wy * dy + wz * dz
        wx -= along * dx; wy -= along * dy; wz -= along * dz
        var wl = sqrt(wx * wx + wy * wy + wz * wz)
        if (wl < 1e-6f) {
            // Pole on the axis: pick any perpendicular (cross with world up, else world x).
            if (abs(dy) < 0.9f) { wx = -dz; wy = 0f; wz = dx } else { wx = 0f; wy = dz; wz = -dy }
            wl = sqrt(wx * wx + wy * wy + wz * wz)
        }
        wx /= wl; wy /= wl; wz /= wl

        val a = (l1 * l1 - l2 * l2 + dc * dc) / (2f * dc)
        val h = sqrt((l1 * l1 - a * a).coerceAtLeast(0f))

        pos[mid * 3] = rx + dx * a + wx * h
        pos[mid * 3 + 1] = ry + dy * a + wy * h
        pos[mid * 3 + 2] = rz + dz * a + wz * h

        // Place the end along the solved forearm/shank so its length is exact even when the
        // target was clamped.
        var ex = rx + dx * dc - pos[mid * 3]
        var ey = ry + dy * dc - pos[mid * 3 + 1]
        var ez = rz + dz * dc - pos[mid * 3 + 2]
        val el = sqrt(ex * ex + ey * ey + ez * ez)
        if (el > 1e-9f) { ex = ex / el * l2; ey = ey / el * l2; ez = ez / el * l2 }
        pos[end * 3] = pos[mid * 3] + ex
        pos[end * 3 + 1] = pos[mid * 3 + 1] + ey
        pos[end * 3 + 2] = pos[mid * 3 + 2] + ez
        return reachable
    }
}
