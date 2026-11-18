package com.laksh.forge.feature.tools

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.Locale

// Its own file, separate from forge_settings, so the tools feature never touches the shared
// SettingsStore and a corrupt gym profile can't take the rest of the settings down with it.
private val Context.gymProfileStore: DataStore<Preferences> by preferencesDataStore(name = "forge_gym_profile")

/**
 * Persists the [GymProfile]. Plates are stored as "25:4;20:2;..." - a tiny, human-readable
 * encoding that needs no serializer and survives new denominations being added later.
 */
object GymProfileStore {

    private val BAR = doublePreferencesKey("bar_kg")
    private val PLATES = stringPreferencesKey("plates")
    private val USE_COLLARS = booleanPreferencesKey("use_collars")
    private val COLLAR_KG = doublePreferencesKey("collar_kg")

    fun profile(context: Context): Flow<GymProfile> =
        context.applicationContext.gymProfileStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { p ->
                val defaults = GymProfile()
                GymProfile(
                    barKg = p[BAR]?.takeIf { it > 0 } ?: defaults.barKg,
                    plates = p[PLATES]?.let(::decodePlates) ?: defaults.plates,
                    useCollars = p[USE_COLLARS] ?: defaults.useCollars,
                    collarKg = p[COLLAR_KG]?.takeIf { it >= 0 } ?: defaults.collarKg
                )
            }

    suspend fun current(context: Context): GymProfile = profile(context).first()

    suspend fun update(context: Context, transform: (GymProfile) -> GymProfile) {
        context.applicationContext.gymProfileStore.edit { p ->
            val defaults = GymProfile()
            val now = GymProfile(
                barKg = p[BAR]?.takeIf { it > 0 } ?: defaults.barKg,
                plates = p[PLATES]?.let(::decodePlates) ?: defaults.plates,
                useCollars = p[USE_COLLARS] ?: defaults.useCollars,
                collarKg = p[COLLAR_KG]?.takeIf { it >= 0 } ?: defaults.collarKg
            )
            val next = transform(now)
            p[BAR] = next.barKg
            p[PLATES] = encodePlates(next.plates)
            p[USE_COLLARS] = next.useCollars
            p[COLLAR_KG] = next.collarKg
        }
    }

    suspend fun reset(context: Context) {
        context.applicationContext.gymProfileStore.edit { it.clear() }
    }

    internal fun encodePlates(plates: List<PlateStock>): String =
        plates.joinToString(";") { "${String.format(Locale.ROOT, "%.3f", it.kg)}:${it.perSide}" }

    /** Null on anything malformed, so the caller falls back to the default inventory. */
    internal fun decodePlates(raw: String): List<PlateStock>? = runCatching {
        raw.split(';').filter { it.isNotBlank() }.map { part ->
            val (kg, n) = part.split(':')
            PlateStock(kg.toDouble(), n.toInt().coerceIn(0, 20))
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
