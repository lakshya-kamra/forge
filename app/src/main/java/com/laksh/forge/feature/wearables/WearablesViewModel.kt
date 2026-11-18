package com.laksh.forge.feature.wearables

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.health.HealthAvailability
import com.laksh.forge.data.health.HealthCapabilities
import com.laksh.forge.data.health.HealthOrigins
import com.laksh.forge.data.wearables.SyncReport
import com.laksh.forge.data.wearables.WearableStatus
import com.laksh.forge.data.wearables.WearableStatusStore
import com.laksh.forge.data.wearables.WearableSync
import com.laksh.forge.data.wearables.WearableSyncWorker
import com.laksh.forge.data.wearables.apple.AppleHealthImport
import com.laksh.forge.data.wearables.whoop.WhoopAuth
import com.laksh.forge.ui.forgeFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class BrandStatus(val label: String) {
    CONNECTED("Connected"),
    SEEN_BEFORE("Seen before"),
    VIA_HEALTH_CONNECT("Available via Health Connect"),
    NEEDS_SETUP("Needs setup"),
    BRIDGE_APP("Needs a bridge app"),
    NOT_POSSIBLE("Not possible on Android")
}

data class BrandUi(val brand: Brand, val status: BrandStatus, val detail: String?)

data class SeenSource(val id: String, val label: String, val brandName: String?, val lastSeen: Long)

data class HealthConnectUi(
    val checked: Boolean = false,
    val availability: HealthAvailability = HealthAvailability.NOT_SUPPORTED,
    val granted: Set<String> = emptySet(),
    val requestable: Set<String> = emptySet(),
    val capabilities: HealthCapabilities = HealthCapabilities(),
    /** Display names of data types, granted ones first. */
    val grantedLabels: List<String> = emptyList(),
    val missingLabels: List<String> = emptyList(),
    val historyGranted: Boolean = false,
    val backgroundGranted: Boolean = false
) {
    val anyDataGranted: Boolean get() = grantedLabels.isNotEmpty()
}

data class WhoopUi(
    val loaded: Boolean = false,
    val hasCredentials: Boolean = false,
    val clientIdHint: String? = null,
    val connected: Boolean = false,
    val userName: String? = null,
    val lastSync: Long = 0L,
    val auth: WhoopAuth.AuthStatus = WhoopAuth.AuthStatus.Idle,
    val busy: Boolean = false
)

data class WearablesUiState(
    val hc: HealthConnectUi = HealthConnectUi(),
    val whoop: WhoopUi = WhoopUi(),
    val apple: AppleHealthImport.State = AppleHealthImport.State.Idle,
    val status: WearableStatus = WearableStatus(),
    val lastHealthSync: Long = 0L,
    val syncing: Boolean = false,
    val brands: List<BrandUi> = emptyList(),
    val sources: List<SeenSource> = emptyList(),
    val message: String? = null,
    /** The report from a sync started on this screen, shown until dismissed. */
    val lastReport: SyncReport? = null
)

class WearablesViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val context: Context get() = ForgeApp.instance
    private val statusStore = WearableStatusStore.get(ForgeApp.instance)

    private val hc = MutableStateFlow(HealthConnectUi())
    private val whoopLocal = MutableStateFlow(WhoopUi())
    private val message = MutableStateFlow<String?>(null)
    private val report = MutableStateFlow<SyncReport?>(null)

    private val whoopFlow = combine(whoopLocal, WhoopAuth.status, repo.settingsStore.settings) { local, auth, s ->
        local.copy(auth = auth, lastSync = s.lastWhoopSync)
    }

    private val baseFlow = combine(hc, whoopFlow, AppleHealthImport.state, statusStore.status, WearableSync.running) {
            hcUi, whoop, apple, status, running ->
        WearablesUiState(hc = hcUi, whoop = whoop.copy(userName = status.whoopUserName ?: whoop.userName),
            apple = apple, status = status, syncing = running)
    }

    val state: StateFlow<WearablesUiState> = combine(
        baseFlow, repo.settingsStore.settings, message, report
    ) { base, settings, msg, rep ->
        val sources = seenSources(base.status)
        base.copy(
            lastHealthSync = settings.lastHealthSync,
            brands = brandStatuses(base.hc, base.whoop, sources, base.status),
            sources = sources,
            message = msg,
            lastReport = rep
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WearablesUiState())

    init {
        refreshHealthConnect()
        refreshWhoop()
        // When a browser sign-in finishes (in WhoopAuth's own scope), reload what's stored.
        viewModelScope.launch {
            WhoopAuth.status.collect { s ->
                if (s is WhoopAuth.AuthStatus.Connected || s is WhoopAuth.AuthStatus.Failed) refreshWhoop()
            }
        }
    }

    // ------------------------------------------------------------------ Health Connect

    fun healthContract() = repo.health.permissionContract()
    fun healthSettingsIntent() = repo.health.settingsIntent()
    fun healthInstallIntent() = repo.health.playStoreIntent()

    fun refreshHealthConnect() = viewModelScope.launch {
        val health = repo.health
        val availability = health.availability()
        if (availability != HealthAvailability.AVAILABLE) {
            hc.value = HealthConnectUi(checked = true, availability = availability)
            return@launch
        }
        val granted = health.grantedPermissions()
        val caps = health.capabilities()
        val requestable = health.requestablePermissions()
        val labels = health.permissionLabels.filter { (perm, _) ->
            perm in requestable && perm != health.historyPermission && perm != health.backgroundPermission
        }
        hc.value = HealthConnectUi(
            checked = true,
            availability = availability,
            granted = granted,
            requestable = requestable,
            capabilities = caps,
            grantedLabels = labels.filter { it.first in granted }.map { it.second },
            missingLabels = labels.filter { it.first !in granted }.map { it.second },
            historyGranted = health.historyPermission in granted,
            backgroundGranted = health.backgroundPermission in granted
        )
    }

    fun onHealthPermissionsResult(@Suppress("UNUSED_PARAMETER") result: Set<String>) = viewModelScope.launch {
        refreshHealthConnect().join()
        if (hc.value.anyDataGranted) {
            repo.settingsStore.setHealthConnectEnabled(true)
            WearableSyncWorker.schedule(context)
            syncNowInternal()
        } else {
            message.value = "No data types were allowed. Forge can't read anything from Health Connect until at least one is."
        }
    }

    // ------------------------------------------------------------------ sync

    fun syncNow() = viewModelScope.launch { syncNowInternal() }

    private suspend fun syncNowInternal() {
        val result = runCatching { WearableSync.syncAll() }
        result.onSuccess { r ->
            report.value = r
            if (r.days == 0 && r.errors.isEmpty() && r.sources.isEmpty()) {
                message.value = "Nothing new. If a device should be sending data, check its sharing steps below."
            }
        }.onFailure { e ->
            message.value = e.message ?: "Sync failed."
        }
    }

    fun dismissReport() { report.value = null }
    fun dismissMessage() { message.value = null }
    fun showMessage(text: String) { message.value = text }

    // ------------------------------------------------------------------ WHOOP

    fun refreshWhoop() = viewModelScope.launch {
        val creds = runCatching { WhoopAuth.credentials() }.getOrNull()
        val connected = runCatching { WhoopAuth.isConnected() }.getOrDefault(false)
        val hint = runCatching { WhoopAuth.clientIdHint() }.getOrNull()
        whoopLocal.update {
            it.copy(loaded = true, hasCredentials = creds != null, clientIdHint = hint, connected = connected, busy = false)
        }
    }

    fun saveWhoopCredentials(clientId: String, clientSecret: String) = viewModelScope.launch {
        whoopLocal.update { it.copy(busy = true) }
        runCatching { WhoopAuth.saveCredentials(clientId, clientSecret) }
            .onSuccess { message.value = "WHOOP app saved. Tap Connect to sign in." }
            .onFailure { message.value = it.message ?: "Couldn't save the WHOOP credentials." }
        refreshWhoop()
    }

    /** Builds the authorize URL; the screen opens it with an Activity context. */
    fun prepareWhoopSignIn(onReady: (Uri) -> Unit) = viewModelScope.launch {
        runCatching { WhoopAuth.prepareAuthorization(context) }
            .onSuccess(onReady)
            .onFailure { message.value = it.message ?: "Couldn't start WHOOP sign-in." }
    }

    fun disconnectWhoop() = viewModelScope.launch {
        whoopLocal.update { it.copy(busy = true) }
        runCatching { WhoopAuth.disconnect(context) }
            .onSuccess { message.value = "WHOOP disconnected. Data already synced stays in Forge." }
            .onFailure { message.value = it.message ?: "Couldn't disconnect WHOOP." }
        refreshWhoop()
    }

    fun forgetWhoopCredentials() = viewModelScope.launch {
        whoopLocal.update { it.copy(busy = true) }
        runCatching { WhoopAuth.forgetCredentials(context) }
            .onSuccess { message.value = "WHOOP client ID and secret removed from this phone." }
            .onFailure { message.value = it.message ?: "Couldn't remove the WHOOP credentials." }
        refreshWhoop()
    }

    fun clearWhoopStatus() = WhoopAuth.clearStatus()

    // ------------------------------------------------------------------ Apple Health

    fun importApple(uri: Uri) = AppleHealthImport.start(context, uri)
    fun cancelApple() = AppleHealthImport.cancel()
    fun dismissApple() = AppleHealthImport.dismiss()

    // ------------------------------------------------------------------ derived

    private fun seenSources(status: WearableStatus): List<SeenSource> =
        status.seenOrigins.entries
            .filter { it.key != HealthOrigins.HEALTH_CONNECT_PACKAGE }
            .map { (id, at) ->
                val info = HealthOrigins.describe(context, id)
                SeenSource(id, info.label, info.brandId?.let { BrandCatalog.byId(it)?.name }, at)
            }
            .sortedByDescending { it.lastSeen }

    private fun brandStatuses(
        hcUi: HealthConnectUi,
        whoop: WhoopUi,
        sources: List<SeenSource>,
        status: WearableStatus
    ): List<BrandUi> {
        val now = System.currentTimeMillis()
        val lastSeenByBrand = HashMap<String, Long>()
        sources.forEach { s ->
            val brandId = HealthOrigins.describe(context, s.id).brandId ?: return@forEach
            lastSeenByBrand[brandId] = maxOf(lastSeenByBrand[brandId] ?: 0L, s.lastSeen)
        }
        val hcReady = hcUi.availability == HealthAvailability.AVAILABLE && hcUi.anyDataGranted

        return BrandCatalog.brands.map { brand ->
            val seen = lastSeenByBrand[brand.id]
            val recent = seen != null && now - seen < RECENT_MS
            when (brand.route) {
                BrandRoute.NOT_POSSIBLE -> BrandUi(
                    brand, BrandStatus.NOT_POSSIBLE,
                    status.appleImportSummary?.let { "Imported from an export: $it" }
                )
                BrandRoute.BRIDGE_APP -> BrandUi(brand, BrandStatus.BRIDGE_APP, null)
                BrandRoute.HEALTH_CONNECT -> when {
                    brand.id == "whoop" && whoop.connected ->
                        BrandUi(brand, BrandStatus.CONNECTED, if (recent) "WHOOP API and Health Connect" else "WHOOP API")
                    recent -> BrandUi(brand, BrandStatus.CONNECTED, "Data seen via Health Connect")
                    seen != null -> BrandUi(brand, BrandStatus.SEEN_BEFORE, "No new data in the last two weeks")
                    !hcReady -> BrandUi(brand, BrandStatus.NEEDS_SETUP, "Allow Forge in Health Connect first")
                    brand.id == "whoop" && whoop.hasCredentials ->
                        BrandUi(brand, BrandStatus.NEEDS_SETUP, "Finish connecting the WHOOP API below")
                    else -> BrandUi(brand, BrandStatus.VIA_HEALTH_CONNECT, null)
                }
            }
        }
    }

    companion object {
        private const val RECENT_MS = 14L * 86_400_000L
        val Factory = forgeFactory { WearablesViewModel(it) }
    }
}
