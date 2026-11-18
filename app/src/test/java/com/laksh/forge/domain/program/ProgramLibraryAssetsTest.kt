package com.laksh.forge.domain.program

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** The bundled templates must parse, carry their sources, and give every slot a known rule. */
class ProgramLibraryAssetsTest {

    private val dir: File = listOf(File("src/main/assets/programs"), File("app/src/main/assets/programs"))
        .first { it.isDirectory }

    private val programs: List<LibraryProgram> by lazy {
        dir.listFiles { f -> f.extension == "json" && f.name != "index.json" }.orEmpty()
            .sortedBy { it.name }
            .map { ProgramLibrary.parse(it.readText()) }
    }

    private val knownRules = setOf(
        Rules.NONE, Rules.LINEAR, Rules.DOUBLE, Rules.RIR, Rules.PERCENT, Rules.TOP_SET,
        Rules.RECENT_RM, Rules.WORK_UP, Rules.STAGES, Rules.RAMP, Rules.PR_ATTEMPT, Rules.WAVE
    )

    @Test fun allTwentyFourProgramsParse() {
        assertThat(programs).hasSize(24)
        assertThat(programs.map { it.id }.toSet()).hasSize(24)
    }

    @Test fun everyProgramKeepsItsProvenance() {
        programs.forEach { p ->
            assertThat(p.sources).isNotEmpty()
            assertThat(p.sources.all { it.url.startsWith("http") }).isTrue()
            assertThat(p.evidenceNote).isNotEmpty()
            assertThat(p.author).isNotEmpty()
            assertThat(p.verifiedAgainst).isNotEmpty()
            assertThat(p.days).isNotEmpty()
        }
    }

    @Test fun everySlotHasAKnownRuleAndUsableNumbers() {
        programs.forEach { p ->
            val all = p.days.flatMap { it.exercises } + p.weeks.orEmpty().flatMap { w -> w.days.flatMap { it.exercises } }
            all.forEach { ex ->
                assertThat(ex.rule).isIn(knownRules)
                if (ex.rule == Rules.PERCENT) {
                    assertThat(ex.maxKey).isNotNull()
                    assertThat(ex.setScheme.orEmpty().all { it.percent != null && it.of != null }).isTrue()
                }
                if (ex.rule == Rules.TOP_SET || ex.rule == Rules.RECENT_RM) assertThat(ex.ref).isNotNull()
            }
        }
    }

    @Test fun percentageProgramsAskForTheirMaxes() {
        val byId = programs.associateBy { it.id }
        listOf("wendler_531_beginners", "nsuns_531_lp_4day", "nsuns_531_lp_5day").forEach { id ->
            val setup = byId.getValue(id).engine.setup
            assertThat(setup.filter { it.basis == "TM" }.map { it.key })
                .containsAtLeast("back_squat", "bench_press", "conventional_deadlift", "overhead_press")
        }
        assertThat(byId.getValue("candito_6_week_strength").engine.setup.map { it.basis }.toSet()).containsExactly("1RM")
    }

    @Test fun recommendationsMatchLevelFirst() {
        val beginner = ProgramLibrary.rank(programs, "BEGINNER", "STRENGTH", 3)
        assertThat(beginner.first().level).isEqualTo("BEGINNER")
        assertThat(beginner.first().category).isEqualTo("STRENGTH")
        assertThat(beginner.first().daysPerWeek).isEqualTo(3)
        val hyper = ProgramLibrary.rank(programs, "INTERMEDIATE", "LEAN_GAIN", 6)
        assertThat(hyper.first().level).isEqualTo("INTERMEDIATE")
        assertThat(hyper.first().daysPerWeek).isEqualTo(6)
    }

    @Test fun firstPrescriptionOfEveryProgramIsComputable() {
        programs.forEach { p ->
            val day = p.weeks?.firstOrNull()?.days?.firstOrNull() ?: p.days.first()
            day.exercises.forEach { ex ->
                val rx = ProgressionEngine.prescribe(
                    SlotInput(ex, p.engine, ProgramState(), 1, emptyList(), loadJumpCheck = { _, _, _ -> null })
                )
                assertThat(rx.sets).isNotEmpty()
            }
        }
    }
}
