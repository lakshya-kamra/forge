package com.laksh.forge.data.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class WorkoutCsvImportTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    // ------------------------------------------------------------------ Hevy

    private val hevy = """
        "title","start_time","end_time","description","exercise_title","superset_id","exercise_notes","set_index","set_type","weight_kg","reps","distance_km","duration_seconds","rpe"
        "Push Day","20 Nov 2023, 18:05","20 Nov 2023, 19:10","Felt good","Bench Press (Barbell)",,"",0,"warmup",40,10,,,
        "Push Day","20 Nov 2023, 18:05","20 Nov 2023, 19:10","Felt good","Bench Press (Barbell)",,"",1,"normal",80,5,,,8
        "Push Day","20 Nov 2023, 18:05","20 Nov 2023, 19:10","Felt good","Bench Press (Barbell)",,"",2,"failure",80,4,,,10
        "Push Day","20 Nov 2023, 18:05","20 Nov 2023, 19:10","Felt good","Plank",,"",0,"normal",,,,60,
        "Leg Day","22 Nov 2023, 07:00","22 Nov 2023, 08:00","","Squat (Barbell)",,"",0,"normal",100,5,,,
    """.trimIndent()

    @Test
    fun `hevy export is detected and grouped into workouts`() {
        val r = WorkoutCsvImport.parse(hevy, zone)
        assertThat(r.source).isEqualTo(WorkoutCsvSource.HEVY)
        assertThat(r.workouts).hasSize(2)
        assertThat(r.unitFromFile).isTrue()

        val push = r.workouts[0]
        assertThat(push.title).isEqualTo("Push Day")
        assertThat(push.startedAt).isEqualTo(millis(2023, 11, 20, 18, 5))
        assertThat(push.endedAt).isEqualTo(millis(2023, 11, 20, 19, 10))
        assertThat(push.notes).isEqualTo("Felt good")
        assertThat(push.sets).hasSize(3)
        assertThat(push.exerciseNames).containsExactly("Bench Press (Barbell)")

        val warm = push.sets[0]
        assertThat(warm.isWarmup).isTrue()
        assertThat(warm.weightKg).isEqualTo(40.0)
        assertThat(warm.reps).isEqualTo(10)
        assertThat(warm.setIdx).isEqualTo(0)

        val top = push.sets[1]
        assertThat(top.isWarmup).isFalse()
        assertThat(top.rpe).isEqualTo(8.0)
        assertThat(top.setIdx).isEqualTo(1)
        assertThat(push.sets[2].isWarmup).isFalse() // failure sets are working sets

        val legs = r.workouts[1]
        assertThat(legs.notes).isNull()
        assertThat(legs.sets.single().weightKg).isEqualTo(100.0)
    }

    @Test
    fun `hevy timed sets without reps are skipped not failed`() {
        val r = WorkoutCsvImport.parse(hevy, zone)
        assertThat(r.skippedRows).isEqualTo(1)
        assertThat(r.setCount).isEqualTo(4)
    }

    @Test
    fun `hevy pounds column is converted to kg`() {
        val csv = """
            title,start_time,end_time,description,exercise_title,superset_id,exercise_notes,set_index,set_type,weight_lbs,reps,distance_miles,duration_seconds,rpe
            Upper,"1 Feb 2024, 09:30","1 Feb 2024, 10:30",,Overhead Press (Barbell),,,0,normal,100,5,,,
        """.trimIndent()
        val r = WorkoutCsvImport.parse(csv, zone)
        assertThat(r.workouts.single().sets.single().weightKg).isWithin(0.001).of(45.359)
    }

    // ------------------------------------------------------------------ Strong

    @Test
    fun `strong semicolon export with decimal commas and no unit column`() {
        val csv = """
            Date;Workout Name;Duration;Exercise Name;Set Order;Weight;Reps;Distance;Seconds;Notes;Workout Notes;RPE
            2023-03-14 17:25:31;"Evening; heavy";1h 5m;Deadlift (Barbell);1;225;5;0;0;;;
            2023-03-14 17:25:31;"Evening; heavy";1h 5m;Deadlift (Barbell);2;245,5;3;0;0;;;9
            2023-03-14 17:25:31;"Evening; heavy";1h 5m;Running;1;0;0;3.2;1200;;;
        """.trimIndent()

        val asLb = WorkoutCsvImport.parse(csv, zone, weightsInLb = true)
        assertThat(asLb.source).isEqualTo(WorkoutCsvSource.STRONG)
        assertThat(asLb.unitFromFile).isFalse()
        val w = asLb.workouts.single()
        assertThat(w.title).isEqualTo("Evening; heavy")
        assertThat(w.startedAt).isEqualTo(millis(2023, 3, 14, 17, 25, 31))
        assertThat(w.endedAt).isEqualTo(w.startedAt + 65 * 60 * 1000L)
        assertThat(w.sets).hasSize(2)
        assertThat(w.sets[0].weightKg).isWithin(0.001).of(102.058)
        assertThat(w.sets[1].weightKg).isWithin(0.001).of(111.357)
        assertThat(w.sets[1].rpe).isEqualTo(9.0)
        assertThat(w.sets.map { it.setIdx }).containsExactly(0, 1).inOrder()
        assertThat(asLb.skippedRows).isEqualTo(1) // the running row has no reps

        val asKg = WorkoutCsvImport.parse(csv, zone, weightsInLb = false)
        assertThat(asKg.workouts.single().sets[0].weightKg).isEqualTo(225.0)
    }

    @Test
    fun `strong export with a unit column and warm-up set order`() {
        val csv = """
            Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Weight Unit,Reps,RPE,Distance,Distance Unit,Seconds,Notes,Workout Notes
            2024-01-02 06:00:00,Morning,45m,Squat (Barbell),W,60,kg,5,,,,,,"Slept badly"
            2024-01-02 06:00:00,Morning,45m,Squat (Barbell),1,100,kg,5,8.5,,,,,"Slept badly"
            2024-01-02 06:00:00,Morning,45m,Leg Press,1,200,lbs,10,,,,,,"Slept badly"
        """.trimIndent()
        // weightsInLb is ignored when the file names its unit.
        val r = WorkoutCsvImport.parse(csv, zone, weightsInLb = true)
        assertThat(r.unitFromFile).isTrue()
        val w = r.workouts.single()
        assertThat(w.notes).isEqualTo("Slept badly")
        assertThat(w.endedAt).isEqualTo(w.startedAt + 45 * 60 * 1000L)
        assertThat(w.sets[0].isWarmup).isTrue()
        assertThat(w.sets[0].weightKg).isEqualTo(60.0)
        assertThat(w.sets[1].isWarmup).isFalse()
        assertThat(w.sets[1].rpe).isEqualTo(8.5)
        assertThat(w.sets[2].weightKg).isWithin(0.001).of(90.718)
        assertThat(w.exerciseNames).containsExactly("Squat (Barbell)", "Leg Press").inOrder()
    }

    @Test
    fun `workouts come back oldest first`() {
        val csv = """
            Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps
            2024-02-10 08:00:00,B,30m,Row,1,60,8
            2024-01-05 08:00:00,A,30m,Row,1,55,8
        """.trimIndent()
        val r = WorkoutCsvImport.parse(csv, zone)
        assertThat(r.workouts.map { it.title }).containsExactly("A", "B").inOrder()
    }

    // ------------------------------------------------------------------ errors

    @Test
    fun `unknown files are rejected with a helpful message`() {
        val e = assertThrows(WorkoutCsvException::class.java) {
            WorkoutCsvImport.parse("date,food,kcal\n2024-01-01,Rice,200", zone)
        }
        assertThat(e.message).contains("Hevy")
    }

    @Test
    fun `header-only files are rejected`() {
        assertThrows(WorkoutCsvException::class.java) {
            WorkoutCsvImport.parse("Date,Workout Name,Exercise Name,Reps", zone)
        }
    }

    @Test
    fun `unreadable dates are skipped and reported`() {
        val csv = """
            Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps
            not a date,A,30m,Row,1,60,8
            2024-01-05 08:00:00,B,30m,Row,1,55,8
        """.trimIndent()
        val r = WorkoutCsvImport.parse(csv, zone)
        assertThat(r.workouts).hasSize(1)
        assertThat(r.warnings).isNotEmpty()
    }

    // ------------------------------------------------------------------ helpers

    @Test
    fun `date formats from both apps parse`() {
        assertThat(WorkoutCsvImport.parseDateTime("20 Nov 2023, 18:05", zone)).isEqualTo(millis(2023, 11, 20, 18, 5))
        assertThat(WorkoutCsvImport.parseDateTime("2023-03-14 17:25:31", zone)).isEqualTo(millis(2023, 3, 14, 17, 25, 31))
        assertThat(WorkoutCsvImport.parseDateTime("2023-03-14T17:25:31Z", zone)).isEqualTo(millis(2023, 3, 14, 17, 25, 31))
        assertThat(WorkoutCsvImport.parseDateTime("yesterday", zone)).isNull()
    }

    @Test
    fun `durations parse`() {
        assertThat(WorkoutCsvImport.parseDurationMillis("1h 5m")).isEqualTo(3_900_000L)
        assertThat(WorkoutCsvImport.parseDurationMillis("45m")).isEqualTo(2_700_000L)
        assertThat(WorkoutCsvImport.parseDurationMillis("50s")).isEqualTo(50_000L)
        assertThat(WorkoutCsvImport.parseDurationMillis("3600")).isEqualTo(3_600_000L)
        assertThat(WorkoutCsvImport.parseDurationMillis("")).isNull()
    }

    @Test
    fun `bracketed equipment names map onto the catalog`() {
        assertThat(WorkoutCsvImport.nameCandidates("Bench Press (Barbell)"))
            .containsExactly("Bench Press (Barbell)", "Barbell Bench Press", "Bench Press").inOrder()
        assertThat(WorkoutCsvImport.nameCandidates("Plank")).containsExactly("Plank")

        val catalog = mapOf("barbell bench press" to "bench_press", "pull up" to "pull_up")
        val resolve: (String) -> String? = { catalog[it.lowercase()] }
        assertThat(WorkoutCsvImport.resolveId("Bench Press (Barbell)", resolve)).isEqualTo("bench_press")
        assertThat(WorkoutCsvImport.resolveId("Pull Up", resolve)).isEqualTo("pull_up")
        assertThat(WorkoutCsvImport.resolveId("Cable Fly (Crossover)", resolve)).isNull()
    }
}
