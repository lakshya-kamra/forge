package com.laksh.forge

import android.app.Application
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.ai.AiKeys
import com.laksh.forge.data.ai.SecretStore
import com.laksh.forge.data.reminders.ReminderScheduler
import com.laksh.forge.data.wearables.WearableSyncWorker
import com.laksh.forge.feature.widgets.ForgeWidgets
import com.laksh.forge.data.prefs.SettingsStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ForgeApp : Application() {

    lateinit var repository: ForgeRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        instance = this

        // PDFBox on Android needs its font resources loaded before first use.
        PDFBoxResourceLoader.init(applicationContext)

        val settingsStore = SettingsStore(this)
        repository = ForgeRepository(this, settingsStore)

        // Keep a plain snapshot of settings so the API client can read the key and model
        // synchronously without every call site suspending on DataStore.
        settingsStore.settings
            .onEach { repository.updateSettingsCache(it) }
            .launchIn(appScope)

        SecretStore.attach(this)
        // Move a v1 plaintext API key into the Keystore-encrypted SecretStore, blank the
        // plaintext setting, and warm the in-memory key so the first AI call needs no decrypt.
        appScope.launch {
            runCatching { AiKeys.migrateLegacy(settingsStore) }
        }

        // Keeps the WorkManager reminder queue in step with the reminder toggles, and
        // re-anchors each reminder on every launch.
        ReminderScheduler.start(this, appScope)
        // Refreshes home-screen widgets whenever food, weights, sessions, programs or
        // settings change.
        ForgeWidgets.start(this, appScope)

        // Safety net for installs set up before v2: the wearables feature schedules its own
        // background sync, and scheduling is idempotent (KEEP), so this is cheap.
        appScope.launch {
            val s = settingsStore.settings.first()
            if (s.healthConnectEnabled || s.whoopConnected) {
                runCatching { WearableSyncWorker.schedule(this@ForgeApp) }
            }
        }

        // Learned maintenance otherwise only moves when something is logged, so a user who
        // opens the app after a break would see a stale figure. Recompute once on launch.
        appScope.launch {
            runCatching { repository.recomputeAdaptiveTdee() }
        }

        // The exercise catalog (~240 KB of JSON) is parsed lazily on first use; warm it on IO
        // so a tap on "How to" or an added exercise never parses it on the main thread.
        appScope.launch(Dispatchers.IO) {
            runCatching { com.laksh.forge.domain.exercise.ExerciseCatalog.preload() }
        }
        // Abandoned full-resolution camera captures (meal, label, body) never outlive a launch.
        appScope.launch(Dispatchers.IO) {
            runCatching { com.laksh.forge.util.ImageUtils.clearStaleCaptures(this@ForgeApp) }
        }
    }

    companion object {
        lateinit var instance: ForgeApp
            private set
    }
}
