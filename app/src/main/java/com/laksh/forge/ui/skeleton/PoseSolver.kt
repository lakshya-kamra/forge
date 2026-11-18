package com.laksh.forge.ui.skeleton

import com.laksh.forge.domain.exercise.ArmMode
import com.laksh.forge.domain.exercise.BarMount
import com.laksh.forge.domain.exercise.ExerciseMotion
import com.laksh.forge.domain.exercise.LegContact
import com.laksh.forge.domain.exercise.MotionAnchor
import com.laksh.forge.domain.exercise.MotionProp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns an exercise's keyframes into world-space joint positions.
 *
 * Each frame: interpolate the joint angles, run forward kinematics from the pelvis, place the
 * pelvis in the world according to what the lifter is anchored by (feet on the floor, hands on
 * a bar, hips on a bench...), then solve two-bone IK so every fixed contact stays exactly where
 * it was at the start of the rep. FK alone drifts: angle data rounded to whole degrees moves a
 * planted foot by centimetres over a rep, which reads as skating. The IK absorbs that drift
 * into the knee and elbow, which is where a real body absorbs it too.
 *
 * Everything is preallocated; [solve] allocates nothing, so it can run inside a draw call at
 * 120 Hz.
 */
class PoseSolver(val motion: ExerciseMotion) {

    val clip = MotionClip(motion)

    /** Joint positions, xyz per [Joint]. */
    val pos = FloatArray(Joint.COUNT * 3)

    // Current trunk frames (world unit vectors). Lateral is always world x.
    val thorUp = FloatArray(3)
    val thorAnt = FloatArray(3)
    val pelUp = FloatArray(3)
    val pelAnt = FloatArray(3)

    /** Hand positions the arm FK asked for (world), before contacts override them. */
    val handFk = FloatArray(6)
    private val elbowFk = FloatArray(6)
    /** Per side: ball-of-foot sole point xyz then heel sole point xyz. */
    val soles = FloatArray(12)
    /** Per side: the direction the toes point (world unit vector). */
    val legForward = FloatArray(6)

    /** Midpoint of the hands and the left-minus-right unit axis, for drawing bars and handles. */
    val barCenter = FloatArray(3)
    val barAxis = FloatArray(3)

    /** Pose at t = 0, for equipment that does not move (benches, racks, platforms). */
    val refPos = FloatArray(Joint.COUNT * 3)
    val refThorUp = FloatArray(3)
    val refThorAnt = FloatArray(3)
    val refPelUp = FloatArray(3)
    val refPelAnt = FloatArray(3)
    val refSoles = FloatArray(12)

    /** World bounds of the skeleton over the whole rep: minX, minY, minZ, maxX, maxY, maxZ. */
    val bounds = FloatArray(6)

    /** Fixed hand points (bars, floor) captured at the start of the rep. */
    val fixedHands = FloatArray(6)

    /** Current grip half-width in metres. */
    var gripHalf = 0f
        private set

    private val ch = FloatArray(MotionClip.CHANNELS)
    private val fixAnkle = FloatArray(6)
    private val fixToe = FloatArray(6)
    private val fixHeel = FloatArray(6)
    private val fixKnee = FloatArray(6)
    private val fixPelvis = FloatArray(3)
    private val fixChest = FloatArray(3)
    private val fixHandMid = FloatArray(3)
    private val footVec = FloatArray(6)
    private val tmp = FloatArray(6)
    private val heelVec = FloatArray(6)
    private var cableLateralHold = FloatArray(3)

    private val turnout = Math.toRadians(motion.turnoutDeg.toDouble()).toFloat()

    init {
        reference()
        computeBounds()
    }

