package com.laksh.forge.feature.widgets

import com.laksh.forge.ForgeApp
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.plan.PlanProfileStore
import com.laksh.forge.domain.plan.PlanTargets
import com.laksh.forge.util.Dates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Everything the Today widget shows, loaded before composition so the Glance tree stays pure. */
data class TodaySnapshot(
    val onboarded: Boolean,
    /** The person chose to hide calorie numbers (or a safety lock is on): show none. */
    val hideNumbers: Boolean,
    val kcal: Int,
    val targetKcal: Int,
    val proteinG: Int,
    val targetProteinG: Int,
    val items: Int
) {
    val remainingKcal: Int get() = targetKcal - kcal
    val kcalProgress: Float get() = if (targetKcal <= 0) 0f else (kcal.toFloat() / targetKcal).coerceIn(0f, 1f)
    val proteinProgress: Float get() = if (targetProteinG <= 0) 0f else (proteinG.toFloat() / targetProteinG).coerceIn(0f, 1f)
    val over: Boolean get() = targetKcal in 1 until kcal
}

data class NextWorkoutSnapshot(
    val programName: String?,
    val dayName: String?,
    val dayFocus: String?,
    val exerciseCount: Int,
    val exercisePreview: List<String>,
    val openSessionId: Long?,
    val openSessionTitle: String?,
    val lastTrainedLabel: String?,
    val trainedToday: Boolean
)

object WidgetData {

    suspend fun today(): TodaySnapshot = withContext(Dispatchers.IO) {
        val repo = ForgeApp.instance.repository
        val s = repo.settings()
        val totals = repo.foodDao.observeTotals(Dates.today()).first()
        // The active plan's targets win when there is one; otherwise the same fallback Today uses.
        val planTargets = try {
            PlanTargets.observe(repo).first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val targets = planTargets ?: NutritionMath.targets(
            s,
            repo.weightDao.observeLatest().first()?.kg ?: 75.0,
            s.learnedTdee.takeIf { it > 0 },
            runCatching { PlanProfileStore.current().kidneyDisease }.getOrDefault(false)
        )
        TodaySnapshot(
            onboarded = s.onboarded,
            // A positive eating-disorder screen hides deficit framing app-wide; "calories
            // left" on the home screen is exactly that framing, so treat it like hideNumbers.
            hideNumbers = s.hideNumbers || s.edScreenPositive,
            kcal = (totals?.kcal ?: 0.0).roundToInt(),
            targetKcal = targets.kcal,
            proteinG = (totals?.proteinG ?: 0.0).roundToInt(),
            targetProteinG = targets.proteinG,
            items = totals?.items ?: 0
        )
    }

    suspend fun nextWorkout(): NextWorkoutSnapshot = withContext(Dispatchers.IO) {
        val repo = ForgeApp.instance.repository
        val open = repo.workoutDao.observeOpenSession().first()
        val sessions = repo.workoutDao.observeSessions(60).first()
        val today = Dates.today()
        val active = repo.programDao.observeActive().first()
        val days = active?.let { repo.programDao.days(it.id) }.orEmpty()
        val next = NextWorkoutPicker.pick(days, sessions)
        val exercises = next?.let { repo.programDao.exercises(it.id) }.orEmpty()
        val last = NextWorkoutPicker.lastProgramSession(days, sessions)
        NextWorkoutSnapshot(
            programName = active?.name,
            dayName = next?.name,
            dayFocus = next?.focus,
            exerciseCount = exercises.size,
            exercisePreview = exercises.take(3).map { it.name },
            openSessionId = open?.id,
            openSessionTitle = open?.title,
            lastTrainedLabel = last?.let { Dates.dayLabel(it.localDate) },
            trainedToday = sessions.any { it.localDate == today && it.endedAt != null }
        )
    }
}
