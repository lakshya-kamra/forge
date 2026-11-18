package com.laksh.forge.domain.exercise

/*
 * Normalised motion data for the 3D demonstrations. The three research files (lower, push,
 * pull) each use their own angle dialect; the asset build folds them into this one schema so
 * the runtime solver only has to interpolate numbers and run FK/IK.
 *
 * Coordinate conventions shared with ui/skeleton:
 *  - trunk pitch is the pelvis-to-neck line's lean from vertical in degrees, + forward
 *    (supine = -90, prone = +90).
 *  - leg angles are sagittal joint angles (0 = anatomical standing), abduction per side.
 *  - upper-arm vectors and hand positions are in the trunk frame [lateral, anterior, caudal];
 *    hand positions are in units of L (straight-arm shoulder-to-grip length).
 */

/** How the root (pelvis) is placed in the world each frame. */
enum class MotionAnchor { FEET, LEAD_FOOT, BALLS, KNEES, TOES, PELVIS, UPPER_BACK, HANDS, GAIT }

/** What keeps a foot in place: a flat planted foot, a pivot on the ball of the foot, or nothing. */
enum class LegContact { FIXED, BALL, FREE }

/** Where the hands go once the arm FK has run. */
enum class ArmMode {
    FREE, TRUNK, BAR, BAR_BODY, WORLD, WORLD_FLOOR, WHEEL, TOGETHER, DB_ROW, CABLE_LATERAL
}

/** Where a barbell rests when it is carried on the body rather than held out in the hands. */
enum class BarMount { UPPER_TRAPS, REAR_DELTS, FRONT_DELTS, HIPS }

enum class DumbbellAxis { LATERAL, SAGITTAL }

enum class TempoKind { REPS, ISOMETRIC, GAIT }

enum class PhaseKind { CONCENTRIC, ECCENTRIC, HOLD, MOVE }

/** Equipment drawn alongside the skeleton. */
enum class MotionProp {
    BARBELL, EZ_BAR, DUMBBELLS, DUMBBELL_RIGHT, DUMBBELL_GOBLET,
    BENCH_FLAT, BENCH_INCLINE, BENCH_UPRIGHT, BENCH_PRONE_INCLINE, BENCH_REAR_FOOT, BENCH_THRUST,
    BENCH_DB_ROW, BOX, PULLUP_BAR, DIP_BARS, CABLE,
    LEG_PRESS, HACK_SQUAT, LEG_EXTENSION, LEG_CURL_LYING, LEG_CURL_SEATED, CALF_STANDING,
    CALF_SEATED, ADDUCTION, CHEST_PRESS, PEC_DECK, REAR_DELT, LAT_PULLDOWN, SEATED_ROW,
    PREACHER, HYPEREXTENSION, NORDIC_PAD, AB_WHEEL
}

enum class CableHandle { ROPE, SINGLE, BAR, LAT_BAR, V }

/** Which hand(s) a cable runs to. */
enum class CableAttach { MID, EACH, RIGHT }

/**
 * Pulley positions are metres relative to the lifter's starting pelvis position on the floor
 * plane: x lateral (+ = lifter's left), y height above the floor, z forward.
 */
class CableSpec(
    val pulleys: List<FloatArray>,
    val attach: CableAttach,
    val handle: CableHandle
)

class MotionPhase(
    val kind: PhaseKind,
    /** Normalised rep time 0..1. */
    val start: Float,
    val end: Float,
    val seconds: Double
)

/**
 * One pose. Per-side arrays are [left, right]; vector arrays are left xyz then right xyz.
 * Plain arrays (not lists) because the solver reads these thousands of times per second.
 */
class MotionKeyframe(
    val t: Float,
    val trunk: Float,
    val spine: Float,
    val hip: FloatArray,
    val knee: FloatArray,
    val ankle: FloatArray,
    val abduction: FloatArray,
    val upperArm: FloatArray,
    val hand: FloatArray,
    val scapRetraction: Float,
    val scapElevation: Float,
    /** Grip width in shoulder widths for this frame, or NaN when the exercise-level value applies. */
    val grip: Float,
    val label: String
)

class ExerciseMotion(
    val orientation: String,
    val implement: String,
    val anchor: MotionAnchor,
    val legs: Array<LegContact>,
    val arms: ArmMode,
    val barMount: BarMount?,
    val props: Set<MotionProp>,
    val cable: CableSpec?,
    val dumbbellAxis: DumbbellAxis,
    val farmerHandles: Boolean,
    val mirrorAlternate: Boolean,
    /** Heel-centre stance width in shoulder widths, when the source gives one. */
    val stanceWidth: Float?,
    /** Grip width in shoulder widths, when the source gives one. */
    val gripWidth: Float?,
    val turnoutDeg: Float,
    val seatHeight: Float?,
    val boxHeight: Float?,
    val blockHeight: Float?,
    val viewYaw: Float,
    val viewPitch: Float,
    val durationS: Float,
    val tempoKind: TempoKind,
    val tempoNote: String,
    val phases: List<MotionPhase>,
    val keyframes: List<MotionKeyframe>
) {
    /** The pose most worth showing in a still: the end of the first movement phase. */
    val keyPoseT: Float
        get() = phases.firstOrNull { it.kind == PhaseKind.CONCENTRIC || it.kind == PhaseKind.ECCENTRIC }
            ?.end ?: 0.5f
}

data class SourceRef(val cite: String, val url: String?)

data class ImageSource(
    val dataset: String,
    val entryId: String?,
    val match: String?,
    val note: String
)

/** Extra per-exercise detail that the contract [ExerciseInfo] has no room for. */
data class ExerciseExtras(
    val motionSources: List<SourceRef>,
    val angleBasis: String?,
    val imageSource: ImageSource?
)

/** A creator whose channel can be searched for technique videos. */
data class Creator(
    val name: String,
    val channel: String,
    val channelUrl: String,
    /** GVS tier (S/A/B/C/D) when GVS ranked them, else null. */
    val tier: String?
) {
    fun searchUrl(query: String): String =
        "$channelUrl/search?query=" + java.net.URLEncoder.encode(query, "UTF-8")
}

data class CatalogLicence(
    val images: String,
    val imagesUrl: String,
    val videos: String,
    val gvs: String,
    val animation: String
)
