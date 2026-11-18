package com.laksh.forge.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.ThemeMode
import com.laksh.forge.ui.forgeFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Third-party work shipped in the app, shown under About. */
enum class OpenSourceNotice(
    val title: String,
    val licence: String,
    val summary: String,
    /** Full licence text bundled in assets, if any. */
    val asset: String?,
    val url: String
) {
    GOOGLE_SANS_FLEX(
        title = "Google Sans Flex",
        licence = "SIL Open Font License 1.1",
        summary = "The typeface used throughout Forge.",
        asset = "licenses/GoogleSansFlex-OFL.txt",
        url = "https://github.com/googlefonts/googlesans-flex"
    ),
    FREE_EXERCISE_DB(
        title = "free-exercise-db",
        licence = "Unlicense (public domain dedication)",
        summary = "Exercise names, instructions and muscle data, from yuhonas/free-exercise-db.",
        asset = null,
        url = "https://github.com/yuhonas/free-exercise-db"
    )
}

/** The licence sheet currently open, with its text once loaded. */
@Immutable
data class LicenceView(
    val notice: OpenSourceNotice,
    val text: String? = null,
    val loading: Boolean = false,
    val error: String? = null
)

@Immutable
data class SettingsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val settings: ForgeSettings = ForgeSettings(),
    val licence: LicenceView? = null
)

class SettingsViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val licence = MutableStateFlow<LicenceView?>(null)

    val state: StateFlow<SettingsUiState> = combine(
        repo.settingsStore.settings
            .map { SettingsUiState(loading = false, settings = it) }
            .catch { e ->
                if (e is CancellationException) throw e
                emit(SettingsUiState(loading = false, error = e.message ?: "Couldn't read settings."))
            },
        licence
    ) { base, lic -> base.copy(licence = lic) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    // ------------------------------------------------------------------ food logging

    fun setSafetyMargin(v: Int) = launchEdit { repo.settingsStore.setSafetyMargin(v) }
    fun setStorePhotos(v: Boolean) = launchEdit { repo.settingsStore.setStorePhotos(v) }

    // ------------------------------------------------------------------ appearance

    fun setThemeMode(v: ThemeMode) = launchEdit { repo.settingsStore.setThemeMode(v) }
    fun setDynamicColor(v: Boolean) = launchEdit { repo.settingsStore.setDynamicColor(v) }
    fun setAmoledBlack(v: Boolean) = launchEdit { repo.settingsStore.setAmoledBlack(v) }
    fun setHaptics(v: Boolean) = launchEdit { repo.settingsStore.setHaptics(v) }
    fun setReduceMotion(v: Boolean) = launchEdit { repo.settingsStore.setReduceMotion(v) }

    // ------------------------------------------------------------------ licences

    fun openLicence(notice: OpenSourceNotice) {
        val asset = notice.asset
        if (asset == null) {
            licence.value = LicenceView(notice)
            return
        }
        licence.value = LicenceView(notice, loading = true)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ForgeApp.instance.assets.open(asset).bufferedReader().use { it.readText() }
                }
            }
            // Ignore the result if the user already closed or switched the sheet.
            if (licence.value?.notice != notice) return@launch
            licence.value = result.fold(
                onSuccess = { LicenceView(notice, text = it) },
                onFailure = { LicenceView(notice, error = it.message ?: "Couldn't open the licence text.") }
            )
        }
    }

    fun closeLicence() { licence.value = null }

    private val _editError = MutableStateFlow<String?>(null)
    /** Last failed preference write, shown once by the screen and then cleared. */
    val editError: StateFlow<String?> = _editError

    fun clearEditError() { _editError.value = null }

    /** Preference writes are tiny; a failure (disk full) is surfaced rather than swallowed. */
    private fun launchEdit(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _editError.value = e.message ?: "Couldn't save that setting."
            }
        }
    }

    companion object {
        val Factory = forgeFactory { SettingsViewModel(it) }
    }
}
