package com.laksh.forge.ui.skeleton

import kotlin.math.sqrt

/**
 * Joint indices into the flat position arrays (x, y, z per joint).
 *
 * World frame: metres, y up, the lifter faces +z when standing, +x is the lifter's LEFT
 * (so that x, y, z is right-handed with the lifter facing +z). The floor is y = 0.
 */
object Joint {
    const val PELVIS = 0
    const val SPINE = 1
    const val CHEST = 2
    const val NECK = 3
    const val HEAD = 4
    const val SHOULDER_L = 5
    const val SHOULDER_R = 6
    const val ELBOW_L = 7
    const val ELBOW_R = 8
    const val WRIST_L = 9
    const val WRIST_R = 10
    const val HIP_L = 11
    const val HIP_R = 12
    const val KNEE_L = 13
    const val KNEE_R = 14
    const val ANKLE_L = 15
    const val ANKLE_R = 16
    const val TOE_L = 17
    const val TOE_R = 18
    const val HEEL_L = 19
    const val HEEL_R = 20
    const val COUNT = 21

    fun shoulder(side: Int) = SHOULDER_L + side
    fun elbow(side: Int) = ELBOW_L + side
    fun wrist(side: Int) = WRIST_L + side
    fun hip(side: Int) = HIP_L + side
    fun knee(side: Int) = KNEE_L + side
    fun ankle(side: Int) = ANKLE_L + side
    fun toe(side: Int) = TOE_L + side
    fun heel(side: Int) = HEEL_L + side

    /** +1 for the left side (+x), -1 for the right. Sides are indexed 0 = left, 1 = right. */
    fun sign(side: Int): Float = if (side == 0) 1f else -1f
}

/**
 * Segment lengths for a 1.75 m adult from standard anthropometric fractions of height
 * (Winter's table), the same proportions the motion research used for its geometry checks:
 * shank 0.246 H, thigh 0.245 H, hip-to-acromion 0.288 H, shoulder-to-grip 0.392 H, ankle
 * height 0.039 H. Using the same skeleton keeps the verified contacts (bar over midfoot,
 * hands reaching a 0.225 m bar centre) true in the animation.
 */
object Anthro {
    const val HEIGHT = 1.75f
    const val SHANK = 0.246f * HEIGHT
    const val THIGH = 0.245f * HEIGHT
    const val TRUNK = 0.288f * HEIGHT
    /** L: straight-arm shoulder-joint-to-grip length. Hand targets in the data are in units of L. */
    const val ARM = 0.392f * HEIGHT
    const val UPPER_ARM = ARM * 0.5f
    const val FOREARM = ARM * 0.5f
    const val ANKLE_HEIGHT = 0.039f * HEIGHT

    // Trunk split into lumbar, thoracic and cervical pieces whose chord is the pelvis-to-neck line.
    const val LUMBAR = TRUNK * 0.40f
    const val THORACIC = TRUNK * 0.45f
    const val CERVICAL = TRUNK * 0.15f

    /** Shoulder-joint-centre separation is 0.55 L in the reference skeleton. */
    const val SHOULDER_HALF = ARM * 0.55f * 0.5f
    const val HIP_HALF = 0.09f
    /** Biacromial width: grip and stance widths in the data are multiples of this. */
    const val BIACROMIAL = 0.42f

    const val HEAD_UP = 0.135f
    const val HEAD_RADIUS = 0.098f
    const val FOOT_FORWARD = 0.14f
    const val HEEL_BACK = 0.05f

    // Drawn radii (slightly exaggerated so bones read on a phone screen).
    const val R_FEMUR = 0.026f
    const val R_TIBIA = 0.022f
    const val R_HUMERUS = 0.021f
    const val R_FOREARM = 0.017f
    const val R_SPINE = 0.024f
    const val R_CLAVICLE = 0.014f
    const val R_FOOT = 0.014f
    const val R_JOINT = 0.034f
}

