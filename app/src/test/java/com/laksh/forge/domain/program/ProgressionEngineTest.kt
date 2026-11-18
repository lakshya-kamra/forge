package com.laksh.forge.domain.program

import com.google.common.truth.Truth.assertThat
import com.laksh.forge.domain.guardrails.GuardrailFlag
import com.laksh.forge.domain.guardrails.Guardrails
import com.laksh.forge.domain.guardrails.Severity
import org.junit.Test

class ProgressionEngineTest {

    private val noGuard: (Double, Double, String) -> GuardrailFlag? = { _, _, _ -> null }

    private fun session(id: Long, weight: Double, vararg reps: Int, target: Int = 5, startedAt: Long = id * 1000, rir: Double? = null) =
        SessionPerformance(
            sessionId = id,
            startedAt = startedAt,
            sets = reps.map { LoggedSet(weightKg = weight, reps = it, rir = rir, targetReps = target, targetWeightKg = weight) }
        )

    private fun input(
        slot: LibraryExercise,
        engine: EngineConfig = EngineConfig(),
        state: ProgramState = ProgramState(),
        history: List<SessionPerformance> = emptyList(),
        week: Int = 1,
        tms: Map<String, Double> = emptyMap(),
        start: Double? = null,
        guard: (Double, Double, String) -> GuardrailFlag? = noGuard
    ) = SlotInput(slot, engine, state, week, history, tms, startKg = start, loadJumpCheck = guard)

    // ------------------------------------------------------------------ LINEAR

    private val slSquat = LibraryExercise(exerciseId = "back_squat", name = "Squat", sets = 5, repsLow = 5, repsHigh = 5, rule = Rules.LINEAR, incrementKg = 2.5)
    private val slEngine = EngineConfig(model = "LINEAR", failLimit = 3, deloadPct = 0.10)

    @Test fun linear_firstSessionUsesStartingWeight() {
        val p = ProgressionEngine.prescribe(input(slSquat, slEngine, start = 60.0))
        assertThat(p.sets).hasSize(5)
        assertThat(p.sets.map { it.weightKg }.distinct()).containsExactly(60.0)
        assertThat(p.sets.all { it.reps == 5 }).isTrue()
    }

    @Test fun linear_successAddsIncrement() {
        val h = listOf(session(1, 100.0, 5, 5, 5, 5, 5))
        val p = ProgressionEngine.prescribe(input(slSquat, slEngine, history = h))
        assertThat(p.sets.first().weightKg).isWithin(1e-9).of(102.5)
    }

    @Test fun linear_singleMissRepeatsWeight() {
        val h = listOf(session(2, 100.0, 5, 5, 5, 4, 3), session(1, 97.5, 5, 5, 5, 5, 5))
        val p = ProgressionEngine.prescribe(input(slSquat, slEngine, history = h))
        assertThat(p.sets.first().weightKg).isWithin(1e-9).of(100.0)
    }

    @Test fun linear_deloadsAfterThreeMissesAtSameWeight() {
        val h = listOf(
            session(4, 100.0, 5, 5, 4, 4, 3),
            session(3, 100.0, 5, 5, 5, 4, 4),
            session(2, 100.0, 5, 5, 5, 5, 3),
            session(1, 97.5, 5, 5, 5, 5, 5)
        )
        val p = ProgressionEngine.prescribe(input(slSquat, slEngine, history = h))
        // 10% off 100 kg, rounded to the 2.5 kg plate step.
        assertThat(p.sets.first().weightKg).isWithin(1e-9).of(90.0)
        assertThat(p.reason).contains("10%")
    }

    @Test fun linear_twoMissesAreNotYetADeloadForStrongLifts() {
        val h = listOf(session(3, 100.0, 5, 5, 5, 4, 3), session(2, 100.0, 5, 5, 5, 5, 3), session(1, 97.5, 5, 5, 5, 5, 5))
        val p = ProgressionEngine.prescribe(input(slSquat, slEngine, history = h))
        assertThat(p.sets.first().weightKg).isWithin(1e-9).of(100.0)
    }

