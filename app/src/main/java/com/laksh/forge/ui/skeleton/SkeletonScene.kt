package com.laksh.forge.ui.skeleton

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.laksh.forge.domain.exercise.ExerciseMotion
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One exercise's 3D demonstration: pose solving, equipment and the painter's-algorithm
 * renderer, all on preallocated arrays.
 *
 * Per frame the work is a fixed list of items (22 bones, 16 joints, skull, ribs, pelvis,
 * muscle glows and the equipment primitives), each projected, given a depth and drawn back to
 * front. The list keeps the same indices every frame, so the insertion sort starts from last
 * frame's order and is effectively linear. Nothing here allocates once constructed: colours
 * are value classes, offsets are value classes, the one Path is rewound and reused.
 *
 * Not thread-safe: build it anywhere, then use it from the main thread only.
 */
class SkeletonScene(
    val motion: ExerciseMotion,
    primaryMuscles: List<String>,
    secondaryMuscles: List<String>
) {
    val solver = PoseSolver(motion)
    private val equipment = EquipmentBuilder(solver)
    private val prims = PrimBuffer()
    private val glows = MuscleGlows(primaryMuscles, secondaryMuscles)

    val centerX: Float
    val centerY: Float
    val centerZ: Float
    val radius: Float

    /** Rep period in normalised time: alternate-side lifts take two reps to loop. */
    val period: Float = if (motion.mirrorAlternate) 2f else 1f

    init {
        equipment.includeInBounds(prims)
        val b = solver.bounds
        centerX = (b[0] + b[3]) * 0.5f
        centerY = (b[1] + b[4]) * 0.5f
        centerZ = (b[2] + b[5]) * 0.5f
        val dx = b[3] - b[0]; val dy = b[4] - b[1]; val dz = b[5] - b[2]
        // The box's half-diagonal overstates a body (it is a box around a limb-shaped
        // cloud), so trim it; the camera still keeps everything in frame at every yaw.
        radius = (0.5f * sqrt(dx * dx + dy * dy + dz * dz) * 0.82f).coerceAtLeast(0.6f)
        pose(motion.keyPoseT)
    }

    // ------------------------------------------------------------------ per-frame buffers

    private val js = FloatArray(Joint.COUNT * 3)
    private val tmp = FloatArray(6)
    private val ring = FloatArray(RING_SEGMENTS * 3 + 3)
    private val path = Path()

    private val maxItems = Bones.COUNT + JointDraw.JOINTS.size + 3 + prims.capacity + glows.count
    private val itemKind = IntArray(maxItems)
    private val itemIndex = IntArray(maxItems)
    private val itemDepth = FloatArray(maxItems)
    private val order = IntArray(maxItems)
    private var lastCount = -1

    // Screen-space light direction (camera-locked, so constant): up and to the left.
    private val lsx = -0.514f
    private val lsy = -0.857f

    private var fogNear = 0f
    private var fogSpan = 1f
    private var onePx = 1f

    /** Poses the skeleton and equipment at rep time [time] (may run past 1 for alternating lifts). */
    fun pose(time: Float) {
        val rep = floor(time)
        val mirrored = motion.mirrorAlternate && (rep.toInt() and 1) == 1
        solver.solve(time - rep, mirrored)
        equipment.build(prims)
        glows.update(solver)
    }

    /**
     * Draws the current pose. [effort] (0..1) drives the primary-muscle glow; [detail] adds
     * the floor grid, rib cage and specular passes, which thumbnails skip.
     */
    fun draw(ds: DrawScope, cam: OrbitCamera, c: SkeletonColors, effort: Float, detail: Boolean) {
        val w = ds.size.width
        val h = ds.size.height
        if (w <= 1f || h <= 1f) return
        cam.frame(centerX, centerY, centerZ, radius)
        cam.update(w, h)
        onePx = ds.density
        val cd = cam.centerDepth()
        fogNear = cd - radius
        fogSpan = (radius * 2f).coerceAtLeast(0.1f)

        val pos = solver.pos
        for (j in 0 until Joint.COUNT) cam.project(pos[j * 3], pos[j * 3 + 1], pos[j * 3 + 2], js, j * 3)

        if (detail) drawGrid(ds, cam, c)
        drawShadows(ds, cam, c)

        // ---- collect items
        var n = 0
        for (b in 0 until Bones.COUNT) {
            itemKind[n] = K_BONE; itemIndex[n] = b
            itemDepth[n] = (js[Bones.A[b] * 3 + 2] + js[Bones.B[b] * 3 + 2]) * 0.5f
            n++
        }
        for (k in JointDraw.JOINTS.indices) {
            itemKind[n] = K_JOINT; itemIndex[n] = k
            // Spheres sit a hair in front of the capsule ends they cap.
            itemDepth[n] = js[JointDraw.JOINTS[k] * 3 + 2] - 0.012f
            n++
        }
        itemKind[n] = K_SKULL; itemIndex[n] = 0; itemDepth[n] = js[Joint.HEAD * 3 + 2]; n++
        itemKind[n] = K_PELVIS; itemIndex[n] = 0; itemDepth[n] = js[Joint.PELVIS * 3 + 2] + 0.01f; n++
        itemKind[n] = K_RIBS; itemIndex[n] = 0
        itemDepth[n] = cam.depth(
            (pos[Joint.CHEST * 3] + pos[Joint.SPINE * 3]) * 0.5f + solver.thorAnt[0] * 0.07f,
            (pos[Joint.CHEST * 3 + 1] + pos[Joint.SPINE * 3 + 1]) * 0.5f + solver.thorAnt[1] * 0.07f,
            (pos[Joint.CHEST * 3 + 2] + pos[Joint.SPINE * 3 + 2]) * 0.5f + solver.thorAnt[2] * 0.07f
        )
        n++
        for (k in 0 until prims.count) {
            itemKind[n] = K_PRIM; itemIndex[n] = k
            itemDepth[n] = primDepth(cam, k) + prims.bias[k]
            n++
        }
        val ge = glows.ends
        for (g in 0 until glows.count) {
            itemKind[n] = K_GLOW; itemIndex[n] = g
            val o = g * 6
            itemDepth[n] = cam.depth(
                (ge[o] + ge[o + 3]) * 0.5f, (ge[o + 1] + ge[o + 4]) * 0.5f, (ge[o + 2] + ge[o + 5]) * 0.5f
            ) + 0.004f
            n++
        }

        // ---- sort far to near
        if (n != lastCount) {
            for (i in 0 until n) order[i] = i
            lastCount = n
        }
        for (i in 1 until n) {
            val it = order[i]
            val d = itemDepth[it]
            var j = i - 1
            while (j >= 0 && itemDepth[order[j]] < d) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = it
        }

        val pulse = effort.coerceIn(0f, 1f)
        for (i in 0 until n) {
            val it = order[i]
            val idx = itemIndex[it]
            when (itemKind[it]) {
                K_BONE -> {
                    val a = Bones.A[idx] * 3
                    val b = Bones.B[idx] * 3
                    capsule(ds, cam, c, SkeletonColors.BONE, pos[a], pos[a + 1], pos[a + 2], pos[b], pos[b + 1], pos[b + 2], Bones.RADIUS[idx], detail, rim = true)
                }
                K_JOINT -> {
                    val j = JointDraw.JOINTS[idx] * 3
                    sphere(ds, cam, c, SkeletonColors.BONE, pos[j], pos[j + 1], pos[j + 2], JointDraw.RADIUS[idx], detail)
                }
                K_SKULL -> skull(ds, cam, c, detail)
                K_PELVIS -> pelvis(ds, cam, c)
                K_RIBS -> if (detail) ribs(ds, cam, c)
                K_PRIM -> prim(ds, cam, c, idx, detail)
                K_GLOW -> glow(ds, cam, c, idx, pulse)
            }
        }
    }

    /**
     * Screen-space box (minX, minY, maxX, maxY, in px) around the current pose and its
     * equipment, seen by [cam] in a [w] x [h] viewport. Uses the same framing [draw] does.
     *
     * The orbit framing centres a sphere around the whole rep at every yaw, which suits the
     * interactive viewer but leaves a single still off-centre (the bar's sweep and the floor
     * pull the sphere's centre away from where the key pose actually is). Thumbnails fit
     * this box instead. Returns false when nothing projected in front of the camera.
     */
    fun projectedBounds(cam: OrbitCamera, w: Float, h: Float, out: FloatArray): Boolean {
        if (w <= 1f || h <= 1f) return false
        cam.frame(centerX, centerY, centerZ, radius)
        cam.update(w, h)
        out[0] = Float.MAX_VALUE; out[1] = Float.MAX_VALUE
        out[2] = -Float.MAX_VALUE; out[3] = -Float.MAX_VALUE

        fun add(x: Float, y: Float, z: Float, r: Float) {
            val d = cam.depth(x, y, z)
            if (d < OrbitCamera.NEAR) return
            val sx = cam.screenX(x, y, z, d)
            val sy = cam.screenY(x, y, z, d)
            val rp = cam.focal * r / d
            out[0] = min(out[0], sx - rp); out[1] = min(out[1], sy - rp)
            out[2] = max(out[2], sx + rp); out[3] = max(out[3], sy + rp)
        }

        val pos = solver.pos
        for (j in 0 until Joint.COUNT) add(pos[j * 3], pos[j * 3 + 1], pos[j * 3 + 2], Anthro.R_JOINT)
        val hd = Joint.HEAD * 3
        add(pos[hd], pos[hd + 1], pos[hd + 2], Anthro.HEAD_RADIUS)

        val p = prims.p
        for (k in 0 until prims.count) {
            val o = k * 12
            val r = prims.radius[k]
            when (prims.kind[k]) {
                PrimBuffer.TUBE -> {
                    add(p[o], p[o + 1], p[o + 2], r)
                    add(p[o + 3], p[o + 4], p[o + 5], r)
                }
                PrimBuffer.DISC -> {
                    // Sample the rim: a plate seen edge-on is a thin bar, face-on a full circle.
                    val nx = p[o + 3]; val ny = p[o + 4]; val nz = p[o + 5]
                    // Any vector not parallel to n gives the first in-plane axis.
                    var ax: Float; var ay: Float; var az: Float
                    if (abs(ny) < 0.9f) { ax = nz; ay = 0f; az = -nx } else { ax = 0f; ay = -nz; az = ny }
                    val al = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-6f)
                    ax /= al; ay /= al; az /= al
                    val bx = ny * az - nz * ay
                    val by = nz * ax - nx * az
                    val bz = nx * ay - ny * ax
                    val half = p[o + 6] * 0.5f
                    for (i in 0 until 8) {
                        val t = i * TWO_PI / 8f
                        val c = cos(t) * r; val s = sin(t) * r
                        add(p[o] + ax * c + bx * s, p[o + 1] + ay * c + by * s, p[o + 2] + az * c + bz * s, half)
                    }
                }
                else -> for (i in 0 until 8) {
                    val su = if (i and 1 == 0) -1f else 1f
                    val sv = if (i and 2 == 0) -1f else 1f
                    val sw = if (i and 4 == 0) -1f else 1f
                    add(
                        p[o] + su * p[o + 3] + sv * p[o + 6] + sw * p[o + 9],
                        p[o + 1] + su * p[o + 4] + sv * p[o + 7] + sw * p[o + 10],
                        p[o + 2] + su * p[o + 5] + sv * p[o + 8] + sw * p[o + 11],
                        0f
                    )
                }
            }
        }
        return out[2] > out[0] && out[3] > out[1]
    }

    // ------------------------------------------------------------------ floor

    private fun drawGrid(ds: DrawScope, cam: OrbitCamera, c: SkeletonColors) {
        val gx = floor(centerX / GRID_STEP) * GRID_STEP
        val gz = floor(centerZ / GRID_STEP) * GRID_STEP
        val half = GRID_LINES * GRID_STEP
        val width = onePx
        for (axis in 0..1) {
            for (k in -GRID_LINES..GRID_LINES) {
                val off = k * GRID_STEP
                val lineFade = 1f - abs(k).toFloat() / (GRID_LINES + 1)
                // Each line in pieces so it fades out toward the edge of the floor.
                for (s in 0 until GRID_PIECES) {
                    val t0 = -half + 2f * half * s / GRID_PIECES
                    val t1 = -half + 2f * half * (s + 1) / GRID_PIECES
                    val mid = (t0 + t1) * 0.5f
                    val fade = lineFade * (1f - abs(mid) / (half + GRID_STEP))
                    if (fade <= 0.02f) continue
                    val x0: Float; val z0: Float; val x1: Float; val z1: Float
                    if (axis == 0) { x0 = gx + off; x1 = gx + off; z0 = gz + t0; z1 = gz + t1 } else { z0 = gz + off; z1 = gz + off; x0 = gx + t0; x1 = gx + t1 }
                    if (!clipSegment(cam, x0, 0f, z0, x1, 0f, z1)) continue
                    ds.drawLine(
                        c.plain(SkeletonColors.GRID, 0.55f * fade),
                        Offset(tmp[0], tmp[1]), Offset(tmp[3], tmp[4]),
                        strokeWidth = width
                    )
                }
            }
        }
    }

    /**
     * Clips a world segment to the near plane and projects it: tmp[0..1] start, tmp[3..4] end.
     * Returns false when it is wholly behind the camera.
     */
    private fun clipSegment(cam: OrbitCamera, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Boolean {
        val za = cam.depth(ax, ay, az)
        val zb = cam.depth(bx, by, bz)
        val near = OrbitCamera.NEAR * 4f
        if (za < near && zb < near) return false
        var x0 = ax; var y0 = ay; var z0 = az; var d0 = za
        var x1 = bx; var y1 = by; var z1 = bz; var d1 = zb
        if (d0 < near) {
            val t = (near - d0) / (d1 - d0)
            x0 += (x1 - x0) * t; y0 += (y1 - y0) * t; z0 += (z1 - z0) * t; d0 = near
        } else if (d1 < near) {
            val t = (near - d1) / (d0 - d1)
            x1 += (x0 - x1) * t; y1 += (y0 - y1) * t; z1 += (z0 - z1) * t; d1 = near
        }
        tmp[0] = cam.screenX(x0, y0, z0, d0); tmp[1] = cam.screenY(x0, y0, z0, d0); tmp[2] = d0
        tmp[3] = cam.screenX(x1, y1, z1, d1); tmp[4] = cam.screenY(x1, y1, z1, d1); tmp[5] = d1
        return true
    }

    private fun drawShadows(ds: DrawScope, cam: OrbitCamera, c: SkeletonColors) {
        val pos = solver.pos
        // A broad soft pool under the lifter grounds the whole figure.
        val b = solver.bounds
        val poolR = max(b[3] - b[0], b[5] - b[2]) * 0.42f + 0.12f
        for (k in 0 until 3) {
            val s = 1f - k * 0.28f
            floorEllipse(ds, cam, c, centerX, centerZ, poolR * s, 1f, 0f, poolR * 0.75f * s, 0.045f)
        }
        // Contact shadows: darker, tight ellipses under feet that are on the floor.
        val so = solver.soles
        for (side in 0..1) {
            val o = side * 6
            val low = min(so[o + 1], so[o + 4])
            if (low > 0.06f) continue
            val strength = 1f - low / 0.06f
            val mx = (so[o] + so[o + 3]) * 0.5f
            val mz = (so[o + 2] + so[o + 5]) * 0.5f
            var fx = so[o] - so[o + 3]
            var fz = so[o + 2] - so[o + 5]
            val fl = sqrt(fx * fx + fz * fz).coerceAtLeast(1e-4f)
            fx /= fl; fz /= fl
            for (k in 0 until 3) {
                val s = 1f - k * 0.25f
                floorEllipse(ds, cam, c, mx, mz, 0.13f * s, fx, fz, 0.06f * s, 0.07f * strength)
            }
        }
        // Drop shadows of the bones straight down onto the floor, fading with height.
        for (k in 0 until Bones.COUNT) {
            val a = Bones.A[k] * 3
            val bb = Bones.B[k] * 3
            val hgt = (pos[a + 1] + pos[bb + 1]) * 0.5f
            val alpha = 0.2f * (1f - (hgt / 1.6f)).coerceIn(0f, 1f)
            if (alpha < 0.01f) continue
            if (!clipSegment(cam, pos[a], 0.002f, pos[a + 2], pos[bb], 0.002f, pos[bb + 2])) continue
            val z = (tmp[2] + tmp[5]) * 0.5f
            val wpx = cam.focal * (Bones.RADIUS[k] * 2.4f + 0.012f) / z
            ds.drawLine(c.plain(SkeletonColors.SHADOW, alpha), Offset(tmp[0], tmp[1]), Offset(tmp[3], tmp[4]), strokeWidth = wpx, cap = StrokeCap.Round)
        }
        val p = prims.p
        for (k in 0 until prims.count) {
            if (prims.kind[k] != PrimBuffer.TUBE) continue
            val o = k * 12
            val hgt = (p[o + 1] + p[o + 4]) * 0.5f
            if (hgt < 0.04f) continue
            val alpha = 0.14f * (1f - (hgt / 1.8f)).coerceIn(0f, 1f)
            if (alpha < 0.01f) continue
            if (!clipSegment(cam, p[o], 0.002f, p[o + 2], p[o + 3], 0.002f, p[o + 5])) continue
            val z = (tmp[2] + tmp[5]) * 0.5f
            val wpx = cam.focal * (prims.radius[k] * 2.2f + 0.01f) / z
            ds.drawLine(c.plain(SkeletonColors.SHADOW, alpha), Offset(tmp[0], tmp[1]), Offset(tmp[3], tmp[4]), strokeWidth = wpx, cap = StrokeCap.Round)
        }
    }

    /** Filled ellipse on the floor: centre, semi-axis [ra] along (ax, az), [rb] across it. */
    private fun floorEllipse(ds: DrawScope, cam: OrbitCamera, c: SkeletonColors, x: Float, z: Float, ra: Float, ax: Float, az: Float, rb: Float, alpha: Float) {
        path.rewind()
        for (i in 0 until RING_SEGMENTS) {
            val t = i * TWO_PI / RING_SEGMENTS
            val ca = cos(t); val sa = sin(t)
            // Perpendicular in the floor plane to (ax, az) is (-az, ax).
            val px = x + ax * ra * ca - az * rb * sa
            val pz = z + az * ra * ca + ax * rb * sa
            val d = cam.depth(px, 0.001f, pz)
            if (d < OrbitCamera.NEAR * 4f) return
            val sx = cam.screenX(px, 0.001f, pz, d)
            val sy = cam.screenY(px, 0.001f, pz, d)
            if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }
        path.close()
        ds.drawPath(path, c.plain(SkeletonColors.SHADOW, alpha))
    }

    // ------------------------------------------------------------------ primitives

    private fun fogAt(depth: Float): Float = (((depth - fogNear) / fogSpan).coerceIn(0f, 1f)) * 0.28f

    /**
     * A shaded capsule: dark outline, lit body, a highlight band and specular streak on the
     * side facing the key light, and a thin rim light on the far edge. Five strokes fake the
     * cylinder's gradient without a shader.
     */
    private fun capsule(
        ds: DrawScope, cam: OrbitCamera, c: SkeletonColors, slot: Int,
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        radius: Float, detail: Boolean, rim: Boolean
    ) {
        val za = cam.depth(ax, ay, az)
        val zb = cam.depth(bx, by, bz)
        if (za < OrbitCamera.NEAR || zb < OrbitCamera.NEAR) return
        val sax = cam.screenX(ax, ay, az, za); val say = cam.screenY(ax, ay, az, za)
        val sbx = cam.screenX(bx, by, bz, zb); val sby = cam.screenY(bx, by, bz, zb)
        val zm = (za + zb) * 0.5f
        val rs = (cam.focal * radius / zm).coerceAtLeast(0.75f)

        var nx: Float; var ny: Float
        val dx = sbx - sax; val dy = sby - say
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.5f) { nx = lsx; ny = lsy } else {
            nx = -dy / len; ny = dx / len
            if (nx * lsx + ny * lsy < 0f) { nx = -nx; ny = -ny }
        }

        // Brightest normal of a cylinder under a directional light: sqrt(1 - (L.axis)^2).
        val wx = bx - ax; val wy = by - ay; val wz = bz - az
        val wl = sqrt(wx * wx + wy * wy + wz * wz)
        val la = if (wl > 1e-5f) (cam.light[0] * wx + cam.light[1] * wy + cam.light[2] * wz) / wl else 0f
        val k = sqrt((1f - la * la).coerceAtLeast(0f))
        val fog = fogAt(zm)

        val a = Offset(sax, say)
        val b = Offset(sbx, sby)
        ds.drawLine(c.lit(SkeletonColors.OUTLINE, 1f, fog), a, b, strokeWidth = rs * 2f + onePx * 1.6f, cap = StrokeCap.Round)
        ds.drawLine(c.lit(slot, 0.52f + 0.3f * k, fog), a, b, strokeWidth = rs * 2f, cap = StrokeCap.Round)
        val hx = nx * rs * 0.3f; val hy = ny * rs * 0.3f
        ds.drawLine(c.lit(slot, 0.85f + 0.3f * k, fog), Offset(sax + hx, say + hy), Offset(sbx + hx, sby + hy), strokeWidth = rs * 0.95f, cap = StrokeCap.Round)
        if (detail) {
            val sx = nx * rs * 0.5f; val sy = ny * rs * 0.5f
            ds.drawLine(
                c.plain(SkeletonColors.HIGHLIGHT, 0.55f * k * k * (1f - fog)),
                Offset(sax + sx, say + sy), Offset(sbx + sx, sby + sy),
                strokeWidth = rs * 0.28f, cap = StrokeCap.Round
            )
        }
        if (rim && rs > 1.5f) {
            val rx = -nx * rs * 0.78f; val ry = -ny * rs * 0.78f
            ds.drawLine(
                c.plain(SkeletonColors.RIM, 0.55f * (1f - fog)),
                Offset(sax + rx, say + ry), Offset(sbx + rx, sby + ry),
                strokeWidth = rs * 0.24f, cap = StrokeCap.Round
            )
        }
    }

    private fun sphere(ds: DrawScope, cam: OrbitCamera, c: SkeletonColors, slot: Int, x: Float, y: Float, z: Float, radius: Float, detail: Boolean) {
        val d = cam.depth(x, y, z)
        if (d < OrbitCamera.NEAR) return
        val sx = cam.screenX(x, y, z, d)
        val sy = cam.screenY(x, y, z, d)
        val rs = (cam.focal * radius / d).coerceAtLeast(0.75f)
        val fog = fogAt(d)
        ds.drawCircle(c.lit(SkeletonColors.OUTLINE, 1f, fog), radius = rs + onePx * 0.8f, center = Offset(sx, sy))
        ds.drawCircle(c.lit(slot, 0.62f, fog), radius = rs, center = Offset(sx, sy))
        ds.drawCircle(c.lit(slot, 0.98f, fog), radius = rs * 0.66f, center = Offset(sx + lsx * rs * 0.25f, sy + lsy * rs * 0.25f))
        if (detail) {
            ds.drawCircle(
                c.plain(SkeletonColors.HIGHLIGHT, 0.5f * (1f - fog)),
                radius = rs * 0.2f,
                center = Offset(sx + lsx * rs * 0.45f, sy + lsy * rs * 0.45f)
            )
        }
    }

    private fun primDepth(cam: OrbitCamera, k: Int): Float {
        val p = prims.p
        val o = k * 12
        return when (prims.kind[k]) {
            PrimBuffer.TUBE -> cam.depth((p[o] + p[o + 3]) * 0.5f, (p[o + 1] + p[o + 4]) * 0.5f, (p[o + 2] + p[o + 5]) * 0.5f)
            else -> cam.depth(p[o], p[o + 1], p[o + 2])
        }
    }

    private fun prim(ds: DrawScope, cam: OrbitCamera, c: SkeletonColors, k: Int, detail: Boolean) {
        val p = prims.p
        val o = k * 12
        val slot = prims.mat[k]
        when (prims.kind[k]) {
            PrimBuffer.TUBE -> capsule(ds, cam, c, slot, p[o], p[o + 1], p[o + 2], p[o + 3], p[o + 4], p[o + 5], prims.radius[k], detail, rim = false)
            PrimBuffer.DISC -> disc(ds, cam, c, slot, o, prims.radius[k])
            PrimBuffer.BOX -> box(ds, cam, c, slot, o)
        }
    }

    /** A short cylinder (plate, wheel, pulley): far face, near face, and a hub. */
    private fun disc(ds: DrawScope, cam: OrbitCamera, c: SkeletonColors, slot: Int, o: Int, r: Float) {
        val p = prims.p
        val cx = p[o]; val cy = p[o + 1]; val cz = p[o + 2]
        var nx = p[o + 3]; var ny = p[o + 4]; var nz = p[o + 5]
        val th = p[o + 6]
        val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
        nx /= nl; ny /= nl; nz /= nl
        // In-plane basis u, v.
        var ux: Float; var uy: Float; var uz: Float
        if (abs(ny) < 0.9f) { ux = nz; uy = 0f; uz = -nx } else { ux = 0f; uy = -nz; uz = ny }
        val ul = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(1e-6f)
        ux /= ul; uy /= ul; uz /= ul
        val vx = ny * uz - nz * uy
        val vy = nz * ux - nx * uz
        val vz = nx * uy - ny * ux
        // Which face looks at the camera.
        val toEyeX = cam.ex - cx; val toEyeY = cam.ey - cy; val toEyeZ = cam.ez - cz
        val s = if (nx * toEyeX + ny * toEyeY + nz * toEyeZ >= 0f) 1f else -1f
        val lam = abs(nx * cam.light[0] + ny * cam.light[1] + nz * cam.light[2])
        val fog = fogAt(cam.depth(cx, cy, cz))
        val half = th * 0.5f
        // Far face first, darker: it shows as the plate's edge in oblique views.
        if (!ringPath(cam, cx - nx * half * s, cy - ny * half * s, cz - nz * half * s, ux, uy, uz, vx, vy, vz, r, r)) return
        ds.drawPath(path, c.lit(slot, 0.38f, fog))
        if (!ringPath(cam, cx + nx * half * s, cy + ny * half * s, cz + nz * half * s, ux, uy, uz, vx, vy, vz, r, r)) return
        ds.drawPath(path, c.lit(slot, 0.6f + 0.45f * lam, fog))
        if (r > 0.08f) {
            // Raised lip and hub so a plate reads as a plate rather than a coin.
            if (ringPath(cam, cx + nx * half * s, cy + ny * half * s, cz + nz * half * s, ux, uy, uz, vx, vy, vz, r * 0.8f, r * 0.8f)) {
                ds.drawPath(path, c.lit(slot, 0.5f + 0.35f * lam, fog))
            }
            if (ringPath(cam, cx + nx * half * s, cy + ny * half * s, cz + nz * half * s, ux, uy, uz, vx, vy, vz, r * 0.2f, r * 0.2f)) {
                ds.drawPath(path, c.lit(SkeletonColors.BAR, 0.75f + 0.3f * lam, fog))
            }
        }
    }

    /** Builds [path] as a projected ellipse; false if any point is behind the camera. */
    private fun ringPath(
        cam: OrbitCamera, cx: Float, cy: Float, cz: Float,
        ux: Float, uy: Float, uz: Float, vx: Float, vy: Float, vz: Float,
        ru: Float, rv: Float
    ): Boolean {
        path.rewind()
        for (i in 0 until RING_SEGMENTS) {
            val t = i * TWO_PI / RING_SEGMENTS
            val a = cos(t) * ru
            val b = sin(t) * rv
            val x = cx + ux * a + vx * b
            val y = cy + uy * a + vy * b
            val z = cz + uz * a + vz * b
            val d = cam.depth(x, y, z)
            if (d < OrbitCamera.NEAR) return false
            val sx = cam.screenX(x, y, z, d)
            val sy = cam.screenY(x, y, z, d)
            if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }
        path.close()
        return true
    }

    /** Oriented box: only camera-facing faces are drawn, which is exact for a convex solid. */
    private fun box(ds: DrawScope, cam: OrbitCamera, c: SkeletonColors, slot: Int, o: Int) {
        val p = prims.p
        val fog = fogAt(cam.depth(p[o], p[o + 1], p[o + 2]))
        for (axis in 0..2) {
            val ao = o + 3 + axis * 3
            val bo = o + 3 + ((axis + 1) % 3) * 3
            val co = o + 3 + ((axis + 2) % 3) * 3
            val al = sqrt(p[ao] * p[ao] + p[ao + 1] * p[ao + 1] + p[ao + 2] * p[ao + 2])
            if (al < 1e-5f) continue
            for (sgn in 0..1) {
                val sg = if (sgn == 0) 1f else -1f
                val fx = p[o] + p[ao] * sg
                val fy = p[o + 1] + p[ao + 1] * sg
                val fz = p[o + 2] + p[ao + 2] * sg
                val nx = p[ao] * sg / al; val ny = p[ao + 1] * sg / al; val nz = p[ao + 2] * sg / al
                if (nx * (cam.ex - fx) + ny * (cam.ey - fy) + nz * (cam.ez - fz) <= 0f) continue
                val lam = (nx * cam.light[0] + ny * cam.light[1] + nz * cam.light[2]).coerceAtLeast(0f)
                path.rewind()
                var ok = true
                for (corner in 0..3) {
                    val sb = if (corner == 0 || corner == 1) 1f else -1f
                    val sc = if (corner == 0 || corner == 3) 1f else -1f
                    val x = fx + p[bo] * sb + p[co] * sc
                    val y = fy + p[bo + 1] * sb + p[co + 1] * sc
                    val z = fz + p[bo + 2] * sb + p[co + 2] * sc
                    val d = cam.depth(x, y, z)
                    if (d < OrbitCamera.NEAR) { ok = false; break }
                    val sx = cam.screenX(x, y, z, d)
                    val sy = cam.screenY(x, y, z, d)
                    if (corner == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                }
                if (!ok) continue
                path.close()
                ds.drawPath(path, c.lit(slot, 0.5f + 0.55f * lam, fog))
            }
        }
    }

    // ------------------------------------------------------------------ anatomy hints

    private fun skull(ds: DrawScope, cam: OrbitCamera, c: SkeletonColors, detail: Boolean) {
        val pos = solver.pos
        val up = solver.thorUp
        val ant = solver.thorAnt
        val h = Joint.HEAD * 3
        val hx = pos[h]; val hy = pos[h + 1]; val hz = pos[h + 2]
        val jx = hx + ant[0] * 0.05f - up[0] * 0.085f
        val jy = hy + ant[1] * 0.05f - up[1] * 0.085f
        val jz = hz + ant[2] * 0.05f - up[2] * 0.085f
        // Facing: < 0 when the face points at the camera.
        val facing = ant[0] * cam.fx + ant[1] * cam.fy + ant[2] * cam.fz
        if (facing < 0f) {
            sphere(ds, cam, c, SkeletonColors.BONE, hx, hy, hz, Anthro.HEAD_RADIUS, detail)
            sphere(ds, cam, c, SkeletonColors.BONE, jx, jy, jz, 0.045f, detail)
            val show = (-facing * 1.6f).coerceIn(0f, 1f)
            if (show > 0.05f) {
                for (side in 0..1) {
                    val sg = Joint.sign(side)
                    val ex = hx + ant[0] * 0.078f + up[0] * 0.005f + sg * 0.034f
                    val ey = hy + ant[1] * 0.078f + up[1] * 0.005f
                    val ez = hz + ant[2] * 0.078f + up[2] * 0.005f
                    val d = cam.depth(ex, ey, ez)
                    if (d < OrbitCamera.NEAR) continue
                    ds.drawCircle(
                        c.lit(SkeletonColors.SOCKET, 1f, fogAt(d), alpha = show),
                        radius = cam.focal * 0.019f / d,
                        center = Offset(cam.screenX(ex, ey, ez, d), cam.screenY(ex, ey, ez, d))
                    )
                }
            }
        } else {
            sphere(ds, cam, c, SkeletonColors.BONE, jx, jy, jz, 0.045f, detail)
            sphere(ds, cam, c, SkeletonColors.BONE, hx, hy, hz, Anthro.HEAD_RADIUS, detail)
        }
    }

    /** Five rib hoops hung off the thoracic spine, plus the sternum. */
    private fun ribs(ds: DrawScope, cam: OrbitCamera, c: SkeletonColors) {
        val pos = solver.pos
        val up = solver.thorUp
        val ant = solver.thorAnt
        val n = Joint.NECK * 3
        val s = Joint.SPINE * 3
        for (k in 0 until 5) {
            val f = 0.2f + k * 0.16f
            val bx = pos[n] + (pos[s] - pos[n]) * f
            val by = pos[n + 1] + (pos[s + 1] - pos[n + 1]) * f
            val bz = pos[n + 2] + (pos[s + 2] - pos[n + 2]) * f
            val wl = 0.085f + 0.035f * sin(PI_F * (0.35f + f * 0.6f))
            val wa = 0.065f + 0.025f * sin(PI_F * f)
            // Hoop centre sits forward of the spine by its own depth, so the back of each
            // hoop meets the vertebrae.
            val cx = bx + ant[0] * wa; val cy = by + ant[1] * wa; val cz = bz + ant[2] * wa
            var count = 0
            for (i in 0..RING_SEGMENTS) {
                val t = i * TWO_PI / RING_SEGMENTS
                val ca = cos(t); val sa = sin(t)
                // Ribs slope down toward the front.
                val drop = 0.03f * ((sa + 1f) * 0.5f)
                // Lateral is world x; the hoop's depth runs along the thorax's anterior axis.
                val x = cx + ca * wl + ant[0] * sa * wa - up[0] * drop
                val y = cy + ant[1] * sa * wa - up[1] * drop
                val z = cz + ant[2] * sa * wa - up[2] * drop
                val d = cam.depth(x, y, z)
                if (d < OrbitCamera.NEAR) return
                ring[count * 3] = cam.screenX(x, y, z, d)
                ring[count * 3 + 1] = cam.screenY(x, y, z, d)
                ring[count * 3 + 2] = d
                count++
            }
            val zr = cam.depth(cx, cy, cz)
            val wpx = (cam.focal * 0.0075f / zr).coerceAtLeast(onePx * 0.8f)
            val col = c.lit(SkeletonColors.BONE, 0.82f, fogAt(zr), alpha = 0.85f)
            for (i in 0 until count - 1) {
                ds.drawLine(col, Offset(ring[i * 3], ring[i * 3 + 1]), Offset(ring[i * 3 + 3], ring[i * 3 + 4]), strokeWidth = wpx, cap = StrokeCap.Round)
            }
        }
        // Sternum down the front of the hoops.
        val f0 = 0.2f; val f1 = 0.72f
        val wa0 = 0.065f + 0.025f * sin(PI_F * f0)
        val wa1 = 0.065f + 0.025f * sin(PI_F * f1)
        for (e in 0..1) {
            val f = if (e == 0) f0 else f1
            val wa = (if (e == 0) wa0 else wa1) * 2f
            tmp[e * 3] = pos[n] + (pos[s] - pos[n]) * f + ant[0] * wa - up[0] * 0.03f
            tmp[e * 3 + 1] = pos[n + 1] + (pos[s + 1] - pos[n + 1]) * f + ant[1] * wa - up[1] * 0.03f
            tmp[e * 3 + 2] = pos[n + 2] + (pos[s + 2] - pos[n + 2]) * f + ant[2] * wa - up[2] * 0.03f
        }
        capsule(ds, cam, c, SkeletonColors.BONE, tmp[0], tmp[1], tmp[2], tmp[3], tmp[4], tmp[5], 0.011f, detail = false, rim = false)
    }

    /** The pelvic ring around the hips. */
    private fun pelvis(ds: DrawScope, cam: OrbitCamera, c: SkeletonColors) {
        val pos = solver.pos
        val up = solver.pelUp
        val ant = solver.pelAnt
        val p = Joint.PELVIS * 3
        val cx = pos[p] + up[0] * 0.03f + ant[0] * 0.015f
        val cy = pos[p + 1] + up[1] * 0.03f + ant[1] * 0.015f
        val cz = pos[p + 2] + up[2] * 0.03f + ant[2] * 0.015f
        val zr = cam.depth(cx, cy, cz)
        if (zr < OrbitCamera.NEAR) return
        // Lateral axis is world x for the trunk frames.
        if (!ringPath(cam, cx, cy, cz, 1f, 0f, 0f, ant[0], ant[1], ant[2], 0.13f, 0.085f)) return
        val fog = fogAt(zr)
        ds.drawPath(path, c.lit(SkeletonColors.BONE, 0.55f, fog, alpha = 0.5f))
        if (!ringPath(cam, cx + up[0] * 0.02f, cy + up[1] * 0.02f, cz + up[2] * 0.02f, 1f, 0f, 0f, ant[0], ant[1], ant[2], 0.095f, 0.06f)) return
        ds.drawPath(path, c.lit(SkeletonColors.OUTLINE, 1f, fog, alpha = 0.55f))
    }

    // ------------------------------------------------------------------ muscles

    private fun glow(ds: DrawScope, cam: OrbitCamera, c: SkeletonColors, g: Int, effort: Float) {
        val e = glows.ends
        val o = g * 6
        val za = cam.depth(e[o], e[o + 1], e[o + 2])
        val zb = cam.depth(e[o + 3], e[o + 4], e[o + 5])
        if (za < OrbitCamera.NEAR || zb < OrbitCamera.NEAR) return
        val a = Offset(cam.screenX(e[o], e[o + 1], e[o + 2], za), cam.screenY(e[o], e[o + 1], e[o + 2], za))
        val b = Offset(cam.screenX(e[o + 3], e[o + 4], e[o + 5], zb), cam.screenY(e[o + 3], e[o + 4], e[o + 5], zb))
        val rs = cam.focal * glows.radius[g] * 2f / (za + zb)
        val primary = glows.primary[g]
        // Primary movers pulse with the effort curve; secondary ones glow steadily and dimmer.
        val level = if (primary) 0.3f + 0.7f * effort else 0.35f
        val slot = if (primary) SkeletonColors.GLOW else SkeletonColors.GLOW_2
        ds.drawLine(c.plain(slot, 0.14f * level), a, b, strokeWidth = rs * 3.4f, cap = StrokeCap.Round)
        ds.drawLine(c.plain(slot, 0.26f * level), a, b, strokeWidth = rs * 2.2f, cap = StrokeCap.Round)
        ds.drawLine(c.plain(slot, 0.5f * level), a, b, strokeWidth = rs * 1.2f, cap = StrokeCap.Round)
    }

    private companion object {
        const val K_BONE = 0
        const val K_JOINT = 1
        const val K_PRIM = 2
        const val K_GLOW = 3
        const val K_SKULL = 4
        const val K_RIBS = 5
        const val K_PELVIS = 6

        const val RING_SEGMENTS = 18
        const val GRID_STEP = 0.25f
        const val GRID_LINES = 7
        const val GRID_PIECES = 6
        const val PI_F = Math.PI.toFloat()
        const val TWO_PI = (Math.PI * 2.0).toFloat()
    }
}