/** The drawn bones: pairs of joints with a capsule radius. */
object Bones {
    val A = intArrayOf(
        Joint.PELVIS, Joint.SPINE, Joint.CHEST, Joint.NECK,
        Joint.NECK, Joint.NECK,
        Joint.SHOULDER_L, Joint.SHOULDER_R, Joint.ELBOW_L, Joint.ELBOW_R,
        Joint.PELVIS, Joint.PELVIS,
        Joint.HIP_L, Joint.HIP_R, Joint.KNEE_L, Joint.KNEE_R,
        Joint.ANKLE_L, Joint.ANKLE_R, Joint.ANKLE_L, Joint.ANKLE_R, Joint.HEEL_L, Joint.HEEL_R
    )
    val B = intArrayOf(
        Joint.SPINE, Joint.CHEST, Joint.NECK, Joint.HEAD,
        Joint.SHOULDER_L, Joint.SHOULDER_R,
        Joint.ELBOW_L, Joint.ELBOW_R, Joint.WRIST_L, Joint.WRIST_R,
        Joint.HIP_L, Joint.HIP_R,
        Joint.KNEE_L, Joint.KNEE_R, Joint.ANKLE_L, Joint.ANKLE_R,
        Joint.TOE_L, Joint.TOE_R, Joint.HEEL_L, Joint.HEEL_R, Joint.TOE_L, Joint.TOE_R
    )
    val RADIUS = floatArrayOf(
        Anthro.R_SPINE, Anthro.R_SPINE, Anthro.R_SPINE * 0.8f, Anthro.R_SPINE * 0.7f,
        Anthro.R_CLAVICLE, Anthro.R_CLAVICLE,
        Anthro.R_HUMERUS, Anthro.R_HUMERUS, Anthro.R_FOREARM, Anthro.R_FOREARM,
        Anthro.R_SPINE * 0.9f, Anthro.R_SPINE * 0.9f,
        Anthro.R_FEMUR, Anthro.R_FEMUR, Anthro.R_TIBIA, Anthro.R_TIBIA,
        Anthro.R_FOOT, Anthro.R_FOOT, Anthro.R_FOOT, Anthro.R_FOOT, Anthro.R_FOOT * 0.8f, Anthro.R_FOOT * 0.8f
    )
    val COUNT = A.size

    // Bone indices referenced by the muscle overlay.
    const val LUMBAR = 0
    const val THORACIC = 1
    const val CLAVICLE_L = 4
    const val UPPER_ARM_L = 6
    const val FOREARM_L = 8
    const val THIGH_L = 12
    const val SHANK_L = 14
}

/** Joints drawn as spheres (the rest are hidden inside the capsules). */
object JointDraw {
    val JOINTS = intArrayOf(
        Joint.SHOULDER_L, Joint.SHOULDER_R, Joint.ELBOW_L, Joint.ELBOW_R, Joint.WRIST_L, Joint.WRIST_R,
        Joint.HIP_L, Joint.HIP_R, Joint.KNEE_L, Joint.KNEE_R, Joint.ANKLE_L, Joint.ANKLE_R,
        Joint.SPINE, Joint.CHEST, Joint.NECK, Joint.PELVIS
    )
    val RADIUS = floatArrayOf(
        Anthro.R_JOINT, Anthro.R_JOINT, Anthro.R_JOINT * 0.8f, Anthro.R_JOINT * 0.8f, Anthro.R_JOINT * 0.65f, Anthro.R_JOINT * 0.65f,
        Anthro.R_JOINT * 1.05f, Anthro.R_JOINT * 1.05f, Anthro.R_JOINT * 0.95f, Anthro.R_JOINT * 0.95f, Anthro.R_JOINT * 0.7f, Anthro.R_JOINT * 0.7f,
        Anthro.R_JOINT * 0.7f, Anthro.R_JOINT * 0.7f, Anthro.R_JOINT * 0.6f, Anthro.R_JOINT * 0.9f
    )
}

/** Tiny allocation-free vector helpers over flat float arrays. */
internal object V {
    fun get(a: FloatArray, i: Int, out: FloatArray, o: Int = 0) {
        out[o] = a[i * 3]; out[o + 1] = a[i * 3 + 1]; out[o + 2] = a[i * 3 + 2]
    }

    fun set(a: FloatArray, i: Int, x: Float, y: Float, z: Float) {
        a[i * 3] = x; a[i * 3 + 1] = y; a[i * 3 + 2] = z
    }

    fun dist(a: FloatArray, i: Int, j: Int): Float {
        val dx = a[i * 3] - a[j * 3]
        val dy = a[i * 3 + 1] - a[j * 3 + 1]
        val dz = a[i * 3 + 2] - a[j * 3 + 2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Normalises the 3-vector at [o] in place; leaves a zero vector untouched. */
    fun normalize(v: FloatArray, o: Int = 0) {
        val l = sqrt(v[o] * v[o] + v[o + 1] * v[o + 1] + v[o + 2] * v[o + 2])
        if (l > 1e-9f) {
            v[o] /= l; v[o + 1] /= l; v[o + 2] /= l
        }
    }
}