    @Test fun linear_startingStrengthDeloadsAfterSecondMiss() {
        val ss = EngineConfig(model = "LINEAR", failLimit = 2, deloadPct = 0.10)
        val slot = slSquat.copy(sets = 3)
        val h = listOf(session(2, 140.0, 5, 5, 3), session(1, 140.0, 5, 4, 4))
        val p = ProgressionEngine.prescribe(input(slot, ss, history = h))
        assertThat(p.sets.first().weightKg).isWithin(1e-9).of(125.0) // 126 rounded to 2.5
    }

    @Test fun linear_fewerSetsThanPlannedIsAMiss() {
        val h = listOf(session(1, 100.0, 5, 5, 5))
        val p = ProgressionEngine.prescribe(input(slSquat, slEngine, history = h))
        assertThat(p.sets.first().weightKg).isWithin(1e-9).of(100.0)
    }

    @Test fun linear_bounceBackReturnsToFailedWeightAfterDeload() {
        val helms = EngineConfig(model = "LINEAR", failLimit = 2, deloadPct = 0.10, bounceBack = true)
        val slot = slSquat.copy(sets = 3)
        val h = listOf(
            session(3, 90.0, 5, 5, 5),   // the deload session, all reps
            session(2, 100.0, 5, 4, 4),
            session(1, 100.0, 5, 5, 3)
        )
        val p = ProgressionEngine.prescribe(input(slot, helms, history = h))
        assertThat(p.sets.first().weightKg).isWithin(1e-9).of(100.0)
    }

    @Test fun linear_weeklyCadenceHoldsWeightWithinTheWeek() {
        val fierce = EngineConfig(model = "LINEAR", cadence = "WEEK", failLimit = 2, deloadPct = 0.15)
        val slot = slSquat.copy(sets = 3, incrementKg = 5.0)
        val weekStart = 10_000L
        val sameWeek = ProgressionEngine.prescribe(
            input(slot, fierce, ProgramState(weekStartedAt = weekStart), listOf(session(1, 80.0, 5, 5, 5, startedAt = 12_000L)))
        )
        assertThat(sameWeek.sets.first().weightKg).isWithin(1e-9).of(80.0)
        val nextWeek = ProgressionEngine.prescribe(
            input(slot, fierce, ProgramState(weekStartedAt = weekStart), listOf(session(1, 80.0, 5, 5, 5, startedAt = 9_000L)), week = 2)
        )
        assertThat(nextWeek.sets.first().weightKg).isWithin(1e-9).of(85.0)
    }

    @Test fun rounding_usesConfiguredSmallPlates() {
        val slot = slSquat.copy(incrementKg = 1.25)
        val h = listOf(session(1, 41.25, 5, 5, 5, 5, 5))
        val p = ProgressionEngine.prescribe(input(slot, slEngine, ProgramState(roundingKg = 1.25), h))
        assertThat(p.sets.first().weightKg).isWithin(1e-9).of(42.5)
        val coarse = ProgressionEngine.prescribe(input(slot.copy(incrementKg = 2.5), slEngine, ProgramState(roundingKg = 2.5), listOf(session(1, 41.0, 5, 5, 5, 5, 5))))
        assertThat(coarse.sets.first().weightKg).isWithin(1e-9).of(42.5) // 43.5 -> nearest 2.5
    }

    // ------------------------------------------------------------------ DOUBLE

    private val curl = LibraryExercise(name = "Curl", sets = 3, repsLow = 8, repsHigh = 12, rule = Rules.DOUBLE, incrementKg = 2.5)

    @Test fun double_belowTopKeepsWeightAndAsksForOneMoreRep() {
        val h = listOf(session(1, 20.0, 12, 10, 9, target = 8))
        val p = ProgressionEngine.prescribe(input(curl, history = h))
        assertThat(p.sets.map { it.weightKg }.distinct()).containsExactly(20.0)
        assertThat(p.sets.map { it.reps }).containsExactly(12, 11, 10).inOrder()
    }

