package com.laksh.forge.screenshots

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.laksh.forge.data.db.DayTotals
import com.laksh.forge.data.db.FoodEntry
import com.laksh.forge.data.db.HealthDaily
import com.laksh.forge.data.db.WeightEntry
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.domain.MacroTargets
import com.laksh.forge.feature.today.MealGroup
import com.laksh.forge.feature.today.TodayContent
import com.laksh.forge.feature.today.TodayState
import com.laksh.forge.ui.theme.ForgeAppearance
import com.laksh.forge.ui.theme.ForgeAppearanceTheme
import com.laksh.forge.ui.theme.appBackground
import com.laksh.forge.util.Dates
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the stateless Today screen with hand-built sample state, light and dark.
 *
 * `application = Application::class` keeps the real ForgeApp (database, DataStore) out of
 * the JVM test; ForgeAppearanceTheme is the stateless theme, so nothing touches settings.
 * Infinite animations (loading indicator, shimmer) are tamed by freezing the clock and
 * advancing a fixed 1.5 s, which also lets the ring spring and intro stagger settle.
 *
 * Record with: ./gradlew :app:recordRoborazziDebug --tests "*TodayScreenshotTest*"
 * Output: app/build/outputs/roborazzi/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = Application::class)
class TodayScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun today_light() = capture("today_light", dark = false)

    @Test
    fun today_dark() = capture("today_dark", dark = true)

    private fun capture(name: String, dark: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ForgeAppearanceTheme(ForgeAppearance(dark = dark)) {
                Surface(
                    color = MaterialTheme.colorScheme.appBackground,
                    modifier = Modifier.fillMaxSize()
                ) {
                    TodayContent(
                        state = sampleState(),
                        onShiftDay = {},
                        onRefresh = {},
                        onRetry = {},
                        onAddFood = {},
                        onOpenEntry = {},
                        onOpenProgress = {},
                        onOpenWearables = {},
                        onLogWeight = {},
                        onDelete = {},
                        onQuickAdd = {}
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun sampleState(): TodayState {
        val today = Dates.today()
        val dayStart = Dates.startOfDayMillis(today)
        fun at(hour: Int, minute: Int) = dayStart + (hour * 60L + minute) * 60_000L

        val entries = listOf(
            FoodEntry(
                id = 1, timestamp = at(8, 10), localDate = today,
                name = "Masala omelette, 3 eggs", servingDesc = "1 plate",
                kcal = 410.0, proteinG = 27.0, carbsG = 6.0, fatG = 30.0, fiberG = 1.5,
                source = "PHOTO", mealSlot = "BREAKFAST", confidence = "HIGH"
            ),
            FoodEntry(
                id = 2, timestamp = at(8, 15), localDate = today,
                name = "Whole milk", brand = "Amul", servingDesc = "250 g",
                kcal = 165.0, proteinG = 8.0, carbsG = 12.0, fatG = 9.0,
                source = "BARCODE", mealSlot = "BREAKFAST"
            ),
            FoodEntry(
                id = 3, timestamp = at(13, 30), localDate = today,
                name = "Rajma chawal with salad", servingDesc = "1 large bowl",
                kcal = 690.0, proteinG = 24.0, carbsG = 112.0, fatG = 14.0, fiberG = 16.0,
                source = "MANUAL", mealSlot = "LUNCH", confidence = "MEDIUM"
            ),
            FoodEntry(
                id = 4, timestamp = at(17, 5), localDate = today,
                name = "Whey protein shake", servingDesc = "1 scoop in water",
                kcal = 120.0, proteinG = 24.0, carbsG = 3.0, fatG = 1.5,
                source = "FAVOURITE", mealSlot = "SNACK"
            )
        )
        val meals = entries.groupBy { it.mealSlot }.map { (slot, items) ->
            MealGroup(
                slot = slot,
                label = when (slot) {
                    "BREAKFAST" -> "Breakfast"
                    "LUNCH" -> "Lunch"
                    "DINNER" -> "Dinner"
                    else -> "Snacks"
                },
                kcal = items.sumOf { it.kcal },
                firstTimestamp = items.minOf { it.timestamp },
                entries = items
            )
        }
        return TodayState(
            loading = false,
            date = today,
            dateLong = "Wednesday 18 November",
            entries = entries,
            meals = meals,
            totals = DayTotals(
                localDate = today,
                kcal = entries.sumOf { it.kcal },
                proteinG = entries.sumOf { it.proteinG },
                carbsG = entries.sumOf { it.carbsG },
                fatG = entries.sumOf { it.fatG },
                fiberG = entries.sumOf { it.fiberG },
                items = entries.size
            ),
            targets = MacroTargets(
                kcal = 2600,
                proteinG = 170,
                carbsG = 300,
                fatG = 80,
                fiberG = 35,
                basis = "Sample targets for the screenshot"
            ),
            targetsFromPlan = true,
            latestWeight = WeightEntry(id = 1, timestamp = at(7, 0), localDate = today, kg = 78.4),
            health = HealthDaily(localDate = today, steps = 6420, syncedAt = at(12, 0)),
            settings = ForgeSettings(),
            recent = entries.take(3)
        )
    }
}
