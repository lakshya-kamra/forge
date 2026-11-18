package com.laksh.forge.data

import android.content.Context
import com.laksh.forge.data.db.CachedFood
import com.laksh.forge.data.db.DayTotals
import com.laksh.forge.data.db.FoodEntry
import com.laksh.forge.data.db.ForgeDatabase
import com.laksh.forge.data.db.Program
import com.laksh.forge.data.db.ProgramDay
import com.laksh.forge.data.db.ProgramExercise
import com.laksh.forge.data.db.WeightEntry
import com.laksh.forge.data.health.HealthConnectManager
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.SettingsStore
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.data.remote.AnthropicClient
import com.laksh.forge.data.remote.FoodEstimate
import com.laksh.forge.data.remote.LabelReading
import com.laksh.forge.data.remote.OpenFoodFactsClient
import com.laksh.forge.data.remote.ParsedProgram
import com.laksh.forge.data.remote.ProductInfo
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.util.Dates
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * Single place where features reach for data. Keeps view models free of DAO wiring and
 * concentrates the cross-cutting rules (safety margin, unit conversion, coach context).
 */
class ForgeRepository(
    context: Context,
    val settingsStore: SettingsStore
) {
    private val db = ForgeDatabase.get(context)
    val foodDao = db.foodDao()
    val weightDao = db.weightDao()
    val programDao = db.programDao()
    val workoutDao = db.workoutDao()
    val chatDao = db.chatDao()
    val healthDao = db.healthDao()
    private val foodCacheDao = db.foodCacheDao()

    val health = HealthConnectManager(context)
    val openFoodFacts = OpenFoodFactsClient()

    @Volatile private var cachedSettings: ForgeSettings = ForgeSettings()

    val ai: com.laksh.forge.data.ai.ForgeAi = AnthropicClient(
        apiKeyProvider = { cachedSettings.apiKey },
        modelProvider = { task -> cachedSettings.tier.modelFor(task) }
    )

    fun updateSettingsCache(s: ForgeSettings) { cachedSettings = s }

    suspend fun settings(): ForgeSettings = settingsStore.settings.first()

    // ------------------------------------------------------------------ food

    /**
     * Apply the user's safety margin on top of the model's already-high estimate, and keep
     * the pre-margin figure so the entry detail can show both.
     */
    fun withSafetyMargin(est: FoodEstimate, marginPct: Int): FoodEstimate {
        if (marginPct <= 0) return est
        val f = 1 + marginPct / 100.0
        return est.copy(
            kcal = est.kcal * f,
            proteinG = est.proteinG,          // protein is not padded - overstating it is unhelpful
            carbsG = est.carbsG * f,
            fatG = est.fatG * f
        )
    }

    suspend fun logEstimate(
        est: FoodEstimate,
        rawKcal: Double?,
        photoPath: String?,
        mealSlot: String,
        source: String
    ): Long {
        val now = System.currentTimeMillis()
        return foodDao.insert(
            FoodEntry(
                timestamp = now,
                localDate = Dates.dayOf(now),
                name = est.name,
                servingDesc = est.servingDesc,
                kcal = est.kcal.roundTo1(),
                proteinG = est.proteinG.roundTo1(),
                carbsG = est.carbsG.roundTo1(),
                fatG = est.fatG.roundTo1(),
                fiberG = est.fiberG.roundTo1(),
                source = source,
                mealSlot = mealSlot,
                photoPath = photoPath,
                confidence = est.confidence,
                estimateNote = est.note.ifBlank { null },
                rawKcal = rawKcal?.roundTo1()
            )
        )
    }

    suspend fun logProduct(product: ProductInfo, grams: Double, mealSlot: String): Long {
        val now = System.currentTimeMillis()
        val f = grams / 100.0
        return foodDao.insert(
            FoodEntry(
                timestamp = now,
                localDate = Dates.dayOf(now),
                name = product.name,
                brand = product.brand,
                servingDesc = "${grams.roundToInt()} g",
                quantity = f,
                kcal = (product.kcalPer100g * f).roundTo1(),
                proteinG = (product.proteinPer100g * f).roundTo1(),
                carbsG = (product.carbsPer100g * f).roundTo1(),
                fatG = (product.fatPer100g * f).roundTo1(),
                fiberG = (product.fiberPer100g * f).roundTo1(),
                source = "BARCODE",
                mealSlot = mealSlot,
                confidence = "HIGH",
                barcode = product.barcode
            )
        )
    }

    /** Repeat a previously logged item exactly, for the quick-add list. */
    suspend fun repeatEntry(entry: FoodEntry, mealSlot: String): Long {
        val now = System.currentTimeMillis()
        return foodDao.insert(
            entry.copy(
                id = 0,
                timestamp = now,
                localDate = Dates.dayOf(now),
                mealSlot = mealSlot,
                photoPath = null,
                source = "FAVOURITE"
            )
        )
    }

    suspend fun deleteFood(entry: FoodEntry) {
        com.laksh.forge.util.ImageUtils.deletePhoto(entry.photoPath)
        foodDao.delete(entry)
    }

    suspend fun cachedProduct(barcode: String): ProductInfo? = foodCacheDao.get(barcode)?.let {
        ProductInfo(
            barcode = it.barcode,
            name = it.name,
            brand = it.brand,
            kcalPer100g = it.kcalPer100g,
            proteinPer100g = it.proteinPer100g,
            carbsPer100g = it.carbsPer100g,
            fatPer100g = it.fatPer100g,
            fiberPer100g = it.fiberPer100g,
            servingSizeG = it.servingSizeG,
            servingDesc = it.servingDesc
        )
    }

    suspend fun cacheProduct(p: ProductInfo) = foodCacheDao.put(
        CachedFood(
            barcode = p.barcode,
            name = p.name,
            brand = p.brand,
            servingDesc = p.servingDesc,
            kcalPer100g = p.kcalPer100g,
            proteinPer100g = p.proteinPer100g,
            carbsPer100g = p.carbsPer100g,
            fatPer100g = p.fatPer100g,
            fiberPer100g = p.fiberPer100g,
            servingSizeG = p.servingSizeG,
            cachedAt = System.currentTimeMillis()
        )
    )

    /** Convert a scanned label into the same per-100g shape as a database hit. */
    fun labelToProduct(label: LabelReading, barcode: String?): ProductInfo {
        val per100 = if (label.basis == "PER_SERVING" && (label.servingSizeG ?: 0.0) > 0) {
            100.0 / label.servingSizeG!!
        } else 1.0
        return ProductInfo(
            barcode = barcode ?: "label_${System.currentTimeMillis()}",
            name = label.name,
            brand = label.brand,
            kcalPer100g = label.kcal * per100,
            proteinPer100g = label.proteinG * per100,
            carbsPer100g = label.carbsG * per100,
            fatPer100g = label.fatG * per100,
            fiberPer100g = label.fiberG * per100,
            servingSizeG = label.servingSizeG,
            servingDesc = label.servingDesc
        )
    }

    // ------------------------------------------------------------------ weight

    suspend fun logWeight(displayValue: Double, unit: WeightUnit, bodyFat: Double?, note: String?) {
        val now = System.currentTimeMillis()
        weightDao.insert(
            WeightEntry(
                timestamp = now,
                localDate = Dates.dayOf(now),
                kg = NutritionMath.displayToKg(displayValue, unit),
                bodyFatPct = bodyFat,
                note = note?.ifBlank { null }
            )
        )
        recomputeAdaptiveTdee()
    }

    /** Refresh the learned TDEE after anything that could change it. */
    suspend fun recomputeAdaptiveTdee() {
        val weights = weightDao.since(System.currentTimeMillis() - 60L * 86_400_000)
        val totals = foodDao.observeTotalsRange(Dates.daysAgo(60), Dates.today()).first()
        val tdee = NutritionMath.adaptiveTdee(weights, totals)
        settingsStore.setLearnedTdee(tdee ?: 0.0)
    }

    // ------------------------------------------------------------------ health

    /** Pull wearable data and merge any scale weights we do not already have. */
    suspend fun syncHealth(): Int {
        val dailies = health.readDailies(45)
        if (dailies.isNotEmpty()) healthDao.upsertAll(dailies)

        var importedWeights = 0
        health.readWeights(90).forEach { w ->
            val date = Dates.dayOf(w.timeMillis)
            if (weightDao.countOn(date) == 0) {
                weightDao.insert(
                    WeightEntry(
                        timestamp = w.timeMillis,
                        localDate = date,
                        kg = w.kg,
                        source = "HEALTH_CONNECT"
                    )
                )
                importedWeights++
            }
        }
        settingsStore.setLastHealthSync(System.currentTimeMillis())
        if (importedWeights > 0) recomputeAdaptiveTdee()
        return dailies.size
    }

    // ------------------------------------------------------------------ programs

    suspend fun saveProgram(parsed: ParsedProgram, sourceFile: String?): Long {
        val programId = programDao.insertProgram(
            Program(
                name = parsed.name,
                author = parsed.author,
                sourceFile = sourceFile,
                notes = parsed.notes,
                createdAt = System.currentTimeMillis()
            )
        )
        parsed.days.forEachIndexed { di, day ->
            val dayId = programDao.insertDay(
                ProgramDay(programId = programId, name = day.name, orderIdx = di, focus = day.focus)
            )
            day.exercises.forEachIndexed { ei, ex ->
                programDao.insertExercise(
                    ProgramExercise(
                        dayId = dayId,
                        name = ex.name,
                        orderIdx = ei,
                        sets = ex.sets,
                        repLow = ex.repLow,
                        repHigh = ex.repHigh,
                        rirTarget = ex.rir,
                        restSeconds = ex.restSeconds,
                        notes = ex.notes,
                        substitutions = ex.substitutions
                    )
                )
            }
        }
        programDao.clearActive()
        programDao.setActive(programId)
        return programId
    }

    suspend fun activateProgram(id: Long) {
        programDao.clearActive()
        programDao.setActive(id)
    }

    // ------------------------------------------------------------------ coach context

    /**
     * A compact snapshot of everything the coach should know. Kept terse on purpose:
     * it is prepended to every turn, and dense numbers beat prose here.
     */
    suspend fun buildCoachContext(): String {
        val s = settings()
        val today = Dates.today()
        val latestWeight = weightDao.observeLatest().first()
        val weightKg = latestWeight?.kg ?: 75.0
        val weights = weightDao.since(System.currentTimeMillis() - 120L * 86_400_000)
        val trend = NutritionMath.trend(weights)
        val adaptive = s.learnedTdee.takeIf { it > 0 }
        val targets = NutritionMath.targets(s, weightKg, adaptive)

        val totals14 = foodDao.observeTotalsRange(Dates.daysAgo(14), today).first()
        val todayTotals = totals14.firstOrNull { it.localDate == today }
        val loggedDays = totals14.filter { it.kcal > 400 }

        val sessions = workoutDao.observeSessions(10).first()
        val recentSets = workoutDao.recentSets(40)
        val activeProgram = programDao.observeActive().first()
        val healthRows = healthDao.range(Dates.daysAgo(7), today)

        val unit = s.weightUnit.label
        fun w(kg: Double) = "%.1f %s".format(NutritionMath.kgToDisplay(kg, s.weightUnit), unit)

        return buildString {
            appendLine("<user_data>")
            appendLine("Today: $today")
            appendLine("Goal: ${s.goal.label} (${s.goal.blurb})")
            appendLine("Profile: ${s.sex.name.lowercase()}, age ${s.age}, height ${s.heightCm.roundToInt()} cm")
            appendLine("Current weight: ${latestWeight?.let { w(it.kg) } ?: "not logged"}")
            trend.latestSmoothed?.let { appendLine("Trend weight (smoothed): ${w(it)}") }
            if (trend.points.size >= 4) {
                appendLine("Weight trend: %+.2f kg/week over %d weigh-ins".format(trend.kgPerWeek, trend.points.size))
            }
            appendLine()
            appendLine("Daily targets: ${targets.kcal} kcal, ${targets.proteinG}g protein, ${targets.carbsG}g carbs, ${targets.fatG}g fat")
            appendLine("Target basis: ${targets.basis}")
            if (adaptive != null) {
                appendLine("Learned maintenance (from intake vs weight change): ${adaptive.roundToInt()} kcal")
            } else {
                appendLine("Learned maintenance: not enough data yet (needs ~2 weeks of consistent logging plus weigh-ins)")
            }
            appendLine()

            if (todayTotals != null) {
                appendLine("Today so far: ${todayTotals.kcal.roundToInt()} kcal, ${todayTotals.proteinG.roundToInt()}g P, ${todayTotals.carbsG.roundToInt()}g C, ${todayTotals.fatG.roundToInt()}g F (${todayTotals.items} items)")
            } else {
                appendLine("Today so far: nothing logged")
            }
            if (loggedDays.isNotEmpty()) {
                val avgK = loggedDays.sumOf { it.kcal } / loggedDays.size
                val avgP = loggedDays.sumOf { it.proteinG } / loggedDays.size
                appendLine("Last 14 days: logged on ${loggedDays.size} days, averaging ${avgK.roundToInt()} kcal and ${avgP.roundToInt()}g protein")
                appendLine("Daily intake: " + loggedDays.takeLast(14).joinToString(", ") { "${Dates.shortLabel(it.localDate)} ${it.kcal.roundToInt()}" })
            } else {
                appendLine("Last 14 days: no food logged")
            }
            appendLine()

            appendLine("Active program: ${activeProgram?.name ?: "none imported"}")
            if (sessions.isNotEmpty()) {
                appendLine("Recent sessions:")
                sessions.take(6).forEach {
                    appendLine("  ${Dates.shortLabel(it.localDate)} - ${it.title}${it.endedAt?.let { _ -> "" } ?: " (in progress)"}")
                }
            } else {
                appendLine("Recent sessions: none logged")
            }
            if (recentSets.isNotEmpty()) {
                val byLift = recentSets.groupBy { it.exerciseName }
                appendLine("Recent top sets:")
                byLift.entries.take(10).forEach { (name, sets) ->
                    val best = sets.maxByOrNull { it.weightKg }
                    if (best != null) {
                        appendLine("  $name: ${"%.1f".format(best.weightKg)} kg x ${best.reps}")
                    }
                }
            }
            appendLine()

            if (healthRows.isNotEmpty()) {
                appendLine("Wearable (last 7 days, from Health Connect):")
                healthRows.forEach { h ->
                    val parts = buildList {
                        h.steps?.let { add("$it steps") }
                        h.activeKcal?.let { add("${it.roundToInt()} active kcal") }
                        h.sleepMinutes?.let { add("${it / 60}h${it % 60}m sleep") }
                        h.restingHr?.let { add("RHR $it") }
                    }
                    if (parts.isNotEmpty()) appendLine("  ${Dates.shortLabel(h.localDate)}: ${parts.joinToString(", ")}")
                }
            } else {
                appendLine("Wearable: no Health Connect data (Zepp sync may be off)")
            }
            appendLine("</user_data>")
        }
    }

    private fun Double.roundTo1(): Double = kotlin.math.round(this * 10) / 10.0
}
