package com.laksh.forge.feature.log

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.remote.FoodEstimate
import com.laksh.forge.data.remote.ProductInfo
import com.laksh.forge.data.remote.ProductNotFound
import com.laksh.forge.data.remote.Task
import com.laksh.forge.ui.forgeFactory
import com.laksh.forge.util.Dates
import com.laksh.forge.util.ImageUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How the user is logging. LABEL reads a printed nutrition panel directly with vision. */
enum class LogMode(val label: String) {
    PHOTO("Photo"), BARCODE("Barcode"), LABEL("Label"), MANUAL("Describe")
}

enum class Phase { IDLE, WORKING, RESULT, ERROR, SAVED }

data class LogState(
    val mode: LogMode = LogMode.PHOTO,
    val phase: Phase = Phase.IDLE,
    val statusText: String = "",
    val error: String? = null,
    val photo: Bitmap? = null,
    val estimate: FoodEstimate? = null,
    val rawKcal: Double? = null,
    val product: ProductInfo? = null,
    val grams: String = "100",
    val mealSlot: String = Dates.mealSlotNow(),
    val hint: String = "",
    val manualText: String = "",
    /** Set when a barcode scan misses, so the UI can offer the label-photo fallback. */
    val missingBarcode: String? = null,
    val savedName: String? = null,
    /** True while a save is in flight, so a double tap can't log the meal twice. */
    val saving: Boolean = false,
    val hasApiKey: Boolean = true,
    val safetyMarginPct: Int = 12,
    val storePhotos: Boolean = true
)

class LogViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val _state = MutableStateFlow(LogState())
    val state: StateFlow<LogState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val s = repo.settings()
            _state.update {
                it.copy(
                    hasApiKey = s.hasApiKey,
                    safetyMarginPct = s.safetyMarginPct,
                    storePhotos = s.storePhotos
                )
            }
        }
    }

    fun setMode(mode: LogMode) = _state.update {
        it.copy(
            mode = mode,
            phase = Phase.IDLE,
            error = null,
            estimate = null,
            product = null,
            photo = null,
            missingBarcode = null
        )
    }

    /**
     * Whether an AI provider is usable. Prefers the AI layer's own answer; the settings flag
     * covers the moment between saving a key and the AI layer picking it up.
     */
    private fun aiReady(settings: com.laksh.forge.data.prefs.ForgeSettings): Boolean =
        runCatching { repo.ai.isConfigured }.getOrDefault(false) || settings.hasApiKey

    fun setMealSlot(slot: String) = _state.update { it.copy(mealSlot = slot) }
    fun setHint(v: String) = _state.update { it.copy(hint = v) }
    fun setManualText(v: String) = _state.update { it.copy(manualText = v) }
    fun setGrams(v: String) = _state.update { it.copy(grams = v) }
    fun dismissError() = _state.update { it.copy(error = null, phase = Phase.IDLE) }
    /** Clears a save error shown on the review card without leaving the review. */
    fun clearError() = _state.update { it.copy(error = null) }

    fun reset() = _state.update {
        LogState(
            mode = it.mode,
            mealSlot = it.mealSlot,
            hasApiKey = it.hasApiKey,
            safetyMarginPct = it.safetyMarginPct,
            storePhotos = it.storePhotos
        )
    }

    // ---------------------------------------------------------------- photo

    fun analysePhoto(uri: Uri) = viewModelScope.launch {
        val settings = repo.settings()
        if (!aiReady(settings)) {
            _state.update { it.copy(phase = Phase.ERROR, error = "Add an AI provider key in Settings > AI to use photo estimates.") }
            return@launch
        }
        _state.update { it.copy(phase = Phase.WORKING, statusText = "Reading the photo...", error = null) }

        runCatching {
            val ctx = ForgeApp.instance
            val prepared = ImageUtils.prepare(ctx, uri)
            _state.update { it.copy(photo = prepared.bitmap, statusText = "Estimating calories...") }

            val context = buildBodyContext()
            val raw = repo.ai.estimateFoodFromPhoto(
                imageBase64 = prepared.base64,
                mediaType = prepared.mediaType,
                userHint = _state.value.hint.takeIf { it.isNotBlank() },
                bodyContext = context
            )
            raw
        }.onSuccess { raw ->
            val padded = repo.withSafetyMargin(raw, settings.safetyMarginPct)
            _state.update {
                it.copy(
                    phase = Phase.RESULT,
                    estimate = padded,
                    rawKcal = raw.kcal,
                    statusText = ""
                )
            }
        }.onFailure { e ->
            _state.update {
                it.copy(phase = Phase.ERROR, error = e.message ?: "Could not analyse that photo.")
            }
        }
    }

    // ---------------------------------------------------------------- text

    fun analyseText() = viewModelScope.launch {
        val text = _state.value.manualText.trim()
        if (text.isBlank()) return@launch
        val settings = repo.settings()
        if (!aiReady(settings)) {
            _state.update { it.copy(phase = Phase.ERROR, error = "Add an AI provider key in Settings > AI to estimate from a description.") }
            return@launch
        }
        _state.update { it.copy(phase = Phase.WORKING, statusText = "Estimating...", error = null) }

        runCatching {
            repo.ai.estimateFoodFromText(text, buildBodyContext())
        }.onSuccess { raw ->
            val padded = repo.withSafetyMargin(raw, settings.safetyMarginPct)
            _state.update {
                it.copy(phase = Phase.RESULT, estimate = padded, rawKcal = raw.kcal, statusText = "")
            }
        }.onFailure { e ->
            _state.update { it.copy(phase = Phase.ERROR, error = e.message ?: "Could not estimate that.") }
        }
    }

    // ---------------------------------------------------------------- barcode

    fun onBarcode(code: String) = viewModelScope.launch {
        if (_state.value.phase == Phase.WORKING) return@launch
        _state.update { it.copy(phase = Phase.WORKING, statusText = "Looking up $code...", error = null, missingBarcode = null) }

        repo.cachedProduct(code)?.let { cached ->
            showProduct(cached)
            return@launch
        }

        runCatching { repo.openFoodFacts.lookup(code) }
            .onSuccess { product ->
                repo.cacheProduct(product)
                showProduct(product)
            }
            .onFailure { e ->
                if (e is ProductNotFound) {
                    _state.update {
                        it.copy(
                            phase = Phase.ERROR,
                            missingBarcode = code,
                            error = "That barcode isn't in the food database. Photograph the nutrition label instead and it'll be read directly."
                        )
                    }
                } else {
                    _state.update {
                        it.copy(phase = Phase.ERROR, error = e.message ?: "Lookup failed.")
                    }
                }
            }
    }

    private fun showProduct(product: ProductInfo) {
        val defaultGrams = product.servingSizeG?.takeIf { it > 0 } ?: 100.0
        _state.update {
            it.copy(
                phase = Phase.RESULT,
                product = product,
                grams = defaultGrams.roundToInt().toString(),
                statusText = ""
            )
        }
    }

    /** Fallback path: read the printed nutrition panel with vision. */
    fun analyseLabel(uri: Uri) = viewModelScope.launch {
        val settings = repo.settings()
        if (!aiReady(settings)) {
            _state.update { it.copy(phase = Phase.ERROR, error = "Add an AI provider key in Settings > AI to read labels.") }
            return@launch
        }
        _state.update { it.copy(phase = Phase.WORKING, statusText = "Reading the label...", error = null) }

        runCatching {
            val prepared = ImageUtils.prepare(ForgeApp.instance, uri, ImageUtils.EDGE_LABEL)
            _state.update { it.copy(photo = prepared.bitmap) }
            repo.ai.readNutritionLabel(prepared.base64, prepared.mediaType)
        }.onSuccess { label ->
            if (label.kcal <= 0.0) {
                _state.update {
                    it.copy(phase = Phase.ERROR, error = label.note.ifBlank { "Couldn't read that label. Try a straighter, closer photo." })
                }
            } else {
                val product = repo.labelToProduct(label, _state.value.missingBarcode)
                repo.cacheProduct(product)
                showProduct(product)
            }
        }.onFailure { e ->
            _state.update { it.copy(phase = Phase.ERROR, error = e.message ?: "Could not read that label.") }
        }
    }

    // ---------------------------------------------------------------- saving

    fun saveEstimate(
        editedName: String,
        editedKcal: Double,
        protein: Double,
        carbs: Double,
        fat: Double
    ) = viewModelScope.launch {
        val s = _state.value
        val base = s.estimate ?: return@launch
        if (s.saving) return@launch
        _state.update { it.copy(saving = true) }

        runCatching {
            val settings = repo.settings()
            val photoPath = if (settings.storePhotos && s.photo != null) {
                runCatching { ImageUtils.savePhoto(ForgeApp.instance, s.photo) }.getOrNull()
            } else null

            val finalEstimate = base.copy(
                name = editedName.ifBlank { base.name },
                kcal = editedKcal,
                proteinG = protein,
                carbsG = carbs,
                fatG = fat
            )

            val id = repo.logEstimate(
                est = finalEstimate,
                rawKcal = s.rawKcal,
                photoPath = photoPath,
                mealSlot = s.mealSlot,
                source = if (s.mode == LogMode.PHOTO) "PHOTO" else "MANUAL"
            )
            // Record which provider/model produced a photo estimate, so estimate quality can
            // be audited later. Best effort: a failure here must not lose the logged meal.
            if (s.mode == LogMode.PHOTO) {
                runCatching {
                    val estimator = repo.ai.describe(Task.FOOD_PHOTO)
                    repo.foodDao.byId(id)?.let { repo.foodDao.update(it.copy(estimator = estimator)) }
                }
            }
            runCatching { repo.recomputeAdaptiveTdee() }
            finalEstimate.name
        }.onSuccess { name ->
            _state.update { it.copy(phase = Phase.SAVED, saving = false, savedName = name) }
        }.onFailure { e ->
            // Stay on the review card so nothing typed is lost; the card shows the message.
            _state.update { it.copy(saving = false, error = e.message ?: "Couldn't save that entry.") }
        }
    }

    fun saveProduct() = viewModelScope.launch {
        val s = _state.value
        val product = s.product ?: return@launch
        val grams = s.grams.replace(',', '.').toDoubleOrNull() ?: return@launch
        if (grams <= 0.0 || s.saving) return@launch
        _state.update { it.copy(saving = true) }

        runCatching {
            val id = repo.logProduct(product, grams, s.mealSlot)
            // logProduct records every product as a barcode hit; a label read is its own source.
            if (s.mode == LogMode.LABEL || product.barcode.startsWith("label_")) {
                repo.foodDao.byId(id)?.let { repo.foodDao.update(it.copy(source = "LABEL")) }
            }
            runCatching { repo.recomputeAdaptiveTdee() }
        }.onSuccess {
            _state.update { it.copy(phase = Phase.SAVED, saving = false, savedName = product.name) }
        }.onFailure { e ->
            // Stay on the review card so nothing typed is lost; the card shows the message.
            _state.update { it.copy(saving = false, error = e.message ?: "Couldn't save that entry.") }
        }
    }

    /**
     * Body context lets the estimator scale portions to the person rather than to an
     * average adult - a 95 kg lifter's "bowl of rice" is not the same as a 55 kg one's.
     */
    private suspend fun buildBodyContext(): String {
        val weight = runCatching { repo.weightDao.observeLatest().first() }.getOrNull()
        return if (weight != null) {
            "For portion scale: this person weighs about ${weight.kg.roundToInt()} kg."
        } else ""
    }

    companion object {
        val Factory = forgeFactory { LogViewModel(it) }
    }
}
