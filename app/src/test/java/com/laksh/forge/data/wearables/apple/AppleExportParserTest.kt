package com.laksh.forge.data.wearables.apple

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Robolectric supplies the real android.util.Xml pull parser (the same KXmlParser the app uses on
 * device); everything else here is plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppleExportParserTest {

    // Shaped like a real export.xml: an inline DTD, ExportDate/Me headers, Records with and
    // without children, overlapping iPhone + Watch steps, sleep stages inside an InBed sample.
    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE HealthData [
        <!ELEMENT HealthData (ExportDate,Me,(Record|Correlation|Workout|ActivitySummary|ClinicalRecord)*)>
        <!ATTLIST HealthData locale CDATA #REQUIRED>
        ]>
        <HealthData locale="en_IN">
         <ExportDate value="2026-11-17 08:00:00 +0530"/>
         <Me HKCharacteristicTypeIdentifierBiologicalSex="HKBiologicalSexMale"/>
         <Record type="HKQuantityTypeIdentifierStepCount" sourceName="Test iPhone" unit="count" startDate="2026-11-16 09:00:00 +0530" endDate="2026-11-16 09:10:00 +0530" value="1000"/>
         <Record type="HKQuantityTypeIdentifierStepCount" sourceName="Test iPhone" unit="count" startDate="2026-11-16 18:00:00 +0530" endDate="2026-11-16 18:20:00 +0530" value="2000"/>
         <Record type="HKQuantityTypeIdentifierStepCount" sourceName="Test Apple Watch" unit="count" startDate="2026-11-16 09:00:00 +0530" endDate="2026-11-16 09:10:00 +0530" value="1200"/>
         <Record type="HKQuantityTypeIdentifierStepCount" sourceName="Test Apple Watch" unit="count" startDate="2026-11-16 18:00:00 +0530" endDate="2026-11-16 18:20:00 +0530" value="2100"/>
         <Record type="HKQuantityTypeIdentifierStepCount" sourceName="Test iPhone" unit="count" startDate="2026-11-15 10:00:00 +0530" endDate="2026-11-15 10:05:00 +0530" value="500"/>
         <Record type="HKQuantityTypeIdentifierStepCount" sourceName="Test iPhone" unit="count" startDate="2026-11-17 01:00:00 -0800" endDate="2026-11-17 01:05:00 -0800" value="300"/>
         <Record type="HKQuantityTypeIdentifierStepCount" sourceName="Test iPhone" unit="count" startDate="not a date" endDate="not a date" value="999"/>
         <Record type="HKQuantityTypeIdentifierActiveEnergyBurned" sourceName="Test Apple Watch" unit="kJ" startDate="2026-11-16 12:00:00 +0530" endDate="2026-11-16 12:30:00 +0530" value="418.4"/>
         <Record type="HKQuantityTypeIdentifierHeartRate" sourceName="Test Apple Watch" unit="count/min" startDate="2026-11-16 10:00:00 +0530" endDate="2026-11-16 10:00:00 +0530" value="60"/>
         <Record type="HKQuantityTypeIdentifierHeartRate" sourceName="Test Apple Watch" unit="count/min" startDate="2026-11-16 11:00:00 +0530" endDate="2026-11-16 11:00:00 +0530" value="80"/>
         <Record type="HKQuantityTypeIdentifierRestingHeartRate" sourceName="Test Apple Watch" unit="count/min" startDate="2026-11-16 00:00:00 +0530" endDate="2026-11-16 23:59:00 +0530" value="55"/>
         <Record type="HKQuantityTypeIdentifierHeartRateVariabilitySDNN" sourceName="Test Apple Watch" unit="ms" startDate="2026-11-16 03:00:00 +0530" endDate="2026-11-16 03:01:00 +0530" value="62.5">
          <HeartRateVariabilityMetadataList>
           <InstantaneousBeatsPerMinute bpm="61" time="3:00:01.00 AM"/>
          </HeartRateVariabilityMetadataList>
         </Record>
         <Record type="HKQuantityTypeIdentifierVO2Max" sourceName="Test Apple Watch" unit="mL/min·kg" startDate="2026-11-16 19:00:00 +0530" endDate="2026-11-16 19:00:00 +0530" value="45.2"/>
         <Record type="HKCategoryTypeIdentifierSleepAnalysis" sourceName="Test Apple Watch" startDate="2026-11-15 23:15:00 +0530" endDate="2026-11-16 07:00:00 +0530" value="HKCategoryValueSleepAnalysisInBed"/>
         <Record type="HKCategoryTypeIdentifierSleepAnalysis" sourceName="Test Apple Watch" startDate="2026-11-15 23:30:00 +0530" endDate="2026-11-16 03:30:00 +0530" value="HKCategoryValueSleepAnalysisAsleepCore"/>
         <Record type="HKCategoryTypeIdentifierSleepAnalysis" sourceName="Test Apple Watch" startDate="2026-11-16 03:30:00 +0530" endDate="2026-11-16 04:30:00 +0530" value="HKCategoryValueSleepAnalysisAsleepDeep"/>
         <Record type="HKCategoryTypeIdentifierSleepAnalysis" sourceName="Test Apple Watch" startDate="2026-11-16 04:30:00 +0530" endDate="2026-11-16 04:45:00 +0530" value="HKCategoryValueSleepAnalysisAwake"/>
         <Record type="HKCategoryTypeIdentifierSleepAnalysis" sourceName="Test Apple Watch" startDate="2026-11-16 04:45:00 +0530" endDate="2026-11-16 06:45:00 +0530" value="HKCategoryValueSleepAnalysisAsleepREM"/>
         <Record type="HKQuantityTypeIdentifierBodyMass" sourceName="Scale" unit="lb" startDate="2026-11-16 07:00:00 +0530" endDate="2026-11-16 07:00:00 +0530" value="176.37"/>
         <Record type="HKQuantityTypeIdentifierBodyMass" sourceName="Scale" unit="kg" startDate="2026-11-16 21:00:00 +0530" endDate="2026-11-16 21:00:00 +0530" value="80.6"/>
         <Record type="HKQuantityTypeIdentifierBodyMass" sourceName="Scale" unit="kg" startDate="2026-11-15 07:00:00 +0530" endDate="2026-11-15 07:00:00 +0530" value="80.4"/>
         <Record type="HKQuantityTypeIdentifierBodyFatPercentage" sourceName="Scale" unit="%" startDate="2026-11-16 07:00:00 +0530" endDate="2026-11-16 07:00:00 +0530" value="0.185"/>
         <Workout workoutActivityType="HKWorkoutActivityTypeTraditionalStrengthTraining" duration="45" durationUnit="min" sourceName="Test Apple Watch" startDate="2026-11-16 18:30:00 +0530" endDate="2026-11-16 19:15:00 +0530">
          <WorkoutStatistics type="HKQuantityTypeIdentifierActiveEnergyBurned" sum="210" unit="kcal"/>
         </Workout>
         <ActivitySummary dateComponents="2026-11-16" activeEnergyBurned="300" activeEnergyBurnedGoal="500"/>
        </HealthData>
    """.trimIndent()

    private fun parse(): AppleAggregate {
        val aggregator = AppleDayAggregator()
        val parser = AppleExportParser.newParser(sample.byteInputStream(Charsets.UTF_8))
        AppleExportParser.parse(parser, aggregator)
        return aggregator.result()
    }

    @Test
    fun aggregatesEachLocalDay() {
        val result = parse()
        val days = result.days.associateBy { it.date }
        val d16 = days.getValue(LocalDate.of(2026, 11, 16))

        // iPhone counted 3,000 and the Watch 3,300 of the same walking: the larger source wins,
        // the two are never added together.
        assertThat(d16.steps).isEqualTo(3_300.0)
        assertThat(d16.watchFields).contains("steps")
        assertThat(d16.activeKcal!!).isWithin(1e-9).of(100.0)
        assertThat(d16.avgHr).isEqualTo(70.0)
        assertThat(d16.restingHr).isEqualTo(55.0)
        assertThat(d16.vo2max).isEqualTo(45.2)
        assertThat(d16.bodyFatPct!!).isWithin(1e-9).of(18.5)
        assertThat(d16.exerciseMinutes).isEqualTo(45.0)

        assertThat(days.getValue(LocalDate.of(2026, 11, 15)).steps).isEqualTo(500.0)
        // A record keeps the date of the offset it was recorded in, not the phone's zone.
        assertThat(days.getValue(LocalDate.of(2026, 11, 17)).steps).isEqualTo(300.0)
    }

    @Test
    fun sleepCountsOnlyAsleepStagesOnTheWakeDay() {
        val d16 = parse().days.first { it.date == LocalDate.of(2026, 11, 16) }
        // Core 240 + Deep 60 + REM 120; InBed and Awake are excluded.
        assertThat(d16.sleepMinutes).isEqualTo(420.0)
        assertThat(parse().days.first { it.date == LocalDate.of(2026, 11, 15) }.sleepMinutes).isNull()
    }

    @Test
    fun sdnnStaysSeparateFromAnyRmssdField() {
        val d16 = parse().days.first { it.date == LocalDate.of(2026, 11, 16) }
        assertThat(d16.hrvSdnnMs).isEqualTo(62.5)
    }

    @Test
    fun weightsAreFirstOfDayAndConvertedToKg() {
        val weights = parse().weights
        assertThat(weights.map { it.date })
            .containsExactly(LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 16))
            .inOrder()
        assertThat(weights[0].kg).isWithin(1e-9).of(80.4)
        // 176.37 lb at 07:00 beats the 80.6 kg reading at 21:00.
        assertThat(weights[1].kg).isWithin(0.01).of(80.0)
    }

    @Test
    fun countsEveryRecordAndSkipsUnreadableOnes() {
        val result = parse()
        // 22 Records + 1 Workout.
        assertThat(result.recordsSeen).isEqualTo(23L)
        assertThat(result.recordsUsed).isLessThan(result.recordsSeen)
        assertThat(result.days.none { it.steps == 999.0 }).isTrue()
    }
}