    /** Solve the pose at normalised rep time [t]. [mirrored] swaps left and right (alternate reps). */
    fun solve(t: Float, mirrored: Boolean = false) {
        clip.sample(t, ch)
        if (mirrored) swapSides()
        fk()
        val ox: Float
        val oy: Float
        val oz: Float
        when (motion.anchor) {
            MotionAnchor.FEET, MotionAnchor.LEAD_FOOT -> {
                var n = 0; var sx = 0f; var sy = 0f; var sz = 0f; var fx = 0f; var fy = 0f; var fz = 0f
                for (s in 0..1) if (anchorLeg(s)) {
                    n++
                    sx += fixAnkle[s * 3]; sy += fixAnkle[s * 3 + 1]; sz += fixAnkle[s * 3 + 2]
                    val a = Joint.ankle(s)
                    fx += pos[a * 3]; fy += pos[a * 3 + 1]; fz += pos[a * 3 + 2]
                }
                if (n == 0) { ox = 0f; oy = 0f; oz = 0f } else { ox = (sx - fx) / n; oy = (sy - fy) / n; oz = (sz - fz) / n }
            }
            MotionAnchor.BALLS, MotionAnchor.TOES -> {
                ox = (fixToe[0] + fixToe[3] - pos[Joint.TOE_L * 3] - pos[Joint.TOE_R * 3]) * 0.5f
                oy = (fixToe[1] + fixToe[4] - pos[Joint.TOE_L * 3 + 1] - pos[Joint.TOE_R * 3 + 1]) * 0.5f
                oz = (fixToe[2] + fixToe[5] - pos[Joint.TOE_L * 3 + 2] - pos[Joint.TOE_R * 3 + 2]) * 0.5f
            }
            MotionAnchor.KNEES -> {
                ox = (fixKnee[0] + fixKnee[3] - pos[Joint.KNEE_L * 3] - pos[Joint.KNEE_R * 3]) * 0.5f
                oy = (fixKnee[1] + fixKnee[4] - pos[Joint.KNEE_L * 3 + 1] - pos[Joint.KNEE_R * 3 + 1]) * 0.5f
                oz = (fixKnee[2] + fixKnee[5] - pos[Joint.KNEE_L * 3 + 2] - pos[Joint.KNEE_R * 3 + 2]) * 0.5f
            }
            MotionAnchor.PELVIS -> { ox = fixPelvis[0]; oy = fixPelvis[1]; oz = fixPelvis[2] }
            MotionAnchor.UPPER_BACK -> {
                ox = fixChest[0] - pos[Joint.CHEST * 3]
                oy = fixChest[1] - pos[Joint.CHEST * 3 + 1]
                oz = fixChest[2] - pos[Joint.CHEST * 3 + 2]
            }
            MotionAnchor.HANDS -> {
                ox = fixHandMid[0] - (handFk[0] + handFk[3]) * 0.5f
                oy = fixHandMid[1] - (handFk[1] + handFk[4]) * 0.5f
                oz = fixHandMid[2] - (handFk[2] + handFk[5]) * 0.5f
            }
            MotionAnchor.GAIT -> { ox = 0f; oy = -lowestSole(0, 1); oz = 0f }
        }
        translate(ox, oy, oz)
        solveLegs()
        solveArms()
        updateBar()
    }

    // ------------------------------------------------------------------ forward kinematics

