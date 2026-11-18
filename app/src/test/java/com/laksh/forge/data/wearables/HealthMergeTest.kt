package com.laksh.forge.data.wearables

import com.google.common.truth.Truth.assertThat
import com.laksh.forge.data.db.HealthDaily
import org.junit.Test

class HealthMergeTest {

    private val device = 2
    private val phone = 1

    @Test
    fun dedicatedDeviceBeatsPhoneRegardlessOfLayer() {
        val layers = mapOf(
            Layer.WHOOP to mapOf(HealthField.STEPS to LayerValue(4_000.0, phone, "whoop")),
            Layer.HEALTH_CONNECT to mapOf(HealthField.STEPS to LayerValue(9_000.0, device, "com.huami.watch.hmwatchmanager"))
        )
        val row = HealthMerge.merge("2026-11-17", layers, existing = null, now = 1L)
        assertThat(row.steps).isEqualTo(9_000L)
    }

    @Test
    fun equalRankFallsBackToLayerPriority() {
        val layers = mapOf(
            Layer.HEALTH_CONNECT to mapOf(HealthField.RESTING_HR to LayerValue(55.0, device, "com.whoop.android")),
            Layer.WHOOP to mapOf(HealthField.RESTING_HR to LayerValue(52.0, device, "whoop"))
        )
        assertThat(HealthMerge.merge("2026-11-17", layers, null, 1L).restingHr).isEqualTo(52L)
    }

    @Test
    fun fieldsNoLayerReportsKeepTheExistingRowsValues() {
        val existing = HealthDaily(localDate = "2026-11-17", steps = 1234, recoveryScore = 67.0, syncedAt = 0L)
        val layers = mapOf(
            Layer.HEALTH_CONNECT to mapOf(HealthField.SLEEP_MIN to LayerValue(420.0, device, "com.huami.watch.hmwatchmanager"))
        )
        val row = HealthMerge.merge("2026-11-17", layers, existing, 5L)
        assertThat(row.sleepMinutes).isEqualTo(420L)
        assertThat(row.steps).isEqualTo(1234L)
        assertThat(row.recoveryScore).isEqualTo(67.0)
        assertThat(row.syncedAt).isEqualTo(5L)
    }

    @Test
    fun appleSdnnNeverLandsInTheRmssdColumn() {
        val layers = mapOf(
            Layer.APPLE to mapOf(HealthField.HRV_SDNN to LayerValue(61.0, device, "apple_health"))
        )
        val row = HealthMerge.merge("2026-11-17", layers, null, 1L)
        assertThat(row.hrvMs).isNull()
        assertThat(row.sources).isNull()
    }

    @Test
    fun sourcesListOnlyTheOriginsWhoseValuesWereChosen() {
        val layers = mapOf(
            Layer.HEALTH_CONNECT to mapOf(
                HealthField.STEPS to LayerValue(9_000.0, device, "com.huami.watch.hmwatchmanager"),
                HealthField.HRV_RMSSD to LayerValue(40.0, device, "com.huami.watch.hmwatchmanager")
            ),
            Layer.WHOOP to mapOf(HealthField.HRV_RMSSD to LayerValue(45.0, device, "whoop"))
        )
        val row = HealthMerge.merge("2026-11-17", layers, null, 1L)
        assertThat(row.hrvMs).isEqualTo(45.0)
        assertThat(row.sources).isEqualTo("com.huami.watch.hmwatchmanager,whoop")
    }

    @Test
    fun aValueTheSourceRetractedIsClearedNotKept() {
        // A bogus 50,000-step session was synced, then deleted in Health Connect.
        val existing = HealthDaily(localDate = "2026-11-17", steps = 50_000, recoveryScore = 67.0, syncedAt = 0L, sources = "hc")
        val row = HealthMerge.merge(
            "2026-11-17", emptyMap(), existing, 5L, retracted = setOf(HealthField.STEPS)
        )
        assertThat(row.steps).isNull()
        // Fields nobody retracted (a v1 value, say) are still kept.
        assertThat(row.recoveryScore).isEqualTo(67.0)
    }
}
