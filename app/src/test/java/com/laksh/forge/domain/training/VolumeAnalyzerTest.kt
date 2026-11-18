package com.laksh.forge.domain.training

import com.google.common.truth.Truth.assertThat
import com.laksh.forge.data.db.DatedSet
import com.laksh.forge.data.prefs.TrainingAge
import com.laksh.forge.domain.exercise.ExerciseInfo
import org.junit.Test
import java.time.LocalDate

class VolumeAnalyzerTest {

    private val today = LocalDate.of(2026, 11, 17)

    private val bench = ExerciseInfo(
        id = "bench_press",
        name = "Bench Press",
        primaryMuscles = listOf("chest"),
        secondaryMuscles = listOf("triceps", "front_delts")
    )
    private val row = ExerciseInfo(
        id = "barbell_row",
        name = "Barbell Row",
        primaryMuscles = listOf("lats", "upper_back"),
        secondaryMuscles = listOf("biceps", "rear_delts")
    )
    private val curl = ExerciseInfo(
        id = "curl",
        name = "Curl",
        primaryMuscles = listOf("biceps"),
        // Listed again as secondary on purpose: it must not count 1.5 times.
        secondaryMuscles = listOf("biceps", "forearms")
    )

    private val byId = listOf(bench, row, curl).associateBy { it.id }
    private val byName = listOf(bench, row, curl).associateBy { it.name.lowercase() }
    private val resolver = ExerciseResolver { name, id ->
        id?.let { byId[it] } ?: byName[name.trim().lowercase()]
    }

    private fun set(
        name: String,
        daysAgo: Long = 1,
        rir: Double? = null,
        rpe: Double? = null,
        id: String? = null
    ) = DatedSet(
        localDate = today.minusDays(daysAgo).toString(),
        exerciseName = name,
        exerciseId = id,
        weightKg = 60.0,
        reps = 8,
        rir = rir,
        rpe = rpe
    )

    private fun analyze(sets: List<DatedSet>, age: TrainingAge = TrainingAge.INTERMEDIATE) =
        VolumeAnalyzer.analyzeRollingWeek(sets, today, age, resolver)

    private fun VolumeReport.sets(muscle: String) = muscles.firstOrNull { it.muscle == muscle }?.sets ?: 0.0

    @Test fun primaryCountsOneAndSecondaryHalf() {
        val r = analyze(List(3) { set("Bench Press", rir = 2.0) } + List(3) { set("Barbell Row") })
        assertThat(r.sets("chest")).isEqualTo(3.0)
        assertThat(r.sets("triceps")).isEqualTo(1.5)
        assertThat(r.sets("front_delts")).isEqualTo(1.5)
        assertThat(r.sets("lats")).isEqualTo(3.0)
        assertThat(r.sets("upper_back")).isEqualTo(3.0)
        assertThat(r.sets("biceps")).isEqualTo(1.5)
        assertThat(r.sets("rear_delts")).isEqualTo(1.5)
        assertThat(r.hardSets).isEqualTo(6)
    }

    @Test fun setsFarFromFailureAreNotCounted() {
        val r = analyze(
            listOf(
                set("Bench Press", rir = 5.0),     // too easy
                set("Bench Press", rpe = 5.0),     // RPE 5 = 5 RIR, too easy
                set("Bench Press", rir = 4.0),     // counts
                set("Bench Press", rpe = 6.0)      // RPE 6 = 4 RIR, counts
            )
        )
        assertThat(r.sets("chest")).isEqualTo(2.0)
        assertThat(r.easySetsExcluded).isEqualTo(2)
        assertThat(r.hardSets).isEqualTo(2)
    }

    @Test fun unknownExercisesAreSkippedAndReported() {
        val r = analyze(listOf(set("Mystery Machine"), set("Mystery Machine"), set("Bench Press")))
        assertThat(r.unresolved).containsExactly("Mystery Machine")
        assertThat(r.unresolvedSets).isEqualTo(2)
        assertThat(r.hardSets).isEqualTo(1)
        assertThat(r.hasData).isTrue()
    }

    @Test fun storedCatalogIdWinsOverTheName() {
        val r = analyze(listOf(set("Flat BB press (paused)", id = "bench_press")))
        assertThat(r.sets("chest")).isEqualTo(1.0)
        assertThat(r.unresolved).isEmpty()
    }

    @Test fun aMuscleListedTwiceCountsAsDirectOnly() {
        val r = analyze(List(2) { set("Curl") })
        assertThat(r.sets("biceps")).isEqualTo(2.0)
        assertThat(r.sets("forearms")).isEqualTo(1.0)
    }

    @Test fun olderSetsGoToThePreviousWeek() {
        val r = analyze(listOf(set("Bench Press", daysAgo = 1), set("Bench Press", daysAgo = 10)))
        val chest = r.muscles.first { it.muscle == "chest" }
        assertThat(chest.sets).isEqualTo(1.0)
        assertThat(chest.previousSets).isEqualTo(1.0)
        assertThat(r.hardSets).isEqualTo(1)
    }

    @Test fun setsOutsideBothWindowsAreIgnored() {
        val r = analyze(listOf(set("Bench Press", daysAgo = 20)))
        assertThat(r.hasData).isFalse()
        assertThat(r.muscles).isEmpty()
    }

    @Test fun twelveSetsInOneSessionSuggestSplitting() {
        val r = analyze(List(12) { set("Bench Press", daysAgo = 2) })
        assertThat(r.sets("chest")).isEqualTo(12.0)
        assertThat(r.muscles.first { it.muscle == "chest" }.sessions).isEqualTo(1)
        assertThat(r.flags.map { it.id }).contains("training_frequency_per_muscle:chest")
        // Triceps at 6 indirect sets sits inside the bands: no volume flag.
        assertThat(r.flags.map { it.id }).doesNotContain("training_weekly_sets_per_muscle:triceps")
    }

    @Test fun lowVolumeMusclesAreFlaggedAndUntrainedListed() {
        val r = analyze(listOf(set("Bench Press")))
        assertThat(r.flags.map { it.id }).contains("training_weekly_sets_per_muscle:chest")
        assertThat(r.untrained).contains("Calves")
        assertThat(r.untrained).doesNotContain("Chest")
    }

    @Test fun musclesAreSortedByVolume() {
        val r = analyze(List(4) { set("Barbell Row") } + List(2) { set("Bench Press") })
        assertThat(r.muscles.first().sets).isEqualTo(4.0)
        assertThat(r.muscles.map { it.sets }).isInOrder(Comparator.reverseOrder<Double>())
    }
}