    /** FK with the pelvis at the origin. */
    private fun fk() {
        val c = ch
        val th = rad(c[MotionClip.TRUNK])
        val sp = rad(c[MotionClip.SPINE])
        val tl = th - sp * 0.5f
        val tt = th + sp * 0.5f
        pelUp[0] = 0f; pelUp[1] = cos(tl); pelUp[2] = sin(tl)
        pelAnt[0] = 0f; pelAnt[1] = -sin(tl); pelAnt[2] = cos(tl)
        thorUp[0] = 0f; thorUp[1] = cos(tt); thorUp[2] = sin(tt)
        thorAnt[0] = 0f; thorAnt[1] = -sin(tt); thorAnt[2] = cos(tt)

        V.set(pos, Joint.PELVIS, 0f, 0f, 0f)
        V.set(pos, Joint.SPINE, pelUp[0] * Anthro.LUMBAR, pelUp[1] * Anthro.LUMBAR, pelUp[2] * Anthro.LUMBAR)
        addAlong(Joint.CHEST, Joint.SPINE, thorUp, Anthro.THORACIC)
        addAlong(Joint.NECK, Joint.CHEST, thorUp, Anthro.CERVICAL)
        val n = Joint.NECK * 3
        V.set(
            pos, Joint.HEAD,
            pos[n] + thorUp[0] * Anthro.HEAD_UP + thorAnt[0] * 0.025f,
            pos[n + 1] + thorUp[1] * Anthro.HEAD_UP + thorAnt[1] * 0.025f,
            pos[n + 2] + thorUp[2] * Anthro.HEAD_UP + thorAnt[2] * 0.025f
        )

        val scapR = c[MotionClip.SCAP_R]
        val scapE = c[MotionClip.SCAP_E]
        for (s in 0..1) {
            val sg = Joint.sign(s)
            // Shoulder joints sit at the top of the pelvis-to-acromion line, where the research
            // geometry put them; the scapula hints slide them up/down (elevation) and
            // back/forward (retraction), which is most of what a row or pull-up looks like.
            val down = -0.03f * scapE
            val back = 0.025f * scapR
            val sh = Joint.shoulder(s)
            V.set(
                pos, sh,
                pos[n] + sg * Anthro.SHOULDER_HALF - thorUp[0] * down - thorAnt[0] * back,
                pos[n + 1] - thorUp[1] * down - thorAnt[1] * back,
                pos[n + 2] - thorUp[2] * down - thorAnt[2] * back
            )
            val ul = c[MotionClip.UPPER_ARM + s * 3]
            val ua = c[MotionClip.UPPER_ARM + s * 3 + 1]
            val uc = c[MotionClip.UPPER_ARM + s * 3 + 2]
            var ux = sg * ul + thorAnt[0] * ua - thorUp[0] * uc
            var uy = thorAnt[1] * ua - thorUp[1] * uc
            var uz = thorAnt[2] * ua - thorUp[2] * uc
            val ulen = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(1e-6f)
            ux /= ulen; uy /= ulen; uz /= ulen
            val sx = pos[sh * 3]; val sy = pos[sh * 3 + 1]; val sz = pos[sh * 3 + 2]
            elbowFk[s * 3] = sx + ux * Anthro.UPPER_ARM
            elbowFk[s * 3 + 1] = sy + uy * Anthro.UPPER_ARM
            elbowFk[s * 3 + 2] = sz + uz * Anthro.UPPER_ARM
            val hl = c[MotionClip.HAND + s * 3]
            val ha = c[MotionClip.HAND + s * 3 + 1]
            val hc = c[MotionClip.HAND + s * 3 + 2]
            handFk[s * 3] = sx + (sg * hl + thorAnt[0] * ha - thorUp[0] * hc) * Anthro.ARM
            handFk[s * 3 + 1] = sy + (thorAnt[1] * ha - thorUp[1] * hc) * Anthro.ARM
            handFk[s * 3 + 2] = sz + (thorAnt[2] * ha - thorUp[2] * hc) * Anthro.ARM
            V.set(pos, Joint.elbow(s), elbowFk[s * 3], elbowFk[s * 3 + 1], elbowFk[s * 3 + 2])
            V.set(pos, Joint.wrist(s), handFk[s * 3], handFk[s * 3 + 1], handFk[s * 3 + 2])

            legFk(s, sg, c)
        }
    }

