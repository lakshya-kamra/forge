package com.laksh.forge.domain.plan

import com.laksh.forge.data.prefs.GoalMode
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.prefs.TrainingAge
import com.laksh.forge.domain.guardrails.Phases

// Plain-language copy and enums shared by onboarding, the plan screens and progress.
// Every factual sentence here is taken from docs/research/onboarding_science.json or
// docs/research/guardrails.final.json - nothing is paraphrased into a stronger claim.

/** What the person mainly wants from training. Drives the rules in [PhaseRules]. */
enum class TrainingGoal(val label: String, val blurb: String) {
    LOOK("Change how I look", "Build muscle and/or lose body fat"),
    STRENGTH("Get stronger", "Lift more on the main lifts"),
    HEALTH("Health", "Feel better, move well, stay consistent"),
    PERFORMANCE("Performance", "A sport, an event or a performance block")
}

/** The tie-break the person gives when more than one phase is reasonable. */
enum class PhasePreference(val label: String, val blurb: String) {
    LOSE_FAT("Losing fat first", "If both are reasonable, start by leaning out"),
    BUILD_MUSCLE("Building muscle first", "If both are reasonable, start by gaining"),
    STAY_LEAN("Staying lean year-round", "Slower gains in exchange for staying lean"),
    NO_PREFERENCE("No preference", "Let Forge decide from the evidence")
}

enum class Equipment(val label: String) {
    FULL_GYM("Full gym"),
    HOME_WEIGHTS("Home - dumbbells or a rack"),
    MINIMAL("Bodyweight / bands only")
}

/** Progress-photo poses. Stored verbatim in ProgressPhoto.pose. */
enum class Pose(val label: String, val instruction: String) {
    FRONT("Front", "Face the camera, arms in a slight 'A' away from the torso, relaxed, breathing normally."),
    SIDE("Side", "Turn to the same side every time, arms relaxed, no sucking in and no flexing."),
    BACK("Back", "Face away from the camera, arms in a slight 'A', relaxed.");

    companion object {
        fun from(raw: String): Pose? = entries.firstOrNull { it.name == raw }
    }
}

/** Display names and one-line descriptions for every phase id in [Phases]. */
object PhaseCopy {
    fun name(phase: String): String = when (phase) {
        Phases.CUT -> "Cut"
        Phases.LEAN_GAIN -> "Lean gain"
        Phases.MAINGAIN -> "Maingain"
        Phases.MAINTAIN -> "Maintain"
        Phases.RECOMP -> "Recomp"
        Phases.REVERSE_DIET -> "Reverse diet"
        Phases.DIET_BREAK -> "Diet break"
        else -> phase.lowercase().replaceFirstChar { it.uppercase() }
    }

    /** Definitions from onboarding_science.json "phases". */
    fun definition(phase: String): String = when (phase) {
        Phases.CUT -> "A sustained energy deficit to reduce body fat while keeping muscle with resistance training and high protein."
        Phases.LEAN_GAIN -> "A deliberate small energy surplus to build muscle while limiting fat gain."
        Phases.MAINGAIN -> "Eating at or slightly above maintenance to gain very slowly while staying lean, accepting slower muscle gain."
        Phases.MAINTAIN -> "Energy balance: your weekly-average weight stays stable while training does the work."
        Phases.RECOMP -> "Losing fat and building muscle at the same time, with little change in scale weight."
        Phases.DIET_BREAK -> "A planned 1-2 week return to maintenance during a long cut."
        Phases.REVERSE_DIET -> "A gradual, stepwise calorie increase after a cut until maintenance is reached."
        else -> ""
    }

    /** Is this phase an energy deficit? Used to apply deficit-specific macro floors. */
    fun isDeficit(phase: String): Boolean = phase == Phases.CUT

    fun isSurplus(phase: String): Boolean = phase == Phases.LEAN_GAIN || phase == Phases.MAINGAIN

    /** The legacy single-goal setting that non-plan screens still read. */
    fun goalFor(phase: String): GoalMode = when (phase) {
        Phases.CUT -> GoalMode.CUT
        Phases.LEAN_GAIN -> GoalMode.LEAN_GAIN
        Phases.MAINGAIN -> GoalMode.MAINGAIN
        Phases.RECOMP -> GoalMode.RECOMP
        else -> GoalMode.MAINTAIN
    }

