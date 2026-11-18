package com.laksh.forge

import android.app.Application
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.prefs.SettingsStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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

        // Learned maintenance otherwise only moves when something is logged, so a user who
        // opens the app after a break would see a stale figure. Recompute once on launch.
        appScope.launch {
            runCatching { repository.recomputeAdaptiveTdee() }
        }
    }

    companion object {
        lateinit var instance: ForgeApp
            private set
    }
}