    private fun legFk(s: Int, sg: Float, c: FloatArray) {
        val hip = Joint.hip(s)
        V.set(pos, hip, sg * Anthro.HIP_HALF, 0f, 0f)
        val ct = cos(turnout); val st = sin(turnout)
        // Toe direction: the pelvis's forward axis turned out about its long axis.
        val fx = pelAnt[0] * ct + sg * st
        val fy = pelAnt[1] * ct
        val fz = pelAnt[2] * ct
        legForward[s * 3] = fx; legForward[s * 3 + 1] = fy; legForward[s * 3 + 2] = fz
        // Flexion plane: spanned by pelvis-down and the toe direction (orthonormal).
        val dx0 = -pelUp[0]; val dy0 = -pelUp[1]; val dz0 = -pelUp[2]
        val h = rad(c[MotionClip.HIP + s])
        val k = rad(c[MotionClip.KNEE + s])
        val a = rad(c[MotionClip.ANKLE + s])
        val hs = h - k
        // Sagittal thigh and shank in the flexion plane.
        val t0x = dx0 * cos(h) + fx * sin(h); val t0y = dy0 * cos(h) + fy * sin(h); val t0z = dz0 * cos(h) + fz * sin(h)
        val s0x = dx0 * cos(hs) + fx * sin(hs); val s0y = dy0 * cos(hs) + fy * sin(hs); val s0z = dz0 * cos(hs) + fz * sin(hs)
        // Abduction turns the whole leg about the floating axis (thigh x outward normal), as in
        // the standard hip joint coordinate system: sideways for a standing leg, knees-apart for
        // a deeply flexed one, and never changing how high the hip sits above the feet when the
        // thigh is horizontal.
        val lx = (sg - st * fx) / ct; val ly = (-st * fy) / ct; val lz = (-st * fz) / ct
        var kx = t0y * lz - t0z * ly; var ky = t0z * lx - t0x * lz; var kz = t0x * ly - t0y * lx
        val kl = sqrt(kx * kx + ky * ky + kz * kz).coerceAtLeast(1e-6f); kx /= kl; ky /= kl; kz /= kl
        val ab = rad(c[MotionClip.ABD + s])
        rotate(t0x, t0y, t0z, kx, ky, kz, ab, tmp, 0)
        rotate(s0x, s0y, s0z, kx, ky, kz, ab, tmp, 3)

        val kn = Joint.knee(s)
        V.set(pos, kn, pos[hip * 3] + tmp[0] * Anthro.THIGH, pos[hip * 3 + 1] + tmp[1] * Anthro.THIGH, pos[hip * 3 + 2] + tmp[2] * Anthro.THIGH)
        val an = Joint.ankle(s)
        V.set(pos, an, pos[kn * 3] + tmp[3] * Anthro.SHANK, pos[kn * 3 + 1] + tmp[4] * Anthro.SHANK, pos[kn * 3 + 2] + tmp[5] * Anthro.SHANK)

        val pf = hs + HALF_PI + a
        val fdx = dx0 * cos(pf) + fx * sin(pf); val fdy = dy0 * cos(pf) + fy * sin(pf); val fdz = dz0 * cos(pf) + fz * sin(pf)
        val ps = pf - HALF_PI
        val sdx = dx0 * cos(ps) + fx * sin(ps); val sdy = dy0 * cos(ps) + fy * sin(ps); val sdz = dz0 * cos(ps) + fz * sin(ps)
        val ax = pos[an * 3]; val ay = pos[an * 3 + 1]; val az = pos[an * 3 + 2]
        val toeDrop = Anthro.ANKLE_HEIGHT - 0.022f
        val heelDrop = Anthro.ANKLE_HEIGHT - 0.018f
        V.set(pos, Joint.toe(s), ax + fdx * Anthro.FOOT_FORWARD + sdx * toeDrop, ay + fdy * Anthro.FOOT_FORWARD + sdy * toeDrop, az + fdz * Anthro.FOOT_FORWARD + sdz * toeDrop)
        V.set(pos, Joint.heel(s), ax - fdx * Anthro.HEEL_BACK + sdx * heelDrop, ay - fdy * Anthro.HEEL_BACK + sdy * heelDrop, az - fdz * Anthro.HEEL_BACK + sdz * heelDrop)
        val o = s * 6
        val sole = Anthro.ANKLE_HEIGHT
        soles[o] = ax + fdx * Anthro.FOOT_FORWARD + sdx * sole
        soles[o + 1] = ay + fdy * Anthro.FOOT_FORWARD + sdy * sole
        soles[o + 2] = az + fdz * Anthro.FOOT_FORWARD + sdz * sole
        soles[o + 3] = ax - fdx * Anthro.HEEL_BACK + sdx * sole
        soles[o + 4] = ay - fdy * Anthro.HEEL_BACK + sdy * sole
        soles[o + 5] = az - fdz * Anthro.HEEL_BACK + sdz * sole
    }

    // ------------------------------------------------------------------ contacts