    @Test fun double_topOfRangeOnEverySetAddsLoad() {
        val h = listOf(session(1, 20.0, 12, 12, 12, target = 8))
        val p = ProgressionEngine.prescribe(input(curl, history = h))
        assertThat(p.sets.first().weightKg).isWithin(1e-9).of(22.5)
        assertThat(p.sets.all { it.reps == 8 }).isTrue()
    }

    @Test fun double_perSetPyramidProgressesEachSetOnItsOwn() {
        val slot = LibraryExercise(
            name = "Bench", rule = Rules.DOUBLE, perSet = true, incrementKg = 2.5, repsLow = 5, repsHigh = 15,
            setScheme = listOf(SetSpec(reps = 8, repsLow = 5, repsHigh = 8), SetSpec(reps = 12, repsLow = 8, repsHigh = 12))
        )
        val h = listOf(
            SessionPerformance(1, 1, sets = listOf(LoggedSet(80.0, 9), LoggedSet(65.0, 10)))
        )
        val p = ProgressionEngine.prescribe(input(slot, history = h))
        assertThat(p.sets[0].weightKg).isWithin(1e-9).of(82.5)
        assertThat(p.sets[0].reps).isEqualTo(5)
        assertThat(p.sets[1].weightKg).isWithin(1e-9).of(65.0)
        assertThat(p.sets[1].reps).isEqualTo(11)
    }

    @Test fun double_deloadWeekCutsToOneSet() {
        val gvs = EngineConfig(model = "DOUBLE", deloadWeeks = listOf(5, 10), deloadSets = 1)
        val p = ProgressionEngine.prescribe(input(curl, gvs, history = listOf(session(1, 20.0, 10, 10, 10, target = 8)), week = 5))
        assertThat(p.sets).hasSize(1)
    }

    // ------------------------------------------------------------------ RPE / RIR

    private val rirSlot = LibraryExercise(name = "Bench (top set)", sets = 1, repsLow = 3, repsHigh = 5, rule = Rules.RIR, incrementKg = 2.5, intensity = Intensity("RPE", 8.0))

    @Test fun rir_tooEasyRaisesLoad() {
        val h = listOf(session(1, 100.0, 5, target = 3, rir = 4.0))
        val p = ProgressionEngine.prescribe(input(rirSlot, history = h))
        // Two reps more in reserve than the RPE 8 (2 RIR) target: two plate steps up.
        assertThat(p.sets.first().weightKg).isWithin(1e-9).of(105.0)
    }

    @Test fun rir_tooHardLowersLoad() {
        val h = listOf(session(1, 100.0, 4, target = 3, rir = 0.0))
        val p = ProgressionEngine.prescribe(input(rirSlot, history = h))
        assertThat(p.sets.first().weightKg).isWithin(1e-9).of(97.5)
    }

    @Test fun rir_onTargetHoldsLoad() {
        val h = listOf(session(1, 100.0, 4, target = 3, rir = 2.0))
        val p = ProgressionEngine.prescribe(input(rirSlot, history = h))
        assertThat(p.sets.first().weightKg).isWithin(1e-9).of(100.0)
        assertThat(p.sets.first().rir).isWithin(1e-9).of(2.0)
    }

    @Test fun rir_withoutLoggedEffortFallsBackToDoubleProgression() {
        val h = listOf(session(1, 100.0, 5, target = 3))
        val p = ProgressionEngine.prescribe(input(rirSlot, history = h))
        assertThat(p.sets.first().weightKg).isWithin(1e-9).of(102.5)
    }

    // ------------------------------------------------------------------ PERCENT_TM

    private val wendlerWeek3 = LibraryExercise(
        exerciseId = "back_squat", name = "Squat", sets = 3, repsLow = 1, repsHigh = 5, rule = Rules.PERCENT, maxKey = "back_squat", incrementKg = 5.0,
        lastSetAmrap = true,
        setScheme = listOf(
            SetSpec(reps = 5, percent = 0.75, of = "TM"),
            SetSpec(reps = 3, percent = 0.85, of = "TM"),
            SetSpec(reps = 1, percent = 0.95, of = "TM", amrap = true)
        )
    )

