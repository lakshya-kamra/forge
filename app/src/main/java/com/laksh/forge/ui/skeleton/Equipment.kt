package com.laksh.forge.ui.skeleton

import com.laksh.forge.domain.exercise.CableAttach
import com.laksh.forge.domain.exercise.CableHandle
import com.laksh.forge.domain.exercise.DumbbellAxis
import com.laksh.forge.domain.exercise.MotionProp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Equipment materials; the renderer maps each to a theme colour. */
object Mat {
    const val BAR = 0
    const val PLATE = 1
    const val PAD = 2
    const val FRAME = 3
    const val CABLE = 4
    const val BOX = 5
    const val ROPE = 6
}

/**
 * A preallocated list of 3D primitives (tubes, discs, boxes). Rebuilt every frame from the
 * solved pose without allocating.
 */
class PrimBuffer(val capacity: Int = 160) {
    var count = 0
        private set
    val kind = IntArray(capacity)
    val mat = IntArray(capacity)
    val radius = FloatArray(capacity)
    /** Depth bias in metres: positive draws earlier (further back) than its centre suggests. */
    val bias = FloatArray(capacity)
    /** 12 floats per primitive; see [tube], [disc], [box] for the layout. */
    val p = FloatArray(capacity * 12)

    fun clear() { count = 0 }

    /** Capsule from a to b. Layout: a xyz, b xyz. */
    fun tube(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, r: Float, m: Int, depthBias: Float = 0f) {
        val i = next(TUBE, m, r, depthBias) ?: return
        p[i] = ax; p[i + 1] = ay; p[i + 2] = az; p[i + 3] = bx; p[i + 4] = by; p[i + 5] = bz
    }

    /** Short cylinder (plate, wheel). Layout: centre xyz, unit normal xyz, thickness. */
    fun disc(cx: Float, cy: Float, cz: Float, nx: Float, ny: Float, nz: Float, r: Float, thickness: Float, m: Int, depthBias: Float = 0f) {
        val i = next(DISC, m, r, depthBias) ?: return
        p[i] = cx; p[i + 1] = cy; p[i + 2] = cz; p[i + 3] = nx; p[i + 4] = ny; p[i + 5] = nz; p[i + 6] = thickness
    }

    /** Oriented box. Layout: centre xyz, then three half-axis vectors u, v, w. */
    fun box(
        cx: Float, cy: Float, cz: Float,
        ux: Float, uy: Float, uz: Float,
        vx: Float, vy: Float, vz: Float,
        wx: Float, wy: Float, wz: Float,
        m: Int, depthBias: Float = 0f
    ) {
        val i = next(BOX, m, 0f, depthBias) ?: return
        p[i] = cx; p[i + 1] = cy; p[i + 2] = cz
        p[i + 3] = ux; p[i + 4] = uy; p[i + 5] = uz
        p[i + 6] = vx; p[i + 7] = vy; p[i + 8] = vz
        p[i + 9] = wx; p[i + 10] = wy; p[i + 11] = wz
    }

    private fun next(k: Int, m: Int, r: Float, b: Float): Int? {
        if (count >= capacity) return null
        val i = count++
        kind[i] = k; mat[i] = m; radius[i] = r; bias[i] = b
        return i * 12
    }

    companion object {
        const val TUBE = 0
        const val DISC = 1
        const val BOX = 2
    }
}

/**
 * Builds the equipment for an exercise around the current pose. Static gear (benches, racks,
 * machine frames) is placed from the start-of-rep pose; moving gear (bars, dumbbells, sleds,
 * levers, cables) follows the hands and feet each frame.
 */
class EquipmentBuilder(private val solver: PoseSolver) {

    private val m = solver.motion
    // Array, not Set: iterating a Set allocates an iterator every frame.
    private val props: Array<MotionProp> = m.props.toTypedArray()
    private val t = FloatArray(24)

    fun build(buf: PrimBuffer) {
        buf.clear()
        for (i in props.indices) when (props[i]) {
            MotionProp.BARBELL -> barbell(buf, half = 1.1f, sleeve = 0.645f, plateR = 0.225f, plates = 2)
            MotionProp.EZ_BAR -> barbell(buf, half = 0.62f, sleeve = 0.42f, plateR = 0.15f, plates = 1)
            MotionProp.DUMBBELLS -> { dumbbell(buf, 0); dumbbell(buf, 1) }
            MotionProp.DUMBBELL_RIGHT -> dumbbell(buf, 1)
            MotionProp.DUMBBELL_GOBLET -> goblet(buf)
            MotionProp.BENCH_FLAT -> backPad(buf, fromPelvis = -0.34f, toHeadExtra = 0.08f, withSeat = false, posts = true)
            MotionProp.BENCH_INCLINE -> { backPad(buf, fromPelvis = 0.04f, toHeadExtra = 0.06f, withSeat = true, posts = true) }
            MotionProp.BENCH_UPRIGHT -> backPad(buf, fromPelvis = 0.04f, toHeadExtra = -0.12f, withSeat = true, posts = true)
            MotionProp.BENCH_PRONE_INCLINE -> pronePad(buf)
            MotionProp.BENCH_REAR_FOOT -> rearFootBench(buf)
            MotionProp.BENCH_THRUST -> thrustBench(buf)
            MotionProp.BENCH_DB_ROW -> dbRowBench(buf)
            MotionProp.BOX -> stepBox(buf)
            MotionProp.PULLUP_BAR -> pullupBar(buf)
            MotionProp.DIP_BARS -> dipBars(buf)
            MotionProp.CABLE -> cable(buf)
            MotionProp.LEG_PRESS -> legPress(buf)
            MotionProp.HACK_SQUAT -> hackSquat(buf)
            MotionProp.LEG_EXTENSION -> { backPad(buf, 0.04f, -0.2f, withSeat = true, posts = true); ankleLever(buf, front = true) }
            MotionProp.LEG_CURL_LYING -> { lyingBench(buf); ankleLever(buf, front = false) }
            MotionProp.LEG_CURL_SEATED -> { backPad(buf, 0.04f, -0.2f, withSeat = true, posts = true); thighRoller(buf, moving = false); ankleLever(buf, front = false) }
            MotionProp.CALF_STANDING -> calfStanding(buf)
            MotionProp.CALF_SEATED -> calfSeated(buf)
            MotionProp.ADDUCTION -> { backPad(buf, 0.04f, -0.2f, withSeat = true, posts = true); adductionPads(buf) }
            MotionProp.CHEST_PRESS -> { backPad(buf, 0.04f, 0.0f, withSeat = true, posts = true); handleArms(buf, pivotUp = 0.3f, pivotAnt = -0.28f) }
            MotionProp.PEC_DECK -> { backPad(buf, 0.04f, 0.0f, withSeat = true, posts = true); handleArms(buf, pivotUp = 0.32f, pivotAnt = -0.02f) }
            MotionProp.REAR_DELT -> { seatOnly(buf); chestPad(buf); handleArms(buf, pivotUp = 0.32f, pivotAnt = 0.3f) }
            MotionProp.LAT_PULLDOWN -> { seatOnly(buf); thighRoller(buf, moving = false) }
            MotionProp.SEATED_ROW -> seatedRow(buf)
            MotionProp.PREACHER -> preacher(buf)
            MotionProp.HYPEREXTENSION -> hyperextension(buf)
            MotionProp.NORDIC_PAD -> nordicPad(buf)
            MotionProp.AB_WHEEL -> abWheel(buf)
        }
    }