    private fun solveLegs() {
        for (s in 0..1) {
            val mode = motion.legs[s]
            if (mode == LegContact.FREE) continue
            val an = Joint.ankle(s); val tn = Joint.toe(s); val hn = Joint.heel(s); val kn = Joint.knee(s)
            val o = s * 3
            val tx: Float; val ty: Float; val tz: Float
            if (mode == LegContact.FIXED) {
                tx = fixAnkle[o]; ty = fixAnkle[o + 1]; tz = fixAnkle[o + 2]
            } else {
                // Ball pivot: keep the ball of the foot planted and let the foot pitch as the
                // data says (calf raise, triple extension); the ankle follows the foot.
                footVec[o] = pos[tn * 3] - pos[an * 3]
                footVec[o + 1] = pos[tn * 3 + 1] - pos[an * 3 + 1]
                footVec[o + 2] = pos[tn * 3 + 2] - pos[an * 3 + 2]
                heelVec[o] = pos[hn * 3] - pos[an * 3]
                heelVec[o + 1] = pos[hn * 3 + 1] - pos[an * 3 + 1]
                heelVec[o + 2] = pos[hn * 3 + 2] - pos[an * 3 + 2]
                tx = fixToe[o] - footVec[o]; ty = fixToe[o + 1] - footVec[o + 1]; tz = fixToe[o + 2] - footVec[o + 2]
            }
            // Knees track the toes: the pole sits ahead of the FK knee along the foot direction.
            val px = pos[kn * 3] + legForward[o] * 0.5f
            val py = pos[kn * 3 + 1] + legForward[o + 1] * 0.5f
            val pz = pos[kn * 3 + 2] + legForward[o + 2] * 0.5f
            TwoBoneIk.solve(pos, Joint.hip(s), kn, an, tx, ty, tz, px, py, pz, Anthro.THIGH, Anthro.SHANK)
            val ax = pos[an * 3]; val ay = pos[an * 3 + 1]; val az = pos[an * 3 + 2]
            if (mode == LegContact.FIXED) {
                V.set(pos, tn, ax + fixToe[o] - fixAnkle[o], ay + fixToe[o + 1] - fixAnkle[o + 1], az + fixToe[o + 2] - fixAnkle[o + 2])
                V.set(pos, hn, ax + fixHeel[o] - fixAnkle[o], ay + fixHeel[o + 1] - fixAnkle[o + 1], az + fixHeel[o + 2] - fixAnkle[o + 2])
            } else {
                V.set(pos, tn, ax + footVec[o], ay + footVec[o + 1], az + footVec[o + 2])
                V.set(pos, hn, ax + heelVec[o], ay + heelVec[o + 1], az + heelVec[o + 2])
            }
        }
    }

    private fun solveArms() {
        gripHalf = ch[MotionClip.GRIP] * Anthro.BIACROMIAL * 0.5f
        val midX = pos[Joint.PELVIS * 3]
        val avgY = (handFk[1] + handFk[4]) * 0.5f
        val avgZ = (handFk[2] + handFk[5]) * 0.5f
        var cx = 0f; var cy = 0f; var cz = 0f
        if (motion.arms == ArmMode.BAR_BODY) {
            val nk = Joint.NECK * 3
            val pv = Joint.PELVIS * 3
            when (motion.barMount ?: BarMount.UPPER_TRAPS) {
                BarMount.UPPER_TRAPS -> { cx = pos[nk] - thorAnt[0] * 0.075f - thorUp[0] * 0.01f; cy = pos[nk + 1] - thorAnt[1] * 0.075f - thorUp[1] * 0.01f; cz = pos[nk + 2] - thorAnt[2] * 0.075f - thorUp[2] * 0.01f }
                BarMount.REAR_DELTS -> { cx = pos[nk] - thorAnt[0] * 0.085f - thorUp[0] * 0.065f; cy = pos[nk + 1] - thorAnt[1] * 0.085f - thorUp[1] * 0.065f; cz = pos[nk + 2] - thorAnt[2] * 0.085f - thorUp[2] * 0.065f }
                BarMount.FRONT_DELTS -> { cx = pos[nk] + thorAnt[0] * 0.09f - thorUp[0] * 0.035f; cy = pos[nk + 1] + thorAnt[1] * 0.09f - thorUp[1] * 0.035f; cz = pos[nk + 2] + thorAnt[2] * 0.09f - thorUp[2] * 0.035f }
                BarMount.HIPS -> { cx = pos[pv] + pelAnt[0] * 0.15f + pelUp[0] * 0.02f; cy = pos[pv + 1] + pelAnt[1] * 0.15f + pelUp[1] * 0.02f; cz = pos[pv + 2] + pelAnt[2] * 0.15f + pelUp[2] * 0.02f }
            }
        }
        val floorFor = when {
            MotionProp.BARBELL in motion.props -> PLATE_RADIUS
            MotionProp.EZ_BAR in motion.props -> EZ_PLATE_RADIUS
            else -> HAND_FLOOR
        }
        for (s in 0..1) {
            val sg = Joint.sign(s)
            val o = s * 3
            var tx = handFk[o]; var ty = handFk[o + 1]; var tz = handFk[o + 2]
            when (motion.arms) {
                ArmMode.BAR -> { tx = midX + sg * gripHalf; ty = avgY; tz = avgZ }
                ArmMode.BAR_BODY -> { tx = cx + sg * gripHalf; ty = cy; tz = cz }
                ArmMode.WORLD, ArmMode.WORLD_FLOOR -> { tx = fixedHands[o]; ty = fixedHands[o + 1]; tz = fixedHands[o + 2] }
                ArmMode.WHEEL -> { tx = midX + sg * gripHalf; ty = WHEEL_AXLE; tz = avgZ }
                ArmMode.TOGETHER -> { tx = (handFk[0] + handFk[3]) * 0.5f + sg * 0.075f; ty = avgY; tz = avgZ }
                ArmMode.CABLE_LATERAL -> if (s == 0) { tx = cableLateralHold[0]; ty = cableLateralHold[1]; tz = cableLateralHold[2] }
                ArmMode.FREE, ArmMode.TRUNK, ArmMode.DB_ROW -> Unit
            }
            // Hands never go through the floor, and a loaded bar never sinks below its plates.
            ty = max(ty, floorFor)
            val sh = Joint.shoulder(s)
            // Pole: push the FK elbow further off the shoulder-hand chord so the IK elbow bends
            // the way the data's elbow points; a straight FK arm falls back to elbows-back.
            val mx = (pos[sh * 3] + tx) * 0.5f; val my = (pos[sh * 3 + 1] + ty) * 0.5f; val mz = (pos[sh * 3 + 2] + tz) * 0.5f
            val px = elbowFk[o] + (elbowFk[o] - mx) * 2f - thorAnt[0] * 0.05f
            val py = elbowFk[o + 1] + (elbowFk[o + 1] - my) * 2f - thorAnt[1] * 0.05f
            val pz = elbowFk[o + 2] + (elbowFk[o + 2] - mz) * 2f - thorAnt[2] * 0.05f
            TwoBoneIk.solve(pos, sh, Joint.elbow(s), Joint.wrist(s), tx, ty, tz, px, py, pz, Anthro.UPPER_ARM, Anthro.FOREARM)
        }
    }