    @Test fun percent_setsAreRoundedShareOfTrainingMax() {
        val p = ProgressionEngine.prescribe(input(wendlerWeek3, EngineConfig(model = "WENDLER"), tms = mapOf("back_squat" to 140.0)))
        assertThat(p.sets.map { it.weightKg }).containsExactly(105.0, 120.0, 132.5).inOrder() // 119 -> 120, 133 -> 132.5
        assertThat(p.sets.map { it.amrap }).containsExactly(false, false, true).inOrder()
        assertThat(p.sets.last().label).isEqualTo("95% TM")
    }

    @Test fun percent_withoutTrainingMaxShowsPercentagesOnly() {
        val p = ProgressionEngine.prescribe(input(wendlerWeek3))
        assertThat(p.sets.all { it.weightKg == null }).isTrue()
        assertThat(p.reason).contains("training max")
    }

    @Test fun wendler_trainingMaxRisesAfterTheCycle() {
        val engine = EngineConfig(model = "WENDLER", cycleWeeks = 3, optionalWeeks = listOf(4))
        val config = ProgressionConfig(
            libraryId = "wendler_531_beginners",
            engine = engine,
            tmIncrements = mapOf("back_squat" to 5.0, "bench_press" to 2.5),
            weekDayCounts = listOf(1, 1, 1, 1),
            state = ProgramState(week = 3)
        )
        val targets = ProgressionEngine.prescribe(input(wendlerWeek3, engine, tms = mapOf("back_squat" to 140.0))).sets
        val done = FinishedSlot(
            wendlerWeek3, targets,
            listOf(LoggedSet(105.0, 5), LoggedSet(120.0, 3), LoggedSet(132.5, 4)),
            "squat"
        )
        val tms = mapOf("back_squat" to 140.0, "bench_press" to 100.0)
        val week3 = ProgressionAdvance.onSessionFinished(config, 3, listOf(done), tms, now = 5L)
        assertThat(week3.state.week).isEqualTo(4)
        assertThat(week3.tmUpdates).isEmpty()
        // Skipping the optional deload wraps the cycle and applies the increases.
        val wrap = ProgressionAdvance.advanceWeek(config.copy(state = week3.state), tms, now = 6L)
        assertThat(wrap.state.week).isEqualTo(1)
        assertThat(wrap.state.cycle).isEqualTo(2)
        assertThat(wrap.tmUpdates["back_squat"]).isWithin(1e-9).of(145.0)
        assertThat(wrap.tmUpdates["bench_press"]).isWithin(1e-9).of(102.5)
    }

    @Test fun wendler_missedRepHoldsThatTrainingMax() {
        val engine = EngineConfig(model = "WENDLER", cycleWeeks = 3)
        val config = ProgressionConfig(
            libraryId = "wendler_531_beginners", engine = engine,
            tmIncrements = mapOf("back_squat" to 5.0),
            weekDayCounts = listOf(1, 1, 1),
            state = ProgramState(week = 3)
        )
        val targets = ProgressionEngine.prescribe(input(wendlerWeek3, engine, tms = mapOf("back_squat" to 140.0))).sets
        val missed = FinishedSlot(wendlerWeek3, targets, listOf(LoggedSet(105.0, 5), LoggedSet(120.0, 2), LoggedSet(132.5, 1)), "squat")
        val r = ProgressionAdvance.onSessionFinished(config, 3, listOf(missed), mapOf("back_squat" to 140.0), now = 1L)
        assertThat(r.state.cycle).isEqualTo(2)
        assertThat(r.tmUpdates).doesNotContainKey("back_squat")
    }

