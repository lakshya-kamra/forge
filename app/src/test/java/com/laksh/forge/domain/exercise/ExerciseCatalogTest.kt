package com.laksh.forge.domain.exercise

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.io.File

/** The bundled catalog parses, and free-text names from PDFs and logs resolve to the right lift. */
class ExerciseCatalogTest {

    private val file: File = listOf(
        File("src/main/assets/exercises/catalog.json"),
        File("app/src/main/assets/exercises/catalog.json")
    ).first { it.isFile }

    @Before fun install() {
        ExerciseCatalog.install(file.readText())
    }

    @Test fun catalogHasSixtyTwoUniqueExercises() {
        val all = ExerciseCatalog.all()
        assertThat(all).hasSize(62)
        assertThat(all.map { it.id }.toSet()).hasSize(62)
        assertThat(ExerciseCatalog.loadError()).isNull()
    }

    @Test fun everyExerciseIsComplete() {
        ExerciseCatalog.all().forEach { e ->
            assertThat(e.name).isNotEmpty()
            assertThat(e.primaryMuscles).isNotEmpty()
            assertThat(e.primaryMuscles.all { it in ExerciseLabels.MUSCLES }).isTrue()
            assertThat(e.secondaryMuscles.all { it in ExerciseLabels.MUSCLES }).isTrue()
            assertThat(e.cues).isNotEmpty()
            e.videos.forEach { v -> assertThat(v.videoId).matches("[A-Za-z0-9_-]{11}") }
            if (e.hasMotion) assertThat(ExerciseCatalog.motion(e.id)!!.keyframes).isNotEmpty()
        }
    }

    @Test fun commonGymSlangResolves() {
        val cases = mapOf(
            "BB bench" to "bench_press",
            "BB Bench" to "bench_press",
            "Bench" to "bench_press",
            "lat pull-down" to "lat_pulldown",
            "Lat Pull-Down" to "lat_pulldown",
            "Lat pulldowns" to "lat_pulldown",
            "RDL" to "romanian_deadlift",
            "RDLs 3x8" to "romanian_deadlift",
            "Romanian Deadlift (RDL)" to "romanian_deadlift",
            "Barbell Back Squat" to "back_squat",
            "Back squat (paused)" to "back_squat",
            "OHP" to "overhead_press",
            "Military press" to "overhead_press",
            "Incline DB press" to "incline_dumbbell_press",
            "DB row" to "dumbbell_row",
            "Pull-ups" to "pull_up",
            "Chin-ups" to "chin_up",
            "Skullcrushers" to "skull_crusher",
            "Tricep rope pushdown" to "triceps_pushdown",
            "Close-grip bench press" to "close_grip_bench_press",
            "Bulgarian split squats" to "bulgarian_split_squat",
            "a) Hip thrusts" to "hip_thrust",
            "bench_press" to "bench_press"
        )
        cases.forEach { (text, id) ->
            assertWithName(text, ExerciseCatalog.resolve(text)?.id).isEqualTo(id)
        }
    }

    @Test fun listNumbersAndQualifiersDoNotHideTheLift() {
        assertThat(ExerciseCatalog.resolve("3. Paused barbell bench press")?.id).isEqualTo("bench_press")
    }

    @Test fun aMisspeltQualifierStillFindsTheLongerName() {
        // Not the shorter, correctly spelt "deadlift" inside it.
        assertThat(ExerciseCatalog.resolve("romanain deadlift")?.id).isEqualTo("romanian_deadlift")
    }

    @Test fun unknownOrAmbiguousNamesResolveToNothing() {
        assertThat(ExerciseCatalog.resolve("Zercher carry")).isNull()
        // Lying or seated: guessing would show the wrong demo.
        assertThat(ExerciseCatalog.resolve("Leg curl")).isNull()
        assertThat(ExerciseCatalog.resolve("")).isNull()
        assertThat(ExerciseCatalog.resolve("   ")).isNull()
    }

    @Test fun keyForFallsBackToTheRawName() {
        assertThat(ExerciseCatalog.keyFor("BB bench")).isEqualTo("bench_press")
        assertThat(ExerciseCatalog.keyFor("Zercher carry")).isEqualTo("Zercher carry")
    }

    @Test fun searchRanksTheResolvedLiftFirst() {
        assertThat(ExerciseCatalog.search("rdl").first().id).isEqualTo("romanian_deadlift")
        assertThat(ExerciseCatalog.search("")).hasSize(62)
        assertThat(ExerciseCatalog.search("qqqzzz")).isEmpty()
    }

    @Test fun gvsTiersComeFromTheCatalog() {
        assertThat(ExerciseCatalog.tierOf("Jeff Nippard")).isEqualTo("S")
        assertThat(ExerciseCatalog.techniqueCreators()).isNotEmpty()
    }

    private fun assertWithName(name: String, actual: String?) =
        com.google.common.truth.Truth.assertWithMessage(name).that(actual)
}