    private fun updateBar() {
        val l = Joint.WRIST_L * 3; val r = Joint.WRIST_R * 3
        barCenter[0] = (pos[l] + pos[r]) * 0.5f
        barCenter[1] = (pos[l + 1] + pos[r + 1]) * 0.5f
        barCenter[2] = (pos[l + 2] + pos[r + 2]) * 0.5f
        barAxis[0] = pos[l] - pos[r]; barAxis[1] = pos[l + 1] - pos[r + 1]; barAxis[2] = pos[l + 2] - pos[r + 2]
        val len = sqrt(barAxis[0] * barAxis[0] + barAxis[1] * barAxis[1] + barAxis[2] * barAxis[2])
        if (len < 0.05f) { barAxis[0] = 1f; barAxis[1] = 0f; barAxis[2] = 0f } else { barAxis[0] /= len; barAxis[1] /= len; barAxis[2] /= len }
    }

    // ------------------------------------------------------------------ reference pose

    private fun reference() {
        val m = motion
        var clearance = 0f
        var handNeed = 0f
        // Pre-pass over the rep for anchors that need the whole motion (bar height, seat height).
        for (i in 0..SAMPLES) {
            clip.sample(i / SAMPLES.toFloat(), ch)
            fk()
            val low = lowestPoint()
            clearance = max(clearance, -low + 0.05f)
            handNeed = max(handNeed, (handFk[1] + handFk[4]) * 0.5f - low)
        }

        clip.sample(0f, ch)
        fk()
        val ox = 0f
        var oy = 0f
        val oz = 0f
        val anyFixed = m.legs[0] == LegContact.FIXED || m.legs[1] == LegContact.FIXED
        when (m.anchor) {
            MotionAnchor.FEET -> {
                oy = -lowestSole(if (anchorLeg(0)) 0 else 1, if (anchorLeg(1)) 1 else 0)
                // Feet planted at a steep angle stand on a raised, tilted platform (hack squat,
                // back extension); lift everything so that platform clears the floor.
                val tilt = abs(soles[1] - soles[4]) / (Anthro.FOOT_FORWARD + Anthro.HEEL_BACK)
                if (tilt > 0.26f) oy += 0.24f * tilt + 0.04f
            }
            MotionAnchor.LEAD_FOOT -> oy = -lowestSole(1, 1) + (m.boxHeight ?: 0f)
            MotionAnchor.BALLS -> oy = -min(soles[1], soles[7]) + (m.blockHeight ?: 0f)
            MotionAnchor.KNEES -> oy = -min(pos[Joint.KNEE_L * 3 + 1], pos[Joint.KNEE_R * 3 + 1]) + 0.09f
            MotionAnchor.TOES -> oy = -min(pos[Joint.TOE_L * 3 + 1], pos[Joint.TOE_R * 3 + 1]) + 0.025f
            MotionAnchor.PELVIS -> oy = when {
                m.seatHeight != null -> max(m.seatHeight, clearance)
                anyFixed -> -lowestSole(if (m.legs[0] == LegContact.FIXED) 0 else 1, if (m.legs[1] == LegContact.FIXED) 1 else 0)
                m.arms == ArmMode.DB_ROW -> -lowestSole(1, 1)
                else -> max(0.6f, clearance)
            }
            MotionAnchor.UPPER_BACK -> oy = -lowestSole(0, 1)
            MotionAnchor.HANDS -> oy = handNeed + 0.12f - (handFk[1] + handFk[4]) * 0.5f
            MotionAnchor.GAIT -> oy = -lowestSole(0, 1)
        }
        translate(ox, oy, oz)

        // Capture the contacts at the start of the rep.
        for (s in 0..1) {
            val o = s * 3
            V.get(pos, Joint.ankle(s), fixAnkle, o)
            V.get(pos, Joint.toe(s), fixToe, o)
            V.get(pos, Joint.heel(s), fixHeel, o)
            V.get(pos, Joint.knee(s), fixKnee, o)
        }
        // Stance width comes straight from the data (heel centres, in shoulder widths) rather
        // than from where the abducted FK happened to put the feet.
        val stance = m.stanceWidth
        val symmetricFeet = m.legs[0] == m.legs[1] && m.legs[0] != LegContact.FREE
        if (stance != null && symmetricFeet && m.anchor != MotionAnchor.LEAD_FOOT) {
            for (s in 0..1) {
                val o = s * 3
                val dx = Joint.sign(s) * stance * Anthro.BIACROMIAL * 0.5f - fixAnkle[o]
                fixAnkle[o] += dx; fixToe[o] += dx; fixHeel[o] += dx
            }
        }
        V.get(pos, Joint.CHEST, fixChest)
        fixPelvis[0] = pos[0]; fixPelvis[1] = pos[1]; fixPelvis[2] = pos[2]
        fixHandMid[0] = (handFk[0] + handFk[3]) * 0.5f
        fixHandMid[1] = (handFk[1] + handFk[4]) * 0.5f
        fixHandMid[2] = (handFk[2] + handFk[5]) * 0.5f
        val grip = m.gripWidth
        for (s in 0..1) {
            val o = s * 3
            fixedHands[o] = if (grip != null) fixHandMid[0] + Joint.sign(s) * grip * Anthro.BIACROMIAL * 0.5f else handFk[o]
            fixedHands[o + 1] = if (m.arms == ArmMode.WORLD_FLOOR) HAND_FLOOR else fixHandMid[1]
            fixedHands[o + 2] = if (m.arms == ArmMode.WORLD_FLOOR) handFk[o + 2] else fixHandMid[2]
        }
        m.cable?.pulleys?.firstOrNull()?.let { p ->
            // The free hand of a one-arm cable raise holds the machine frame beside the pulley.
            cableLateralHold = floatArrayOf(p[0] - 0.1f, 1.0f, p[2])
        }

        solve(0f)
        pos.copyInto(refPos)
        thorUp.copyInto(refThorUp); thorAnt.copyInto(refThorAnt)
        pelUp.copyInto(refPelUp); pelAnt.copyInto(refPelAnt)
        soles.copyInto(refSoles)
    }