    @Test fun nsuns_onePlusSetDrivesTrainingMax() {
        val slot = LibraryExercise(
            exerciseId = "back_squat", name = "Squat", rule = Rules.PERCENT, maxKey = "back_squat", incrementKg = 2.5,
            setScheme = listOf(
                SetSpec(reps = 5, percent = 0.75, of = "TM"),
                SetSpec(reps = 3, percent = 0.85, of = "TM"),
                SetSpec(reps = 1, percent = 0.95, of = "TM", amrap = true)
            )
        )
        val engine = EngineConfig(
            model = "NSUNS",
            amrapRules = listOf(AmrapRule(0, 1, 0.0), AmrapRule(2, 3, 2.5), AmrapRule(4, 5, 2.5), AmrapRule(6, null, 5.0))
        )
        val config = ProgressionConfig(libraryId = "nsuns_531_lp_4day", engine = engine, daysPerWeek = 4)
        fun run(reps: Int): Double? {
            val fs = FinishedSlot(slot, emptyList(), listOf(LoggedSet(75.0, 5), LoggedSet(85.0, 3), LoggedSet(95.0, reps)), "squat")
            return ProgressionAdvance.onSessionFinished(config, 1, listOf(fs), mapOf("back_squat" to 100.0), 1L).tmUpdates["back_squat"]
        }
        assertThat(run(1)).isNull()
        assertThat(run(3)).isWithin(1e-9).of(102.5)
        assertThat(run(7)).isWithin(1e-9).of(105.0)
    }

    // ------------------------------------------------------------------ others

    @Test fun gzclp_t1MissMovesToNextStageAtSameWeight() {
        val t1 = LibraryExercise(exerciseId = "back_squat", name = "Squat (T1)", sets = 5, repsLow = 3, repsHigh = 3, lastSetAmrap = true, rule = Rules.STAGES, tier = "T1", incrementKg = 5.0)
        val h = listOf(SessionPerformance(1, 1, sets = listOf(3, 3, 3, 2, 2).map { LoggedSet(100.0, it, targetReps = 3, targetWeightKg = 100.0) }))
        val p = ProgressionEngine.prescribe(input(t1, EngineConfig(model = "GZCLP"), history = h))
        assertThat(p.sets).hasSize(6)
        assertThat(p.sets.all { it.reps == 2 && it.weightKg == 100.0 }).isTrue()
        assertThat(p.sets.last().amrap).isTrue()
    }

    @Test fun gzclp_finalStageMissCallsForA5rmTestThenRestartsAt85Percent() {
        val t1 = LibraryExercise(name = "Squat (T1)", rule = Rules.STAGES, tier = "T1", incrementKg = 5.0)
        val failed10x1 = SessionPerformance(2, 2, sets = List(9) { LoggedSet(120.0, 1, targetReps = 1, targetWeightKg = 120.0) })
        val test = ProgressionEngine.prescribe(input(t1, history = listOf(failed10x1)))
        assertThat(test.workUp).isTrue()
        assertThat(test.sets.single().reps).isEqualTo(5)
        val tested = SessionPerformance(3, 3, sets = listOf(LoggedSet(125.0, 5, targetReps = 5)))
        val restart = ProgressionEngine.prescribe(input(t1, history = listOf(tested, failed10x1)))
        assertThat(restart.sets).hasSize(5)
        assertThat(restart.sets.first().weightKg).isWithin(1e-9).of(107.5) // 106.25 -> 107.5
    }

    @Test fun madcow_rampAndFridayTripleBecomeNextWeek() {
        val mon = LibraryExercise(exerciseId = "back_squat", name = "Squat", sets = 5, rule = Rules.RAMP, maxKey = "back_squat", role = "MON",
            setScheme = List(5) { SetSpec(reps = 5) })
        val state = ProgramState(maxes = mapOf("TOP:back_squat" to 100.0))
        val p = ProgressionEngine.prescribe(input(mon, EngineConfig(model = "MADCOW"), state))
        assertThat(p.sets.map { it.weightKg }).containsExactly(50.0, 62.5, 75.0, 87.5, 100.0).inOrder()

        val fri = mon.copy(role = "FRI", sets = 6, setScheme = List(6) { SetSpec(reps = 5) })
        val friP = ProgressionEngine.prescribe(input(fri, EngineConfig(model = "MADCOW"), state))
        assertThat(friP.sets[4].weightKg).isWithin(1e-9).of(102.5)
        assertThat(friP.sets[5].weightKg).isWithin(1e-9).of(75.0)
        val config = ProgressionConfig(libraryId = "madcow_5x5", engine = EngineConfig(model = "MADCOW"), daysPerWeek = 3, state = state)
        val logged = friP.sets.map { LoggedSet(it.weightKg ?: 0.0, it.reps) }
        val r = ProgressionAdvance.onSessionFinished(config, 1, listOf(FinishedSlot(fri, friP.sets, logged, "squat")), emptyMap(), 1L)
        assertThat(r.state.maxes["TOP:back_squat"]).isWithin(1e-9).of(102.5)
    }

