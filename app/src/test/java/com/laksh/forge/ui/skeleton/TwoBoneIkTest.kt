package com.laksh.forge.ui.skeleton

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

class TwoBoneIkTest {

    private val l1 = Anthro.THIGH
    private val l2 = Anthro.SHANK

    /** Joints 0 (root), 1 (mid), 2 (end). */
    private fun chain(rx: Float = 0f, ry: Float = 1f, rz: Float = 0f) = FloatArray(9).also {
        it[0] = rx; it[1] = ry; it[2] = rz
        // Arbitrary straight start: the solver must not depend on the previous mid/end.
        it[3] = rx; it[4] = ry - l1; it[5] = rz
        it[6] = rx; it[7] = ry - l1 - l2; it[8] = rz
    }

    private fun dist(p: FloatArray, i: Int, j: Int) = V.dist(p, i, j)

    private fun distTo(p: FloatArray, i: Int, x: Float, y: Float, z: Float): Float {
        val dx = p[i * 3] - x; val dy = p[i * 3 + 1] - y; val dz = p[i * 3 + 2] - z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    @Test fun reachableTargetIsHitExactly() {
        val p = chain()
        val ok = TwoBoneIk.solve(p, 0, 1, 2, 0.1f, 0.35f, 0.25f, 0f, 1f, 2f, l1, l2)
        assertThat(ok).isTrue()
        assertThat(distTo(p, 2, 0.1f, 0.35f, 0.25f)).isWithin(1e-4f).of(0f)
        assertThat(dist(p, 0, 1)).isWithin(1e-4f).of(l1)
        assertThat(dist(p, 1, 2)).isWithin(1e-4f).of(l2)
    }

    @Test fun boneLengthsArePreservedForAnyTarget() {
        val rnd = Random(42)
        repeat(2000) {
            val p = chain()
            val tx = rnd.nextFloat() * 3f - 1.5f
            val ty = rnd.nextFloat() * 3f - 0.5f
            val tz = rnd.nextFloat() * 3f - 1.5f
            val reachable = TwoBoneIk.solve(p, 0, 1, 2, tx, ty, tz, 0f, 1f, 3f, l1, l2)
            assertThat(dist(p, 0, 1)).isWithin(1e-4f).of(l1)
            assertThat(dist(p, 1, 2)).isWithin(1e-4f).of(l2)
            val d = distTo(p, 0, tx, ty, tz)
            if (d < l1 + l2 - 1e-3f && d > kotlin.math.abs(l1 - l2) + 1e-3f) {
                assertThat(reachable).isTrue()
                assertThat(distTo(p, 2, tx, ty, tz)).isWithin(2e-4f).of(0f)
            }
        }
    }

    @Test fun outOfReachTargetGivesStraightChainPointingAtIt() {
        val p = chain()
        val ok = TwoBoneIk.solve(p, 0, 1, 2, 0f, 1f, 3f, 0f, 2f, 1f, l1, l2)
        assertThat(ok).isFalse()
        assertThat(dist(p, 0, 1)).isWithin(1e-4f).of(l1)
        assertThat(dist(p, 1, 2)).isWithin(1e-4f).of(l2)
        // Fully extended along +z from the root.
        assertThat(p[8]).isWithin(1e-3f).of(l1 + l2)
        assertThat(p[6]).isWithin(1e-3f).of(0f)
        assertThat(p[7]).isWithin(1e-3f).of(1f)
    }

    @Test fun middleJointBendsTowardThePole() {
        val p = chain()
        // Target straight below the root; pole in front (+z) means the knee goes forward.
        TwoBoneIk.solve(p, 0, 1, 2, 0f, 0.4f, 0f, 0f, 0.7f, 1f, l1, l2)
        assertThat(p[5]).isGreaterThan(0.05f)
        val q = chain()
        TwoBoneIk.solve(q, 0, 1, 2, 0f, 0.4f, 0f, 0f, 0.7f, -1f, l1, l2)
        assertThat(q[5]).isLessThan(-0.05f)
    }

    @Test fun targetOnTheRootStaysFinite() {
        val p = chain()
        TwoBoneIk.solve(p, 0, 1, 2, 0f, 1f, 0f, 0f, 1f, 0f, Anthro.UPPER_ARM, Anthro.FOREARM)
        p.forEach { assertThat(it.isNaN()).isFalse() }
        assertThat(dist(p, 0, 1)).isWithin(1e-4f).of(Anthro.UPPER_ARM)
        assertThat(dist(p, 1, 2)).isWithin(1e-4f).of(Anthro.FOREARM)
    }
}