    private fun computeBounds() {
        bounds[0] = Float.MAX_VALUE; bounds[1] = Float.MAX_VALUE; bounds[2] = Float.MAX_VALUE
        bounds[3] = -Float.MAX_VALUE; bounds[4] = -Float.MAX_VALUE; bounds[5] = -Float.MAX_VALUE
        val passes = if (motion.mirrorAlternate) 2 else 1
        for (p in 0 until passes) for (i in 0..SAMPLES) {
            solve(i / SAMPLES.toFloat(), mirrored = p == 1)
            for (j in 0 until Joint.COUNT) include(pos[j * 3], pos[j * 3 + 1], pos[j * 3 + 2])
        }
        include(bounds[0], 0f, bounds[2])
        solve(0f)
    }

    fun include(x: Float, y: Float, z: Float) {
        if (x < bounds[0]) bounds[0] = x
        if (y < bounds[1]) bounds[1] = y
        if (z < bounds[2]) bounds[2] = z
        if (x > bounds[3]) bounds[3] = x
        if (y > bounds[4]) bounds[4] = y
        if (z > bounds[5]) bounds[5] = z
    }

    // ------------------------------------------------------------------ helpers

    private fun anchorLeg(s: Int): Boolean = when (motion.anchor) {
        MotionAnchor.LEAD_FOOT -> s == 1
        else -> motion.legs[s] == LegContact.FIXED
    }