    @Test fun texas_fridayFiveRepMaxUpdatesReference() {
        val fri = LibraryExercise(exerciseId = "back_squat", name = "Squat", sets = 1, repsLow = 5, repsHigh = 5, rule = Rules.PR_ATTEMPT, maxKey = "back_squat", incrementKg = 2.5)
        val state = ProgramState(maxes = mapOf("5RM:back_squat" to 150.0))
        val p = ProgressionEngine.prescribe(input(fri, EngineConfig(model = "TEXAS"), state))
        assertThat(p.sets.single().weightKg).isWithin(1e-9).of(152.5)
        val config = ProgressionConfig(libraryId = "texas_method", engine = EngineConfig(model = "TEXAS"), daysPerWeek = 3, state = state)
        val r = ProgressionAdvance.onSessionFinished(config, 1, listOf(FinishedSlot(fri, p.sets, listOf(LoggedSet(152.5, 5)), "squat")), emptyMap(), 1L)
        assertThat(r.state.maxes["5RM:back_squat"]).isWithin(1e-9).of(152.5)
    }

    @Test fun candito_missedRequiredRepCutsTheMax() {
        val slot = LibraryExercise(exerciseId = "back_squat", name = "Squat", rule = Rules.PERCENT, maxKey = "back_squat",
            setScheme = List(4) { SetSpec(reps = 6, percent = 0.8, of = "1RM") })
        val engine = EngineConfig(model = "CANDITO", missedRepCutPct = 0.025)
        val state = ProgramState(maxes = mapOf("1RM:back_squat" to 200.0))
        val p = ProgressionEngine.prescribe(input(slot, engine, state))
        assertThat(p.sets.first().weightKg).isWithin(1e-9).of(160.0)
        val config = ProgressionConfig(libraryId = "candito_6_week_strength", engine = engine, daysPerWeek = 5, state = state)
        val logged = listOf(LoggedSet(160.0, 6), LoggedSet(160.0, 6), LoggedSet(160.0, 6), LoggedSet(160.0, 5))
        val r = ProgressionAdvance.onSessionFinished(config, 1, listOf(FinishedSlot(slot, p.sets, logged, "squat")), emptyMap(), 1L)
        assertThat(r.state.maxes["1RM:back_squat"]).isWithin(1e-9).of(195.0)
    }

    @Test fun wave_loadClimbsThroughTheBlockAndResetsOnDeload() {
        val slot = LibraryExercise(name = "Squat (Barbell)", sets = 4, repsLow = 5, repsHigh = 5, rule = Rules.WAVE, incrementKg = 5.0)
        val engine = EngineConfig(model = "WAVE", optionalWeeks = listOf(4))
        fun at(week: Int, cycle: Int) = ProgressionEngine.prescribe(
            input(slot, engine, ProgramState(cycle = cycle), week = week, start = 100.0)
        ).sets.first().weightKg
        assertThat(at(1, 1)).isWithin(1e-9).of(100.0)
        assertThat(at(3, 1)).isWithin(1e-9).of(110.0)
        assertThat(at(4, 1)).isWithin(1e-9).of(100.0)
        assertThat(at(1, 2)).isWithin(1e-9).of(105.0)
    }

    @Test fun guardrailBlockHoldsTheLoad() {
        val blocker: (Double, Double, String) -> GuardrailFlag? = { _, _, _ -> GuardrailFlag("jump", Severity.BLOCK, "Too big a jump.") }
        val h = listOf(session(1, 100.0, 5, 5, 5, 5, 5))
        val p = ProgressionEngine.prescribe(input(slSquat, slEngine, history = h, guard = blocker))
        assertThat(p.sets.first().weightKg).isWithin(1e-9).of(100.0)
        assertThat(p.flag?.severity).isEqualTo(Severity.BLOCK)
    }