    /** ProgramLibrary levels. An unknown training age is treated as a beginner, the safer start. */
    fun level(trainingAge: TrainingAge): String = when (trainingAge) {
        TrainingAge.INTERMEDIATE -> "INTERMEDIATE"
        TrainingAge.ADVANCED -> "ADVANCED"
        else -> "BEGINNER"
    }
}

/** One tape-measure site with the protocol text from onboarding_science.json "measurements". */
data class MeasurementGuide(val title: String, val howTo: String)

object MeasurementGuides {
    val height = MeasurementGuide(
        "Height",
        "Barefoot, heels together against a wall, head level looking straight ahead; record to 0.5 cm."
    )
    val neck = MeasurementGuide(
        "Neck",
        "Bare skin, just below the larynx (Adam's apple), tape perpendicular to the long axis of the neck - " +
            "front of the tape at the same height as the back as far as possible. Look straight ahead, shoulders " +
            "down and relaxed; do not include the trapezius."
    )
    val waistNavel = MeasurementGuide(
        "Waist (at the navel)",
        "Bare skin, horizontally across the navel, arms at your sides, tape parallel to the floor and snug " +
            "without compressing the skin. Read it at the end of a normal relaxed breath out - take it after " +
            "several breaths and don't suck in."
    )
    val waistNarrowest = MeasurementGuide(
        "Waist (narrowest point)",
        "Bare skin at the narrowest point of your abdomen, usually about halfway between the navel and the " +
            "bottom of the breastbone. If it isn't obvious, measure a few likely spots and take the smallest. " +
            "Tape level, arms at your sides, at the end of a normal breath out."
    )
    val waistHealth = MeasurementGuide(
        "Health waist (optional)",
        "Midway between the lowest rib you can feel and the top of the hip bone, at the end of a normal breath " +
            "out, arms relaxed. This is the site used for health cut-offs; it is kept separate from your " +
            "tracking waist so the two are never mixed."
    )
    val hips = MeasurementGuide(
        "Hips",
        "Over thin, form-fitting shorts (no shaping garments), standing relaxed with feet together, tape around " +
            "the widest part of the buttocks as seen from the side, level with the floor, firm enough to " +
            "minimise the effect of clothing."
    )
    val chest = MeasurementGuide(
        "Chest (optional)",
        "At mid-sternum level, arms relaxed at your sides, read at the end of a normal breath out, tape horizontal."
    )
    val arm = MeasurementGuide(
        "Upper arm (optional)",
        "Arm hanging relaxed, tape horizontal at the midpoint between the tip of the shoulder and the elbow. " +
            "Use the same side every time."
    )
    val thigh = MeasurementGuide(
        "Thigh (optional)",
        "1 cm below the fold under the buttock, standing with weight evenly on both feet, tape perpendicular " +
            "to the thigh. Use the same side every time."
    )
    val calf = MeasurementGuide(
        "Calf (optional)",
        "Use the same side, the same spot and the same tape every time so the trend is comparable."
    )
    val protocol =
        "Use the right side of the body. The tape touches the skin and follows its shape without pressing in. " +
            "Take each site twice in rotation (neck, waist, neck, waist); if the two readings differ by more " +
            "than about 2.5 cm, take a third and average the two closest. Measure every 2-4 weeks, in the " +
            "morning after the toilet and before food, with the same non-stretch tape, to the nearest 0.5 cm."

    fun waistFor(sex: Sex): MeasurementGuide = if (sex == Sex.FEMALE) waistNarrowest else waistNavel
}

/** The photo protocol from onboarding_science.json "photoProtocol", in plain words. */
object PhotoProtocol {
    val steps: List<String> = listOf(
        "First thing in the morning, before eating or drinking and after the toilet - not after a workout or a big meal.",
        "Same spot against a plain wall, same time of day and light, no filters or beauty modes, no back-lighting.",
        "Phone on a stand or propped up with the self-timer, level (not tilted), about 1.2-1.8 m away. Mark the floor spot.",
        "Whole body in frame, head near the top and feet near the bottom, cropped the same way each time.",
        "Minimal, form-fitting clothing - the same outfit every time - with no shoes, watch or jewellery.",
        "Front, side and back. Relaxed, arms slightly away from the body, breathing normally, no flexing.",
        "About once a month, plus at the start and end of each phase.",
        "Photos are optional and stay on this phone. Skip them any time they don't feel good to take."
    )
}
