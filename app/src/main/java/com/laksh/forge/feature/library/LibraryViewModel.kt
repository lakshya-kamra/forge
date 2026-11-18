package com.laksh.forge.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.prefs.TrainingAge
import com.laksh.forge.domain.program.LibraryProgram
import com.laksh.forge.domain.program.ProgramLibrary
import com.laksh.forge.ui.forgeFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryFilters(
    val category: String? = null,
    val level: String? = null,
    val days: Int? = null,
    /** Equipment the lifter does NOT have; programs needing any of it are hidden. */
    val missing: Set<String> = emptySet()
) {
    val isActive: Boolean get() = category != null || level != null || days != null || missing.isNotEmpty()
}

/** What the recommendation was based on, shown so the pick never feels arbitrary. */
data class Profile(val level: String, val goal: String, val days: Int?, val levelKnown: Boolean)

data class LibraryState(
    val loading: Boolean = true,
    val error: String? = null,
    val all: List<LibraryProgram> = emptyList(),
    val visible: List<LibraryProgram> = emptyList(),
    val recommended: List<LibraryProgram> = emptyList(),
    val profile: Profile? = null,
    val filters: LibraryFilters = LibraryFilters(),
    val categories: List<String> = emptyList(),
    val levels: List<String> = emptyList(),
    val dayOptions: List<Int> = emptyList(),
    val equipment: List<String> = emptyList()
)

private data class Loaded(val programs: List<LibraryProgram>, val profile: Profile)

class LibraryViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val loaded = MutableStateFlow<Result<Loaded>?>(null)
    private val filters = MutableStateFlow(LibraryFilters())

    val state: StateFlow<LibraryState> = combine(loaded, filters) { result, f ->
        when {
            result == null -> LibraryState(loading = true, filters = f)
            result.isFailure -> LibraryState(
                loading = false,
                error = result.exceptionOrNull()?.message ?: "Could not load the program library.",
                filters = f
            )
            else -> {
                val data = result.getOrThrow()
                val all = data.programs
                LibraryState(
                    loading = false,
                    all = all,
                    visible = all.filter { matches(it, f) },
                    recommended = ProgramLibrary.rank(all, data.profile.level, data.profile.goal, data.profile.days).take(3),
                    profile = data.profile,
                    filters = f,
                    categories = all.map { it.category }.distinct().sorted(),
                    levels = listOf("BEGINNER", "INTERMEDIATE", "ADVANCED").filter { l -> all.any { it.level == l } },
                    dayOptions = all.map { it.daysPerWeek }.distinct().sorted(),
                    equipment = EQUIPMENT_ORDER.filter { e -> all.any { e in it.equipment } }
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        loaded.value = null
        loaded.value = runCatching {
            withContext(Dispatchers.IO) {
                val programs = ProgramLibrary.all()
                if (programs.isEmpty()) error("The program library is missing from this build.")
                Loaded(programs, profile())
            }
        }
    }

    private suspend fun profile(): Profile {
        val s = repo.settings()
        val level = when (s.trainingAge) {
            TrainingAge.NOVICE -> "BEGINNER"
            TrainingAge.INTERMEDIATE -> "INTERMEDIATE"
            TrainingAge.ADVANCED -> "ADVANCED"
            TrainingAge.UNKNOWN -> "BEGINNER"
        }
        // How often they actually train: finished sessions over the last four weeks.
        val since = System.currentTimeMillis() - 28L * 86_400_000
        val recent = repo.workoutDao.observeSessions(60).first()
            .count { it.endedAt != null && it.startedAt >= since }
        val days = if (recent >= 4) ((recent + 2) / 4).coerceIn(2, 6) else null
        return Profile(level, s.goal.name, days, s.trainingAge != TrainingAge.UNKNOWN)
    }

    private fun matches(p: LibraryProgram, f: LibraryFilters): Boolean =
        (f.category == null || p.category == f.category) &&
            (f.level == null || p.level == f.level) &&
            (f.days == null || p.daysPerWeek == f.days) &&
            p.equipment.none { it in f.missing }

    fun setCategory(c: String?) = filters.update { it.copy(category = if (it.category == c) null else c) }
    fun setLevel(l: String?) = filters.update { it.copy(level = if (it.level == l) null else l) }
    fun setDays(d: Int?) = filters.update { it.copy(days = if (it.days == d) null else d) }
    fun toggleEquipment(e: String) = filters.update {
        it.copy(missing = if (e in it.missing) it.missing - e else it.missing + e)
    }
    fun clearFilters() = filters.update { LibraryFilters() }

    companion object {
        val EQUIPMENT_ORDER = listOf("barbell", "rack", "bench", "dumbbells", "cable", "machines", "pullup_bar")
        val Factory = forgeFactory { LibraryViewModel(it) }
    }
}