    private val realGuard: (Double, Double, String) -> GuardrailFlag? = { prev, next, key ->
        Guardrails.loadJumpFlag(prev, next, key)
    }

    @Test fun guard_heavyWeekAfterDeloadIsNotAJump() {
        // 5/3/1: the light deload session is the most recent, but week 3 and the TM show the
        // lifter already handles these loads, so the new cycle's week must not be held back.
        val h = listOf(session(2, 84.0, 5, 5, 5), session(1, 132.5, 5, 3, 4))
        val p = ProgressionEngine.prescribe(
            input(wendlerWeek3, EngineConfig(model = "WENDLER"), history = h, tms = mapOf("back_squat" to 145.0), guard = realGuard)
        )
        assertThat(p.flag).isNull()
        assertThat(p.sets.last().weightKg).isWithin(1e-9).of(137.5) // 137.75 -> 137.5
    }

    @Test fun guard_texasFridayAfterLightDayIsNotAJump() {
        val fri = LibraryExercise(exerciseId = "back_squat", name = "Squat", sets = 1, repsLow = 5, repsHigh = 5, rule = Rules.PR_ATTEMPT, maxKey = "back_squat", incrementKg = 2.5)
        val wednesday = session(1, 107.5, 5, 5)
        val p = ProgressionEngine.prescribe(
            input(fri, EngineConfig(model = "TEXAS"), ProgramState(maxes = mapOf("5RM:back_squat" to 150.0)), listOf(wednesday), guard = realGuard)
        )
        assertThat(p.flag).isNull()
        assertThat(p.sets.single().weightKg).isWithin(1e-9).of(152.5)
    }

    @Test fun guard_realOversizedJumpIsStillHeld() {
        val slot = slSquat.copy(incrementKg = 15.0)
        val p = ProgressionEngine.prescribe(input(slot, slEngine, history = listOf(session(1, 100.0, 5, 5, 5, 5, 5)), guard = realGuard))
        assertThat(p.flag?.severity).isEqualTo(Severity.BLOCK)
        assertThat(p.sets.first().weightKg).isWithin(1e-9).of(100.0)
    }

    @Test fun epleyRoundTrips() {
        val e1 = Strength.e1rm(100.0, 5)
        assertThat(e1).isWithin(1e-9).of(100.0 * (1 + 5 / 30.0))
        assertThat(Strength.weightForReps(e1, 5)).isWithin(1e-9).of(100.0)
        assertThat(Strength.e1rm(150.0, 1)).isWithin(1e-9).of(150.0)
    }

    @Test fun schedule_startingStrengthRotatesWithinItsPhase() {
        val config = ProgressionConfig(
            libraryId = "starting_strength_novice",
            engine = EngineConfig(rotation = listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5, 6, 5)))
        )
        val days = listOf("P1A", "P1B", "P2A", "P2B", "P3A-DL", "P3B", "P3A-PC")
        assertThat(ProgramSchedule.nextDayName(config, null, days, emptyList())).isEqualTo("P1A")
        assertThat(ProgramSchedule.nextDayName(config, null, days, listOf("P1B", "P1A"))).isEqualTo("P1A")
        assertThat(ProgramSchedule.nextDayName(config, null, days, listOf("P3B", "P3A-DL"))).isEqualTo("P3A-PC")
        assertThat(ProgramSchedule.nextDayName(config, null, days, listOf("P3B", "P3A-PC"))).isEqualTo("P3A-DL")
    }

    @Test fun schedule_waveProgramsFollowTheWeeksDayList() {
        val weeks = listOf(
            LibraryWeek(1, "a", listOf(LibraryDay("Lower 1"), LibraryDay("Upper 1"))),
            LibraryWeek(2, "b", listOf(LibraryDay("Lower 1"), LibraryDay("Lower 2")))
        )
        val config = ProgressionConfig(libraryId = "x", engine = EngineConfig(), state = ProgramState(week = 2, sessionsInWeek = 1))
        assertThat(ProgramSchedule.nextDayName(config, weeks, listOf("Lower 1", "Upper 1", "Lower 2"), listOf("Lower 1"))).isEqualTo("Lower 2")
    }
}