    private fun lowestSole(a: Int, b: Int): Float =
        min(min(soles[a * 6 + 1], soles[a * 6 + 4]), min(soles[b * 6 + 1], soles[b * 6 + 4]))

    private fun lowestPoint(): Float {
        var low = Float.MAX_VALUE
        for (j in 0 until Joint.COUNT) low = min(low, pos[j * 3 + 1])
        return min(low, lowestSole(0, 1))
    }

    private fun translate(x: Float, y: Float, z: Float) {
        for (j in 0 until Joint.COUNT) {
            pos[j * 3] += x; pos[j * 3 + 1] += y; pos[j * 3 + 2] += z
        }
        for (i in 0..1) {
            handFk[i * 3] += x; handFk[i * 3 + 1] += y; handFk[i * 3 + 2] += z
            elbowFk[i * 3] += x; elbowFk[i * 3 + 1] += y; elbowFk[i * 3 + 2] += z
        }
        for (i in 0..3) {
            soles[i * 3] += x; soles[i * 3 + 1] += y; soles[i * 3 + 2] += z
        }
    }

    private fun addAlong(dst: Int, from: Int, dir: FloatArray, len: Float) {
        V.set(pos, dst, pos[from * 3] + dir[0] * len, pos[from * 3 + 1] + dir[1] * len, pos[from * 3 + 2] + dir[2] * len)
    }

    private fun swapSides() {
        fun sw(a: Int, b: Int) { val t = ch[a]; ch[a] = ch[b]; ch[b] = t }
        sw(MotionClip.HIP, MotionClip.HIP + 1)
        sw(MotionClip.KNEE, MotionClip.KNEE + 1)
        sw(MotionClip.ANKLE, MotionClip.ANKLE + 1)
        sw(MotionClip.ABD, MotionClip.ABD + 1)
        for (a in 0..2) {
            sw(MotionClip.UPPER_ARM + a, MotionClip.UPPER_ARM + 3 + a)
            sw(MotionClip.HAND + a, MotionClip.HAND + 3 + a)
        }
    }

    private fun rad(deg: Float): Float = deg * DEG

    /** Rodrigues rotation of v about the unit axis k by [angle]; result into out[o..o+2]. */
    private fun rotate(vx: Float, vy: Float, vz: Float, kx: Float, ky: Float, kz: Float, angle: Float, out: FloatArray, o: Int) {
        val c = cos(angle); val sn = sin(angle)
        val kdv = kx * vx + ky * vy + kz * vz
        val cx = ky * vz - kz * vy; val cy = kz * vx - kx * vz; val cz = kx * vy - ky * vx
        out[o] = vx * c + cx * sn + kx * kdv * (1f - c)
        out[o + 1] = vy * c + cy * sn + ky * kdv * (1f - c)
        out[o + 2] = vz * c + cz * sn + kz * kdv * (1f - c)
    }

    companion object {
        private const val DEG = (Math.PI / 180.0).toFloat()
        private const val HALF_PI = (Math.PI / 2.0).toFloat()
        private const val SAMPLES = 32
        /** Ab wheel handle (axle) height above the floor. */
        const val WHEEL_AXLE = 0.09f
        /** Grip centre height when the palm is flat on the floor. */
        const val HAND_FLOOR = 0.035f
        /** 20 kg / 45 lb plates are 450 mm across, so a loaded bar centre rests 225 mm up. */
        const val PLATE_RADIUS = 0.225f
        const val EZ_PLATE_RADIUS = 0.15f
    }
}