    /** Grows the solver's bounds to include the equipment over the whole rep. */
    fun includeInBounds(buf: PrimBuffer, samples: Int = 24) {
        for (i in 0..samples) {
            solver.solve(i / samples.toFloat())
            build(buf)
            for (k in 0 until buf.count) {
                val o = k * 12
                when (buf.kind[k]) {
                    PrimBuffer.TUBE -> {
                        solver.include(buf.p[o], buf.p[o + 1], buf.p[o + 2])
                        solver.include(buf.p[o + 3], buf.p[o + 4], buf.p[o + 5])
                    }
                    PrimBuffer.DISC -> {
                        val r = buf.radius[k]
                        solver.include(buf.p[o] - r, buf.p[o + 1] - r, buf.p[o + 2] - r)
                        solver.include(buf.p[o] + r, buf.p[o + 1] + r, buf.p[o + 2] + r)
                    }
                    else -> {
                        val ex = abs(buf.p[o + 3]) + abs(buf.p[o + 6]) + abs(buf.p[o + 9])
                        val ey = abs(buf.p[o + 4]) + abs(buf.p[o + 7]) + abs(buf.p[o + 10])
                        val ez = abs(buf.p[o + 5]) + abs(buf.p[o + 8]) + abs(buf.p[o + 11])
                        solver.include(buf.p[o] - ex, buf.p[o + 1] - ey, buf.p[o + 2] - ez)
                        solver.include(buf.p[o] + ex, buf.p[o + 1] + ey, buf.p[o + 2] + ez)
                    }
                }
            }
        }
        solver.solve(0f)
    }

    // ------------------------------------------------------------------ free weights

    private fun barbell(buf: PrimBuffer, half: Float, sleeve: Float, plateR: Float, plates: Int) {
        val c = solver.barCenter
        val a = solver.barAxis
        fun at(d: Float, i: Int) = c[i] + a[i] * d
        // Shaft in three pieces so depth sorting interleaves correctly with the hands.
        val cut = sleeve * 0.35f
        buf.tube(at(-sleeve, 0), at(-sleeve, 1), at(-sleeve, 2), at(-cut, 0), at(-cut, 1), at(-cut, 2), 0.014f, Mat.BAR)
        buf.tube(at(-cut, 0), at(-cut, 1), at(-cut, 2), at(cut, 0), at(cut, 1), at(cut, 2), 0.014f, Mat.BAR)
        buf.tube(at(cut, 0), at(cut, 1), at(cut, 2), at(sleeve, 0), at(sleeve, 1), at(sleeve, 2), 0.014f, Mat.BAR)
        for (sg in SIDES) {
            buf.tube(at(sg * sleeve, 0), at(sg * sleeve, 1), at(sg * sleeve, 2), at(sg * half, 0), at(sg * half, 1), at(sg * half, 2), 0.025f, Mat.BAR)
            buf.disc(at(sg * sleeve, 0), at(sg * sleeve, 1), at(sg * sleeve, 2), a[0], a[1], a[2], 0.04f, 0.025f, Mat.BAR)
            for (k in 0 until plates) {
                val d = sg * (sleeve + 0.055f + k * 0.055f)
                buf.disc(at(d, 0), at(d, 1), at(d, 2), a[0], a[1], a[2], plateR, 0.05f, Mat.PLATE)
            }
        }
    }

