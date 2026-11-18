package com.laksh.forge.data.wearables

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

// The wearables feature's own small store. SettingsStore belongs to another area, and this state
// (sync reports, origins seen, a pending OAuth state) is bookkeeping nobody else reads.
private val Context.wearableStore: DataStore<Preferences> by preferencesDataStore(name = "forge_wearables")

data class WearableStatus(
    val lastSyncAt: Long = 0L,
    val lastDays: Int = 0,
    val lastSources: List<String> = emptyList(),
    val lastErrors: List<String> = emptyList(),
    /** Health Connect origin package -> last time a sync saw data from it. */
    val seenOrigins: Map<String, Long> = emptyMap(),
    val whoopUserName: String? = null,
    val appleImportAt: Long = 0L,
    val appleImportSummary: String? = null
)

internal class WearableStatusStore private constructor(private val context: Context) {

    private object K {
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastDays = intPreferencesKey("last_days")
        val lastSources = stringPreferencesKey("last_sources")
        val lastErrors = stringPreferencesKey("last_errors")
        val seenOrigins = stringPreferencesKey("seen_origins")
        val whoopPendingState = stringPreferencesKey("whoop_pending_state")
        val whoopPendingAt = longPreferencesKey("whoop_pending_at")
        val whoopUserName = stringPreferencesKey("whoop_user_name")
        val appleImportAt = longPreferencesKey("apple_import_at")
        val appleImportSummary = stringPreferencesKey("apple_import_summary")
    }

    private val json = Json { ignoreUnknownKeys = true }

    val status: Flow<WearableStatus> = context.wearableStore.data
        // A corrupt store must never take the Wearables screen down with it.
        .catch { emit(emptyPreferences()) }
        .map { p ->
            WearableStatus(
                lastSyncAt = p[K.lastSyncAt] ?: 0L,
                lastDays = p[K.lastDays] ?: 0,
                lastSources = p[K.lastSources].splitLines(),
                lastErrors = p[K.lastErrors].splitLines(),
                seenOrigins = decodeOrigins(p[K.seenOrigins]),
                whoopUserName = p[K.whoopUserName],
                appleImportAt = p[K.appleImportAt] ?: 0L,
                appleImportSummary = p[K.appleImportSummary]
            )
        }

    suspend fun snapshot(): WearableStatus = status.first()

    suspend fun recordSync(at: Long, days: Int, sources: List<String>, errors: List<String>, origins: Set<String>) {
        context.wearableStore.edit { p ->
            p[K.lastSyncAt] = at
            p[K.lastDays] = days
            p[K.lastSources] = sources.joinToString("\n")
            p[K.lastErrors] = errors.joinToString("\n")
            if (origins.isNotEmpty()) {
                val merged = decodeOrigins(p[K.seenOrigins]).toMutableMap()
                origins.forEach { merged[it] = at }
                p[K.seenOrigins] = encodeOrigins(merged)
            }
        }
    }

    suspend fun setPendingWhoopState(state: String?, at: Long) {
        context.wearableStore.edit { p ->
            if (state == null) {
                p.remove(K.whoopPendingState)
                p.remove(K.whoopPendingAt)
            } else {
                p[K.whoopPendingState] = state
                p[K.whoopPendingAt] = at
            }
        }
    }

    suspend fun pendingWhoopState(): Pair<String, Long>? {
        val p = context.wearableStore.data.catch { emit(emptyPreferences()) }.first()
        val state = p[K.whoopPendingState] ?: return null
        return state to (p[K.whoopPendingAt] ?: 0L)
    }

    suspend fun setWhoopUserName(name: String?) {
        context.wearableStore.edit { p ->
            if (name.isNullOrBlank()) p.remove(K.whoopUserName) else p[K.whoopUserName] = name
        }
    }

    suspend fun setAppleImport(at: Long, summary: String) {
        context.wearableStore.edit { p ->
            p[K.appleImportAt] = at
            p[K.appleImportSummary] = summary
        }
    }

    private fun String?.splitLines(): List<String> =
        this?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun decodeOrigins(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.parseToJsonElement(raw).jsonObject.mapNotNull { (k, v) ->
                v.jsonPrimitive.longOrNull?.let { k to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun encodeOrigins(map: Map<String, Long>): String =
        buildJsonObject { map.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }.toString()

    companion object {
        @Volatile private var instance: WearableStatusStore? = null

        fun get(context: Context): WearableStatusStore =
            instance ?: synchronized(this) {
                instance ?: WearableStatusStore(context.applicationContext).also { instance = it }
            }
    }
}