    private fun dumbbell(buf: PrimBuffer, s: Int) {
        val pos = solver.pos
        val w = Joint.wrist(s) * 3
        val e = Joint.elbow(s) * 3
        // Forearm direction; the handle lies across it.
        var fx = pos[w] - pos[e]; var fy = pos[w + 1] - pos[e + 1]; var fz = pos[w + 2] - pos[e + 2]
        val fl = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-6f); fx /= fl; fy /= fl; fz /= fl
        if (m.dumbbellAxis == DumbbellAxis.LATERAL) { t[0] = 1f; t[1] = 0f; t[2] = 0f } else {
            t[0] = solver.thorAnt[0]; t[1] = solver.thorAnt[1]; t[2] = solver.thorAnt[2]
        }
        orthonormal(t, 0, fx, fy, fz)
        val big = m.farmerHandles
        val hh = if (big) 0.07f else 0.06f
        val head = if (big) 0.17f else 0.135f
        val hr = if (big) 0.075f else 0.055f
        val cx = pos[w] + fx * 0.03f; val cy = pos[w + 1] + fy * 0.03f; val cz = pos[w + 2] + fz * 0.03f
        buf.tube(cx - t[0] * hh, cy - t[1] * hh, cz - t[2] * hh, cx + t[0] * hh, cy + t[1] * hh, cz + t[2] * hh, 0.016f, Mat.BAR)
        for (sg in SIDES) {
            buf.tube(
                cx + t[0] * hh * sg, cy + t[1] * hh * sg, cz + t[2] * hh * sg,
                cx + t[0] * head * sg, cy + t[1] * head * sg, cz + t[2] * head * sg,
                hr, Mat.PLATE
            )
        }
    }

    private fun goblet(buf: PrimBuffer) {
        val c = solver.barCenter
        val up = solver.thorUp; val ant = solver.thorAnt
        val cx = c[0] + ant[0] * 0.04f; val cy = c[1] + ant[1] * 0.04f; val cz = c[2] + ant[2] * 0.04f
        buf.tube(cx - up[0] * 0.06f, cy - up[1] * 0.06f, cz - up[2] * 0.06f, cx + up[0] * 0.06f, cy + up[1] * 0.06f, cz + up[2] * 0.06f, 0.016f, Mat.BAR)
        for (sg in SIDES) {
            buf.tube(
                cx + up[0] * 0.06f * sg, cy + up[1] * 0.06f * sg, cz + up[2] * 0.06f * sg,
                cx + up[0] * 0.13f * sg, cy + up[1] * 0.13f * sg, cz + up[2] * 0.13f * sg,
                0.065f, Mat.PLATE
            )
        }
    }

    // ------------------------------------------------------------------ benches and boxes

    /**
     * A pad along the back of the start-of-rep trunk, from [fromPelvis] metres along the trunk
     * (negative = past the pelvis towards the feet) up to the head plus [toHeadExtra]; with an
     * optional horizontal seat under the pelvis.
     */
    private fun backPad(buf: PrimBuffer, fromPelvis: Float, toHeadExtra: Float, withSeat: Boolean, posts: Boolean) {
        val rp = solver.refPos
        val up = solver.refThorUp
        val ant = solver.refThorAnt
        val pv = Joint.PELVIS * 3
        val hd = Joint.HEAD * 3
        val sx = rp[pv] + up[0] * fromPelvis; val sy = rp[pv + 1] + up[1] * fromPelvis; val sz = rp[pv + 2] + up[2] * fromPelvis
        val alongHead = (rp[hd] - rp[pv]) * up[0] + (rp[hd + 1] - rp[pv + 1]) * up[1] + (rp[hd + 2] - rp[pv + 2]) * up[2]
        val endD = alongHead + toHeadExtra
        val ex = rp[pv] + up[0] * endD; val ey = rp[pv + 1] + up[1] * endD; val ez = rp[pv + 2] + up[2] * endD
        val half = (endD - fromPelvis) * 0.5f
        val off = BODY_BACK + PAD_HALF
        val cx = (sx + ex) * 0.5f - ant[0] * off
        val cy = (sy + ey) * 0.5f - ant[1] * off
        val cz = (sz + ez) * 0.5f - ant[2] * off
        buf.box(cx, cy, cz, up[0] * half, up[1] * half, up[2] * half, 0.14f, 0f, 0f, -ant[0] * PAD_HALF, -ant[1] * PAD_HALF, -ant[2] * PAD_HALF, Mat.PAD, UNDER_BIAS)
        if (posts) {
            // Legs: drop from both ends of the pad (and from the seat) to the floor.
            for (k in 0..1) {
                val d = if (k == 0) -half * 0.8f else half * 0.8f
                val px = cx + up[0] * d - ant[0] * PAD_HALF
                val py = cy + up[1] * d - ant[1] * PAD_HALF
                val pz = cz + up[2] * d - ant[2] * PAD_HALF
                if (py > 0.12f) {
                    buf.tube(px, py, pz, px, 0.02f, pz, 0.026f, Mat.FRAME, UNDER_BIAS)
                    buf.tube(px - 0.2f, 0.02f, pz, px + 0.2f, 0.02f, pz, 0.022f, Mat.FRAME, UNDER_BIAS)
                }
            }
        }
        if (withSeat) seatOnly(buf)
    }

    /** Horizontal seat under the start-of-rep pelvis, with a post. */
    private fun seatOnly(buf: PrimBuffer) {
        val rp = solver.refPos
        val pv = Joint.PELVIS * 3
        seatForward(t)
        val top = rp[pv + 1] - SEAT_DROP
        val cx = rp[pv] + t[0] * 0.1f; val cz = rp[pv + 2] + t[2] * 0.1f
        buf.box(cx, top - PAD_HALF, cz, t[0] * 0.2f, 0f, t[2] * 0.2f, 0.16f, 0f, 0f, 0f, PAD_HALF, 0f, Mat.PAD, UNDER_BIAS)
        if (top > 0.1f) {
            buf.tube(cx, top - PAD_HALF * 2f, cz, cx, 0.02f, cz, 0.035f, Mat.FRAME, UNDER_BIAS)
            buf.tube(cx - 0.22f, 0.02f, cz, cx + 0.22f, 0.02f, cz, 0.025f, Mat.FRAME, UNDER_BIAS)
        }
    }

    private fun pronePad(buf: PrimBuffer) {
        val rp = solver.refPos
        val up = solver.refThorUp
        val ant = solver.refThorAnt
        val pv = Joint.PELVIS * 3; val nk = Joint.NECK * 3
        val off = 0.12f + PAD_HALF
        val sx = rp[pv] + up[0] * 0.05f; val sy = rp[pv + 1] + up[1] * 0.05f; val sz = rp[pv + 2] + up[2] * 0.05f
        val cx = (sx + rp[nk]) * 0.5f + ant[0] * off
        val cy = (sy + rp[nk + 1]) * 0.5f + ant[1] * off
        val cz = (sz + rp[nk + 2]) * 0.5f + ant[2] * off
        val half = sqrt(sq(rp[nk] - sx) + sq(rp[nk + 1] - sy) + sq(rp[nk + 2] - sz)) * 0.5f
        buf.box(cx, cy, cz, up[0] * half, up[1] * half, up[2] * half, 0.14f, 0f, 0f, ant[0] * PAD_HALF, ant[1] * PAD_HALF, ant[2] * PAD_HALF, Mat.PAD, -0.02f)
        // Support legs from the low end of the pad and from its middle.
        val lx = cx - up[0] * half; val ly = cy - up[1] * half; val lz = cz - up[2] * half
        buf.tube(lx, ly, lz, lx, 0.02f, lz, 0.026f, Mat.FRAME, UNDER_BIAS)
        buf.tube(cx + ant[0] * PAD_HALF, cy + ant[1] * PAD_HALF, cz + ant[2] * PAD_HALF, cx + up[0] * 0.1f, 0.02f, cz + 0.2f, 0.026f, Mat.FRAME, UNDER_BIAS)
    }

    private fun rearFootBench(buf: PrimBuffer) {
        // Rear foot is the non-lead (left) leg; its laces rest on the bench.
        val rp = solver.refPos
        val a = Joint.ANKLE_L * 3
        val top = (rp[a + 1] - 0.06f).coerceAtLeast(0.2f)
        buf.box(rp[a], top * 0.5f, rp[a + 2] - 0.04f, 0.55f, 0f, 0f, 0f, top * 0.5f, 0f, 0f, 0f, 0.16f, Mat.PAD, UNDER_BIAS)
    }

    private fun thrustBench(buf: PrimBuffer) {
        val rp = solver.refPos
        val up = solver.refThorUp; val ant = solver.refThorAnt
        val c = Joint.CHEST * 3
        val ex = rp[c] - ant[0] * BODY_BACK; val ey = rp[c + 1] - ant[1] * BODY_BACK; val ez = rp[c + 2] - ant[2] * BODY_BACK
        var hx = up[0]; var hz = up[2]
        val hl = sqrt(hx * hx + hz * hz).coerceAtLeast(1e-4f); hx /= hl; hz /= hl
        val top = ey.coerceAtLeast(0.2f)
        buf.box(ex + hx * 0.2f, top * 0.5f, ez + hz * 0.2f, 0.6f, 0f, 0f, 0f, top * 0.5f, 0f, hx * 0.2f, 0f, hz * 0.2f, Mat.PAD, UNDER_BIAS)
    }

    private fun dbRowBench(buf: PrimBuffer) {
        val rp = solver.refPos
        val k = Joint.KNEE_L * 3; val w = Joint.WRIST_L * 3
        val top = min(rp[k + 1], rp[w + 1]) - 0.045f
        val z0 = min(rp[k + 2], rp[w + 2]) - 0.25f
        val z1 = max(rp[k + 2], rp[w + 2]) + 0.2f
        val cx = (rp[k] + rp[w]) * 0.5f
        val cz = (z0 + z1) * 0.5f
        buf.box(cx, top - PAD_HALF, cz, 0.15f, 0f, 0f, 0f, PAD_HALF, 0f, 0f, 0f, (z1 - z0) * 0.5f, Mat.PAD, UNDER_BIAS)
        for (k in 0..1) {
            val z = if (k == 0) z0 + 0.12f else z1 - 0.12f
            buf.tube(cx, top - PAD_HALF * 2f, z, cx, 0.02f, z, 0.026f, Mat.FRAME, UNDER_BIAS)
            buf.tube(cx - 0.2f, 0.02f, z, cx + 0.2f, 0.02f, z, 0.022f, Mat.FRAME, UNDER_BIAS)
        }
    }

    private fun stepBox(buf: PrimBuffer) {
        val rp = solver.refPos
        val a = Joint.ANKLE_R * 3
        val h = m.boxHeight ?: 0.45f
        buf.box(rp[a], h * 0.5f, rp[a + 2] + 0.03f, 0.24f, 0f, 0f, 0f, h * 0.5f, 0f, 0f, 0f, 0.21f, Mat.BOX, UNDER_BIAS)
    }

    private fun lyingBench(buf: PrimBuffer) {
        val rp = solver.refPos
        val ant = solver.refThorAnt; val up = solver.refThorUp
        val pv = Joint.PELVIS * 3; val nk = Joint.NECK * 3
        // Torso pad (under the chest: anterior points at the floor when prone).
        val off = BODY_BACK + PAD_HALF
        val tx = (rp[pv] + rp[nk]) * 0.5f + ant[0] * off
        val ty = (rp[pv + 1] + rp[nk + 1]) * 0.5f + ant[1] * off
        val tz = (rp[pv + 2] + rp[nk + 2]) * 0.5f + ant[2] * off
        val th = V.dist(rp, Joint.PELVIS, Joint.NECK) * 0.5f + 0.08f
        buf.box(tx, ty, tz, up[0] * th, up[1] * th, up[2] * th, 0.15f, 0f, 0f, ant[0] * PAD_HALF, ant[1] * PAD_HALF, ant[2] * PAD_HALF, Mat.PAD, UNDER_BIAS)
        // Thigh pad, angled with the thighs.
        midOf(rp, Joint.HIP_L, Joint.HIP_R, t, 0); midOf(rp, Joint.KNEE_L, Joint.KNEE_R, t, 3)
        var dx = t[3] - t[0]; var dy = t[4] - t[1]; var dz = t[5] - t[2]
        val dl = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f); dx /= dl; dy /= dl; dz /= dl
        t[6] = ant[0]; t[7] = ant[1]; t[8] = ant[2]
        orthonormal(t, 6, dx, dy, dz)
        val hl = dl * 0.5f
        buf.box(
            (t[0] + t[3]) * 0.5f + t[6] * 0.1f, (t[1] + t[4]) * 0.5f + t[7] * 0.1f, (t[2] + t[5]) * 0.5f + t[8] * 0.1f,
            dx * hl, dy * hl, dz * hl, 0.15f, 0f, 0f, t[6] * PAD_HALF, t[7] * PAD_HALF, t[8] * PAD_HALF, Mat.PAD, UNDER_BIAS
        )
        for (k in 0..1) {
            val z = if (k == 0) tz - th * 0.6f * up[2] else t[5]
            buf.tube(0f, ty - PAD_HALF, z, 0f, 0.02f, z, 0.03f, Mat.FRAME, UNDER_BIAS)
            buf.tube(-0.22f, 0.02f, z, 0.22f, 0.02f, z, 0.022f, Mat.FRAME, UNDER_BIAS)
        }
    }

    private fun pullupBar(buf: PrimBuffer) {
        val y = (solver.fixedHands[1] + solver.fixedHands[4]) * 0.5f
        val z = (solver.fixedHands[2] + solver.fixedHands[5]) * 0.5f
        val x = (solver.fixedHands[0] + solver.fixedHands[3]) * 0.5f
        buf.tube(x - 0.8f, y, z, x + 0.8f, y, z, 0.017f, Mat.BAR)
        for (sg in SIDES) {
            buf.tube(x + sg * 0.8f, y + 0.06f, z, x + sg * 0.8f, 0.02f, z, 0.032f, Mat.FRAME, 0.1f)
            buf.tube(x + sg * 0.8f, 0.02f, z - 0.35f, x + sg * 0.8f, 0.02f, z + 0.35f, 0.026f, Mat.FRAME, 0.1f)
        }
    }

    private fun dipBars(buf: PrimBuffer) {
        val fh = solver.fixedHands
        for (s in 0..1) {
            val x = fh[s * 3]; val y = fh[s * 3 + 1]; val z = fh[s * 3 + 2]
            buf.tube(x, y, z - 0.3f, x, y, z + 0.28f, 0.02f, Mat.BAR)
            buf.tube(x, y, z - 0.3f, x, 0.02f, z - 0.3f, 0.026f, Mat.FRAME, 0.1f)
            buf.tube(x, y, z + 0.28f, x, 0.02f, z + 0.28f, 0.026f, Mat.FRAME, 0.1f)
            buf.tube(x, 0.02f, z - 0.3f, x, 0.02f, z + 0.28f, 0.024f, Mat.FRAME, 0.1f)
        }
    }

    // ------------------------------------------------------------------ cables

    private fun cable(buf: PrimBuffer) {
        val spec = m.cable ?: return
        val pos = solver.pos
        for (i in spec.pulleys.indices) {
            val p = spec.pulleys[i]
            val px = p[0]; val py = p[1]; val pz = p[2]
            // Tower stands just behind the pulley, away from the lifter.
            var ax = px; var az = pz
            val al = sqrt(ax * ax + az * az)
            if (al > 1e-3f) { ax /= al; az /= al } else { ax = 0f; az = 1f }
            val tx = px + ax * 0.09f; val tz = pz + az * 0.09f
            val topY = max(py + 0.2f, 2.25f)
            // Perpendicular (in the floor plane) to the lifter-pulley line.
            val qx = -az; val qz = ax
            for (sg in SIDES) {
                buf.tube(tx + qx * 0.09f * sg, 0.02f, tz + qz * 0.09f * sg, tx + qx * 0.09f * sg, topY, tz + qz * 0.09f * sg, 0.028f, Mat.FRAME, 0.15f)
            }
            buf.tube(tx - qx * 0.09f, topY, tz - qz * 0.09f, tx + qx * 0.09f, topY, tz + qz * 0.09f, 0.028f, Mat.FRAME, 0.15f)
            buf.box(tx + ax * 0.02f, 0.42f, tz + az * 0.02f, qx * 0.08f, 0f, qz * 0.08f, 0f, 0.36f, 0f, ax * 0.06f, 0f, az * 0.06f, Mat.PLATE, 0.15f)
            buf.disc(px, py, pz, qx, 0f, qz, 0.045f, 0.025f, Mat.BAR, 0.05f)

            // Attachment point on the lifter's side of the cable.
            val target = when (spec.attach) {
                CableAttach.MID -> -1
                CableAttach.EACH -> i
                CableAttach.RIGHT -> 1
            }
            if (target >= 0) {
                val w = Joint.wrist(target) * 3
                buf.tube(px, py, pz, pos[w], pos[w + 1], pos[w + 2], 0.005f, Mat.CABLE)
                handleAt(buf, spec.handle, target, px, py, pz)
            } else {
                val c = solver.barCenter
                // Knot sits a little up the cable from the hands.
                var dx = px - c[0]; var dy = py - c[1]; var dz = pz - c[2]
                val dl = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f); dx /= dl; dy /= dl; dz /= dl
                val knot = when (spec.handle) { CableHandle.ROPE, CableHandle.V -> 0.13f; else -> 0f }
                val kx = c[0] + dx * knot; val ky = c[1] + dy * knot; val kz = c[2] + dz * knot
                buf.tube(px, py, pz, kx, ky, kz, 0.005f, Mat.CABLE)
                handleMid(buf, spec.handle, kx, ky, kz)
            }
        }
    }

    private fun handleMid(buf: PrimBuffer, handle: CableHandle, kx: Float, ky: Float, kz: Float) {
        val pos = solver.pos
        val l = Joint.WRIST_L * 3; val r = Joint.WRIST_R * 3
        val a = solver.barAxis; val c = solver.barCenter
        when (handle) {
            CableHandle.ROPE -> {
                buf.tube(kx, ky, kz, pos[l], pos[l + 1], pos[l + 2], 0.014f, Mat.ROPE)
                buf.tube(kx, ky, kz, pos[r], pos[r + 1], pos[r + 2], 0.014f, Mat.ROPE)
            }
            CableHandle.V -> {
                buf.tube(kx, ky, kz, pos[l], pos[l + 1], pos[l + 2], 0.012f, Mat.BAR)
                buf.tube(kx, ky, kz, pos[r], pos[r + 1], pos[r + 2], 0.012f, Mat.BAR)
            }
            CableHandle.BAR, CableHandle.SINGLE -> {
                val h = max(0.26f, solver.gripHalf + 0.06f)
                buf.tube(c[0] - a[0] * h, c[1] - a[1] * h, c[2] - a[2] * h, c[0] + a[0] * h, c[1] + a[1] * h, c[2] + a[2] * h, 0.013f, Mat.BAR)
            }
            CableHandle.LAT_BAR -> {
                val h = 0.6f
                val ux = solver.thorUp[0]; val uy = solver.thorUp[1]; val uz = solver.thorUp[2]
                buf.tube(c[0] - a[0] * h, c[1] - a[1] * h, c[2] - a[2] * h, c[0] + a[0] * h, c[1] + a[1] * h, c[2] + a[2] * h, 0.013f, Mat.BAR)
                for (sg in SIDES) {
                    val ex = c[0] + a[0] * h * sg; val ey = c[1] + a[1] * h * sg; val ez = c[2] + a[2] * h * sg
                    buf.tube(ex, ey, ez, ex - ux * 0.08f + a[0] * 0.05f * sg, ey - uy * 0.08f, ez - uz * 0.08f, 0.013f, Mat.BAR)
                }
            }
        }
    }

    private fun handleAt(buf: PrimBuffer, handle: CableHandle, s: Int, px: Float, py: Float, pz: Float) {
        val pos = solver.pos
        val w = Joint.wrist(s) * 3; val e = Joint.elbow(s) * 3
        var fx = pos[w] - pos[e]; var fy = pos[w + 1] - pos[e + 1]; var fz = pos[w + 2] - pos[e + 2]
        val fl = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-6f); fx /= fl; fy /= fl; fz /= fl
        // D-handle across the palm, perpendicular to both the forearm and the cable.
        var cx = px - pos[w]; var cy = py - pos[w + 1]; var cz = pz - pos[w + 2]
        val cl = sqrt(cx * cx + cy * cy + cz * cz).coerceAtLeast(1e-6f); cx /= cl; cy /= cl; cz /= cl
        var hx = fy * cz - fz * cy; var hy = fz * cx - fx * cz; var hz = fx * cy - fy * cx
        val hl = sqrt(hx * hx + hy * hy + hz * hz)
        if (hl < 1e-3f) { hx = 1f; hy = 0f; hz = 0f } else { hx /= hl; hy /= hl; hz /= hl }
        val r = if (handle == CableHandle.SINGLE) 0.055f else 0.12f
        buf.tube(pos[w] - hx * r, pos[w + 1] - hy * r, pos[w + 2] - hz * r, pos[w] + hx * r, pos[w + 1] + hy * r, pos[w + 2] + hz * r, 0.013f, Mat.BAR)
    }

    // ------------------------------------------------------------------ machines

    private fun legPress(buf: PrimBuffer) {
        backPad(buf, 0.02f, -0.1f, withSeat = true, posts = true)
        footPlate(buf, solver.soles, moving = true)
        // Rails at 45 degrees through the starting foot position, under the sled.
        val rs = solver.refSoles
        val fx = (rs[0] + rs[3] + rs[6] + rs[9]) * 0.25f
        val fy = (rs[1] + rs[4] + rs[7] + rs[10]) * 0.25f
        val fz = (rs[2] + rs[5] + rs[8] + rs[11]) * 0.25f
        val c = 0.7071f
        for (sg in SIDES) {
            val x = fx + sg * 0.32f
            buf.tube(x, fy - c * 1.0f - 0.08f, fz - c * 1.0f + 0.08f, x, fy + c * 0.55f - 0.08f, fz + c * 0.55f + 0.08f, 0.026f, Mat.FRAME, 0.1f)
        }
    }

    private fun hackSquat(buf: PrimBuffer) {
        val pos = solver.pos
        val up = solver.thorUp; val ant = solver.thorAnt
        val pv = Joint.PELVIS * 3; val nk = Joint.NECK * 3
        val off = BODY_BACK + PAD_HALF
        val half = Anthro.TRUNK * 0.55f
        val cx = (pos[pv] + pos[nk]) * 0.5f - ant[0] * off
        val cy = (pos[pv + 1] + pos[nk + 1]) * 0.5f - ant[1] * off
        val cz = (pos[pv + 2] + pos[nk + 2]) * 0.5f - ant[2] * off
        buf.box(cx, cy, cz, up[0] * half, up[1] * half, up[2] * half, 0.15f, 0f, 0f, -ant[0] * PAD_HALF, -ant[1] * PAD_HALF, -ant[2] * PAD_HALF, Mat.PAD, UNDER_BIAS)
        for (s in 0..1) {
            val sh = Joint.shoulder(s) * 3
            buf.box(pos[sh] + up[0] * 0.07f, pos[sh + 1] + up[1] * 0.07f, pos[sh + 2] + up[2] * 0.07f, 0.06f, 0f, 0f, up[0] * 0.035f, up[1] * 0.035f, up[2] * 0.035f, ant[0] * 0.08f, ant[1] * 0.08f, ant[2] * 0.08f, Mat.PAD)
        }
        // Rails parallel to the back pad, fixed in the world.
        val rp = solver.refPos; val ru = solver.refThorUp; val ra = solver.refThorAnt
        val bx = rp[pv] - ra[0] * 0.24f; val by = rp[pv + 1] - ra[1] * 0.24f; val bz = rp[pv + 2] - ra[2] * 0.24f
        val down = if (ru[1] > 0.1f) (by - 0.03f) / ru[1] else 1f
        for (sg in SIDES) {
            buf.tube(bx + sg * 0.28f - ru[0] * down, by - ru[1] * down, bz - ru[2] * down, bx + sg * 0.28f + ru[0] * 1.1f, by + ru[1] * 1.1f, bz + ru[2] * 1.1f, 0.028f, Mat.FRAME, 0.2f)
        }
        footPlate(buf, solver.refSoles, moving = false)
    }

    private fun footPlate(buf: PrimBuffer, soles: FloatArray, moving: Boolean) {
        val cx = (soles[0] + soles[3] + soles[6] + soles[9]) * 0.25f
        val cy = (soles[1] + soles[4] + soles[7] + soles[10]) * 0.25f
        val cz = (soles[2] + soles[5] + soles[8] + soles[11]) * 0.25f
        // Foot direction (heel to ball), averaged over both feet.
        var dx = (soles[0] - soles[3] + soles[6] - soles[9]) * 0.5f
        var dy = (soles[1] - soles[4] + soles[7] - soles[10]) * 0.5f
        var dz = (soles[2] - soles[5] + soles[8] - soles[11]) * 0.5f
        val dl = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f); dx /= dl; dy /= dl; dz /= dl
        // Plate normal: perpendicular to the foot and to the lateral axis, pointing away from the sole.
        var nx = 0f; var ny = dz; var nz = -dy
        val nl = sqrt(ny * ny + nz * nz).coerceAtLeast(1e-4f); ny /= nl; nz /= nl
        if (ny > 0f) { ny = -ny; nz = -nz }
        val t2 = 0.02f
        buf.box(cx + nx * t2, cy + ny * t2, cz + nz * t2, 0.3f, 0f, 0f, dx * 0.22f, dy * 0.22f, dz * 0.22f, nx * t2, ny * t2, nz * t2, Mat.FRAME, if (moving) 0.05f else UNDER_BIAS)
    }

    /** Roller pad on the front (extension) or back (curl) of the lower shins, on a lever from the knee. */
    private fun ankleLever(buf: PrimBuffer, front: Boolean) {
        val pos = solver.pos
        val sgn = if (front) 1f else -1f
        var sx = 0f; var sy = 0f; var sz = 0f
        for (s in 0..1) {
            val a = Joint.ankle(s) * 3; val k = Joint.knee(s) * 3; val tn = Joint.toe(s) * 3
            var dx = pos[a] - pos[k]; var dy = pos[a + 1] - pos[k + 1]; var dz = pos[a + 2] - pos[k + 2]
            val dl = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f); dx /= dl; dy /= dl; dz /= dl
            t[0] = pos[tn] - pos[a]; t[1] = pos[tn + 1] - pos[a + 1]; t[2] = pos[tn + 2] - pos[a + 2]
            orthonormal(t, 0, dx, dy, dz)
            sx += pos[a] - dx * 0.05f + t[0] * 0.065f * sgn
            sy += pos[a + 1] - dy * 0.05f + t[1] * 0.065f * sgn
            sz += pos[a + 2] - dz * 0.05f + t[2] * 0.065f * sgn
        }
        sx *= 0.5f; sy *= 0.5f; sz *= 0.5f
        buf.tube(sx - 0.2f, sy, sz, sx + 0.2f, sy, sz, 0.042f, Mat.PAD)
        // Lever from a pivot beside the right knee down to the roller.
        val k = Joint.KNEE_R * 3
        val rk = solver.refPos
        val pxv = rk[k] - 0.16f
        buf.tube(pxv, rk[k + 1], rk[k + 2], sx - 0.2f, sy, sz, 0.022f, Mat.FRAME)
        buf.disc(pxv, rk[k + 1], rk[k + 2], 1f, 0f, 0f, 0.05f, 0.03f, Mat.FRAME)
    }

    private fun thighRoller(buf: PrimBuffer, moving: Boolean) {
        val p = if (moving) solver.pos else solver.refPos
        midOf(p, Joint.KNEE_L, Joint.KNEE_R, t, 0)
        midOf(p, Joint.HIP_L, Joint.HIP_R, t, 3)
        var dx = t[0] - t[3]; var dy = t[1] - t[4]; var dz = t[2] - t[5]
        val dl = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f); dx /= dl; dy /= dl; dz /= dl
        val cx = t[0] - dx * 0.07f; val cy = t[1] - dy * 0.07f + 0.085f; val cz = t[2] - dz * 0.07f
        buf.tube(cx - 0.24f, cy, cz, cx + 0.24f, cy, cz, 0.045f, Mat.PAD, -0.05f)
        buf.tube(cx + 0.24f, cy, cz, cx + 0.3f, cy - 0.35f, cz, 0.022f, Mat.FRAME)
    }

    private fun calfStanding(buf: PrimBuffer) {
        val pos = solver.pos
        val up = solver.thorUp; val ant = solver.thorAnt
        for (s in 0..1) {
            val sh = Joint.shoulder(s) * 3
            buf.box(pos[sh] + up[0] * 0.07f, pos[sh + 1] + up[1] * 0.07f, pos[sh + 2] + up[2] * 0.07f, 0.065f, 0f, 0f, 0f, 0.035f, 0f, ant[0] * 0.09f, 0f, ant[2] * 0.09f, Mat.PAD)
        }
        val l = Joint.SHOULDER_L * 3; val r = Joint.SHOULDER_R * 3
        val y = (pos[l + 1] + pos[r + 1]) * 0.5f + 0.1f
        val z = (pos[l + 2] + pos[r + 2]) * 0.5f - 0.12f
        buf.tube(pos[l] + 0.3f, y, z, pos[r] - 0.3f, y, z, 0.026f, Mat.FRAME)
        val ry = solver.refPos[Joint.SHOULDER_L * 3 + 1] + 0.35f
        for (sg in SIDES) {
            buf.tube(sg * 0.5f, 0.02f, z, sg * 0.5f, ry, z, 0.03f, Mat.FRAME, 0.2f)
        }
        block(buf)
    }

    private fun calfSeated(buf: PrimBuffer) {
        seatOnly(buf)
        thighRoller(buf, moving = true)
        block(buf)
    }

    private fun block(buf: PrimBuffer) {
        val h = m.blockHeight ?: 0.08f
        val rs = solver.refSoles
        val z = (rs[2] + rs[8]) * 0.5f
        buf.box(0f, h * 0.5f, z + 0.02f, 0.4f, 0f, 0f, 0f, h * 0.5f, 0f, 0f, 0f, 0.09f, Mat.BOX, UNDER_BIAS)
    }

    private fun adductionPads(buf: PrimBuffer) {
        val pos = solver.pos
        val rp = solver.refPos
        val seatY = rp[Joint.PELVIS * 3 + 1] - SEAT_DROP
        for (s in 0..1) {
            val sg = Joint.sign(s)
            val k = Joint.knee(s) * 3
            val cx = pos[k] - sg * 0.07f
            buf.box(cx, pos[k + 1], pos[k + 2] - 0.02f, 0.025f, 0f, 0f, 0f, 0.08f, 0f, 0f, 0f, 0.09f, Mat.PAD)
            buf.tube(cx, pos[k + 1] - 0.08f, pos[k + 2] - 0.02f, sg * 0.05f, seatY - 0.2f, pos[k + 2] - 0.15f, 0.02f, Mat.FRAME)
        }
    }

    /** Handles at the hands on levers to a pivot above/behind (presses) or in front (rear delts). */
    private fun handleArms(buf: PrimBuffer, pivotUp: Float, pivotAnt: Float) {
        val pos = solver.pos
        val rp = solver.refPos
        val up = solver.refThorUp; val ant = solver.refThorAnt
        for (s in 0..1) {
            val sg = Joint.sign(s)
            val w = Joint.wrist(s) * 3
            buf.tube(pos[w], pos[w + 1] - 0.07f, pos[w + 2], pos[w], pos[w + 1] + 0.07f, pos[w + 2], 0.017f, Mat.BAR)
            val sh = Joint.shoulder(s) * 3
            val px = rp[sh] + sg * 0.12f + up[0] * pivotUp + ant[0] * pivotAnt
            val py = rp[sh + 1] + up[1] * pivotUp + ant[1] * pivotAnt
            val pz = rp[sh + 2] + up[2] * pivotUp + ant[2] * pivotAnt
            buf.tube(px, py, pz, pos[w], pos[w + 1] + 0.07f, pos[w + 2], 0.022f, Mat.FRAME)
            buf.disc(px, py, pz, 0f, 1f, 0f, 0.045f, 0.03f, Mat.FRAME)
            buf.tube(px, py, pz, px, 0.02f, pz + if (pivotAnt < 0f) -0.1f else 0.1f, 0.028f, Mat.FRAME, 0.25f)
        }
    }

    private fun chestPad(buf: PrimBuffer) {
        val rp = solver.refPos
        val up = solver.refThorUp; val ant = solver.refThorAnt
        val c = Joint.CHEST * 3
        val off = 0.13f + PAD_HALF
        buf.box(rp[c] + ant[0] * off, rp[c + 1] + ant[1] * off, rp[c + 2] + ant[2] * off, up[0] * 0.2f, up[1] * 0.2f, up[2] * 0.2f, 0.14f, 0f, 0f, ant[0] * PAD_HALF, ant[1] * PAD_HALF, ant[2] * PAD_HALF, Mat.PAD, -0.02f)
        val bx = rp[c] + ant[0] * (off + 0.05f)
        val bz = rp[c + 2] + ant[2] * (off + 0.05f)
        buf.tube(bx, rp[c + 1] - 0.15f, bz, bx, 0.02f, bz + 0.1f, 0.03f, Mat.FRAME, 0.1f)
    }

    private fun seatedRow(buf: PrimBuffer) {
        val rp = solver.refPos
        val pv = Joint.PELVIS * 3
        val top = rp[pv + 1] - SEAT_DROP
        val rs = solver.refSoles
        val fz = (rs[2] + rs[5] + rs[8] + rs[11]) * 0.25f
        val z0 = rp[pv + 2] - 0.3f
        buf.box(0f, top - PAD_HALF, (z0 + fz) * 0.5f, 0.14f, 0f, 0f, 0f, PAD_HALF, 0f, 0f, 0f, (fz - z0) * 0.5f, Mat.PAD, UNDER_BIAS)
        buf.tube(0f, top - PAD_HALF * 2f, z0 + 0.1f, 0f, 0.02f, z0 + 0.1f, 0.03f, Mat.FRAME, UNDER_BIAS)
        buf.tube(0f, top - PAD_HALF * 2f, fz - 0.15f, 0f, 0.02f, fz - 0.15f, 0.03f, Mat.FRAME, UNDER_BIAS)
        footPlate(buf, rs, moving = false)
    }

    private fun preacher(buf: PrimBuffer) {
        seatOnly(buf)
        val rp = solver.refPos
        midOf(rp, Joint.SHOULDER_L, Joint.SHOULDER_R, t, 0)
        midOf(rp, Joint.ELBOW_L, Joint.ELBOW_R, t, 3)
        var dx = t[3] - t[0]; var dy = t[4] - t[1]; var dz = t[5] - t[2]
        val dl = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f); dx /= dl; dy /= dl; dz /= dl
        // Pad sits under the backs of the upper arms: offset towards world-down, perpendicular to the arm.
        t[6] = 0f; t[7] = -1f; t[8] = 0f
        orthonormal(t, 6, dx, dy, dz)
        val off = 0.05f + PAD_HALF
        val cx = (t[0] + t[3]) * 0.5f + t[6] * off; val cy = (t[1] + t[4]) * 0.5f + t[7] * off; val cz = (t[2] + t[5]) * 0.5f + t[8] * off
        val half = dl * 0.55f
        buf.box(cx, cy, cz, dx * half, dy * half, dz * half, 0.22f, 0f, 0f, t[6] * PAD_HALF, t[7] * PAD_HALF, t[8] * PAD_HALF, Mat.PAD, UNDER_BIAS)
        buf.tube(cx, cy - 0.05f, cz - 0.05f, cx, 0.02f, cz - 0.1f, 0.035f, Mat.FRAME, UNDER_BIAS)
    }

    private fun hyperextension(buf: PrimBuffer) {
        val rp = solver.refPos
        midOf(rp, Joint.HIP_L, Joint.HIP_R, t, 0)
        midOf(rp, Joint.KNEE_L, Joint.KNEE_R, t, 3)
        var dx = t[3] - t[0]; var dy = t[4] - t[1]; var dz = t[5] - t[2]
        val dl = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f); dx /= dl; dy /= dl; dz /= dl
        t[6] = solver.refPelAnt[0]; t[7] = solver.refPelAnt[1]; t[8] = solver.refPelAnt[2]
        orthonormal(t, 6, dx, dy, dz)
        val off = 0.08f + PAD_HALF
        val cx = t[0] + dx * 0.16f + t[6] * off; val cy = t[1] + dy * 0.16f + t[7] * off; val cz = t[2] + dz * 0.16f + t[8] * off
        buf.box(cx, cy, cz, dx * 0.12f, dy * 0.12f, dz * 0.12f, 0.2f, 0f, 0f, t[6] * PAD_HALF, t[7] * PAD_HALF, t[8] * PAD_HALF, Mat.PAD, -0.02f)
        footPlate(buf, solver.refSoles, moving = false)
        // Heel roller behind the ankles and a frame from the platform to the pad.
        midOf(rp, Joint.HEEL_L, Joint.HEEL_R, t, 9)
        buf.tube(t[9] - 0.16f, t[10] + 0.06f, t[11] - 0.04f, t[9] + 0.16f, t[10] + 0.06f, t[11] - 0.04f, 0.035f, Mat.PAD)
        buf.tube(t[9], 0.06f, t[11] - 0.1f, cx, cy, cz, 0.03f, Mat.FRAME, 0.2f)
        buf.tube(t[9] - 0.25f, 0.02f, t[11] - 0.1f, t[9] + 0.25f, 0.02f, t[11] - 0.1f, 0.025f, Mat.FRAME, 0.2f)
    }

    private fun nordicPad(buf: PrimBuffer) {
        val rp = solver.refPos
        midOf(rp, Joint.KNEE_L, Joint.KNEE_R, t, 0)
        midOf(rp, Joint.ANKLE_L, Joint.ANKLE_R, t, 3)
        val z0 = min(t[2], t[5]) - 0.1f; val z1 = max(t[2], t[5]) + 0.1f
        buf.box(t[0], 0.025f, (z0 + z1) * 0.5f, 0.25f, 0f, 0f, 0f, 0.025f, 0f, 0f, 0f, (z1 - z0) * 0.5f, Mat.PAD, UNDER_BIAS)
        // Roller pinning the heels.
        val hz = t[5] - 0.04f
        buf.tube(t[3] - 0.2f, t[4] + 0.07f, hz, t[3] + 0.2f, t[4] + 0.07f, hz, 0.04f, Mat.PAD)
        for (sg in SIDES) buf.tube(t[3] + sg * 0.22f, t[4] + 0.07f, hz, t[3] + sg * 0.22f, 0.02f, hz, 0.022f, Mat.FRAME, 0.1f)
    }

    private fun abWheel(buf: PrimBuffer) {
        val c = solver.barCenter
        val h = solver.gripHalf + 0.04f
        buf.tube(c[0] - h, c[1], c[2], c[0] + h, c[1], c[2], 0.014f, Mat.BAR)
        buf.disc(c[0], c[1], c[2], 1f, 0f, 0f, PoseSolver.WHEEL_AXLE, 0.07f, Mat.PLATE)
    }

    // ------------------------------------------------------------------ helpers

    /** Horizontal unit direction from the pelvis toward the knees at the start of the rep. */
    private fun seatForward(out: FloatArray) {
        val rp = solver.refPos
        midOf(rp, Joint.KNEE_L, Joint.KNEE_R, out, 0)
        var dx = out[0] - rp[0]; var dz = out[2] - rp[2]
        val l = sqrt(dx * dx + dz * dz)
        if (l < 0.05f) { dx = solver.refPelAnt[0]; dz = solver.refPelAnt[2] } else { dx /= l; dz /= l }
        val l2 = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-4f)
        out[0] = dx / l2; out[1] = 0f; out[2] = dz / l2
    }

    private fun midOf(p: FloatArray, a: Int, b: Int, out: FloatArray, o: Int) {
        out[o] = (p[a * 3] + p[b * 3]) * 0.5f
        out[o + 1] = (p[a * 3 + 1] + p[b * 3 + 1]) * 0.5f
        out[o + 2] = (p[a * 3 + 2] + p[b * 3 + 2]) * 0.5f
    }

    /** Makes v (at offset o) a unit vector perpendicular to the unit vector d. */
    private fun orthonormal(v: FloatArray, o: Int, dx: Float, dy: Float, dz: Float) {
        val along = v[o] * dx + v[o + 1] * dy + v[o + 2] * dz
        v[o] -= along * dx; v[o + 1] -= along * dy; v[o + 2] -= along * dz
        val l = sqrt(v[o] * v[o] + v[o + 1] * v[o + 1] + v[o + 2] * v[o + 2])
        if (l < 1e-4f) {
            // Fall back to any perpendicular.
            if (abs(dy) < 0.9f) { v[o] = -dz; v[o + 1] = 0f; v[o + 2] = dx } else { v[o] = 1f; v[o + 1] = 0f; v[o + 2] = 0f }
            val l2 = sqrt(v[o] * v[o] + v[o + 1] * v[o + 1] + v[o + 2] * v[o + 2])
            v[o] /= l2; v[o + 1] /= l2; v[o + 2] /= l2
        } else {
            v[o] /= l; v[o + 1] /= l; v[o + 2] /= l
        }
    }

    private fun sq(x: Float) = x * x

    companion object {
        private val SIDES = floatArrayOf(-1f, 1f)
        /** Distance from the trunk line to the surface of the back (body half-thickness). */
        private const val BODY_BACK = 0.11f
        private const val PAD_HALF = 0.03f
        /** Hip joint height above the seat surface. */
        private const val SEAT_DROP = 0.1f
        /** Support surfaces sit under the body, so they sort behind it. */
        private const val UNDER_BIAS = 0.25f
    }
}
