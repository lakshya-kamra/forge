package com.laksh.forge.feature.wearables

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.data.health.HealthAvailability
import com.laksh.forge.data.wearables.SyncReport
import com.laksh.forge.data.wearables.WearableStatus
import com.laksh.forge.data.wearables.apple.AppleHealthImport
import com.laksh.forge.data.wearables.whoop.WhoopAuth
import com.laksh.forge.data.wearables.whoop.WhoopConfig
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.util.Dates

private val APPLE_EXPORT_MIME_TYPES = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream",
    "text/xml",
    "application/xml"
)

@Composable
fun WearablesScreen(onBack: () -> Unit) {
    val vm: WearablesViewModel = viewModel(factory = WearablesViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val permissionLauncher = rememberLauncherForActivityResult(vm.healthContract()) { granted ->
        vm.onHealthPermissionsResult(granted)
    }
    val appleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importApple(uri)
    }

    // Coming back from Health Connect, the Play Store or the browser can change what's granted.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshHealthConnect()
        vm.refreshWhoop()
    }

    val startIntent: (Intent, String) -> Unit = { intent, failure ->
        runCatching { context.startActivity(intent) }.onFailure { vm.showMessage(failure) }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var expandedBrand by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) {
        LargeFlexibleTopAppBar(
            title = { Text("Wearables") },
            subtitle = { Text("Last sync: ${syncLabel(state.status.lastSyncAt)}") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = MaterialTheme.colorScheme.background
            ),
            scrollBehavior = scrollBehavior
        )

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.message?.let { msg ->
                item(key = "message", contentType = "message") {
                    MessageCard(msg, onDismiss = vm::dismissMessage, modifier = Modifier.animateItem())
                }
            }

            item(key = "sync", contentType = "sync") {
                SyncCard(
                    syncing = state.syncing,
                    status = state.status,
                    report = state.lastReport,
                    onSync = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        vm.syncNow()
                    },
                    onDismissReport = vm::dismissReport,
                    modifier = Modifier.animateItem()
                )
            }

            item(key = "hc_header", contentType = "header") { SectionHeader("Health Connect") }
            item(key = "hc", contentType = "hc") {
                HealthConnectCard(
                    hc = state.hc,
                    onGrant = { permissionLauncher.launch(state.hc.requestable) },
                    onInstall = {
                        startIntent(vm.healthInstallIntent(), "Couldn't open the Play Store.")
                    },
                    onOpenSettings = {
                        startIntent(vm.healthSettingsIntent(), "Couldn't open Health Connect.")
                    },
                    modifier = Modifier.animateItem()
                )
            }

            if (state.sources.isNotEmpty()) {
                item(key = "sources_header", contentType = "header") { SectionHeader("Sources seen") }
                item(key = "sources", contentType = "sources") {
                    SourcesCard(state.sources, modifier = Modifier.animateItem())
                }
            }

            item(key = "whoop_header", contentType = "header") { SectionHeader("WHOOP API") }
            item(key = "whoop", contentType = "whoop") {
                WhoopCard(
                    whoop = state.whoop,
                    onSave = vm::saveWhoopCredentials,
                    onConnect = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        vm.prepareWhoopSignIn { uri -> WhoopAuth.launch(context, uri) }
                    },
                    onDisconnect = vm::disconnectWhoop,
                    onForget = vm::forgetWhoopCredentials,
                    onDismissStatus = vm::clearWhoopStatus,
                    onOpenDashboard = {
                        openInBrowser(context, Uri.parse(WhoopConfig.DASHBOARD_URL)) {
                            vm.showMessage("No browser is installed to open the WHOOP dashboard.")
                        }
                    },
                    onCopyRedirect = {
                        copyToClipboard(context, "WHOOP redirect URI", WhoopConfig.REDIRECT_URI)
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        vm.showMessage("Redirect URI copied.")
                    },
                    modifier = Modifier.animateItem()
                )
            }

            item(key = "apple_header", contentType = "header") { SectionHeader("Apple Health") }
            item(key = "apple", contentType = "apple") {
                AppleCard(
                    apple = state.apple,
                    status = state.status,
                    onPick = { appleLauncher.launch(APPLE_EXPORT_MIME_TYPES) },
                    onCancel = {
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                        vm.cancelApple()
                    },
                    onDismiss = vm::dismissApple,
                    modifier = Modifier.animateItem()
                )
            }

            item(key = "brands_header", contentType = "header") { SectionHeader("Brands") }
            items(
                items = state.brands,
                key = { "brand_${it.brand.id}" },
                contentType = { "brand" }
            ) { b ->
                BrandCard(
                    ui = b,
                    expanded = expandedBrand == b.brand.id,
                    onToggle = {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        expandedBrand = if (expandedBrand == b.brand.id) null else b.brand.id
                    },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- cards

@Composable
private fun MessageCard(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    ForgeCard(modifier = modifier, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Info, null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("OK") }
        }
    }
}

@Composable
private fun SyncCard(
    syncing: Boolean,
    status: WearableStatus,
    report: SyncReport?,
    onSync: () -> Unit,
    onDismissReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    ForgeCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Every 6 hours in the background while the battery isn't low, and whenever you tap Sync now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onSync, shapes = ButtonDefaults.shapes(), enabled = !syncing) {
                if (syncing) {
                    LoadingIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Syncing")
                } else {
                    Icon(Icons.Outlined.Sync, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sync now")
                }
            }
        }

        val days = report?.days ?: status.lastDays
        val sources = report?.sources ?: status.lastSources
        val errors = report?.errors ?: status.lastErrors
        if (status.lastSyncAt > 0L || report != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                if (days == 0) "Last sync found nothing new."
                else "Last sync updated $days ${if (days == 1) "day" else "days"}.",
                style = MaterialTheme.typography.bodyMedium
            )
            if (sources.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    sources.forEach { Pill(it, filled = true) }
                }
            }
            errors.forEach { e ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.ErrorOutline, null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(e, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (report != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismissReport, shapes = ButtonDefaults.shapes()) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun HealthConnectCard(
    hc: HealthConnectUi,
    onGrant: () -> Unit,
    onInstall: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    ForgeCard(modifier = modifier) {
        if (!hc.checked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(32.dp))
                Spacer(Modifier.width(10.dp))
                Text("Checking Health Connect…", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            HealthConnectBody(hc, onGrant, onInstall, onOpenSettings)
        }
    }
}

@Composable
private fun HealthConnectBody(
    hc: HealthConnectUi,
    onGrant: () -> Unit,
    onInstall: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column {
        Text(
            "Most brands reach Forge through Health Connect: each vendor app writes to it, and Forge reads from it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        when (hc.availability) {
            HealthAvailability.NOT_SUPPORTED -> StatusLine(
                icon = Icons.Outlined.Block,
                text = "Health Connect isn't available on this device.",
                tint = MaterialTheme.colorScheme.error
            )
            HealthAvailability.NOT_INSTALLED -> {
                StatusLine(
                    icon = Icons.Outlined.ErrorOutline,
                    text = "Health Connect needs installing or updating before Forge can read anything.",
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onInstall, shapes = ButtonDefaults.shapes()) { Text("Get Health Connect") }
            }
            HealthAvailability.AVAILABLE -> {
                val total = hc.grantedLabels.size + hc.missingLabels.size
                if (hc.anyDataGranted) {
                    StatusLine(
                        icon = Icons.Outlined.CheckCircle,
                        text = "Forge can read ${hc.grantedLabels.size} of $total data types.",
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    StatusLine(
                        icon = Icons.Outlined.HealthAndSafety,
                        text = "Forge isn't allowed to read anything yet.",
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (total > 0) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        hc.grantedLabels.forEach { Pill(it, filled = true) }
                        hc.missingLabels.forEach {
                            Pill(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (hc.capabilities.history) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (hc.historyGranted) "Past data: allowed."
                        else "Past data: not allowed. Without it, Health Connect only shares data from up to 30 days before you first allowed Forge.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (hc.capabilities.background) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (hc.backgroundGranted) "Background access: allowed, so the 6-hourly sync can read Health Connect."
                        else "Background access: not allowed, so Health Connect only syncs while Forge is open.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(14.dp))
                val needsMore = hc.missingLabels.isNotEmpty() ||
                    (hc.capabilities.history && !hc.historyGranted) ||
                    (hc.capabilities.background && !hc.backgroundGranted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (needsMore && hc.requestable.isNotEmpty()) {
                        Button(onClick = onGrant, shapes = ButtonDefaults.shapes()) {
                            Text(if (hc.anyDataGranted) "Allow more" else "Allow access")
                        }
                    }
                    OutlinedButton(onClick = onOpenSettings) { Text("Open Health Connect") }
                }
            }
        }
    }
}

@Composable
private fun SourcesCard(sources: List<SeenSource>, modifier: Modifier = Modifier) {
    ForgeCard(modifier = modifier) {
        Text(
            "Apps that have written data Forge read, newest first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        sources.forEach { s ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Watch, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        listOfNotNull(s.brandName, s.id.takeIf { it != s.label }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    syncLabel(s.lastSeen),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WhoopCard(
    whoop: WhoopUi,
    onSave: (String, String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    onDismissStatus: () -> Unit,
    onOpenDashboard: () -> Unit,
    onCopyRedirect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var clientId by rememberSaveable { mutableStateOf("") }
    var clientSecret by rememberSaveable { mutableStateOf("") }
    var showSecret by rememberSaveable { mutableStateOf(false) }

    ForgeCard(modifier = modifier) {
        Text(
            "Recovery, HRV (RMSSD), strain and skin temperature only come from WHOOP's own API - they never reach Health Connect. WHOOP makes every app register with it, so you register your own personal one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        when (val auth = whoop.auth) {
            WhoopAuth.AuthStatus.WaitingForBrowser -> {
                StatusLine(Icons.Outlined.Link, "Finish signing in to WHOOP in the browser.", MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
            }
            WhoopAuth.AuthStatus.Exchanging -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Connecting to WHOOP…", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(10.dp))
            }
            is WhoopAuth.AuthStatus.Failed -> {
                StatusLine(Icons.Outlined.ErrorOutline, auth.message, MaterialTheme.colorScheme.error)
                TextButton(onClick = onDismissStatus, shapes = ButtonDefaults.shapes()) { Text("Dismiss") }
                Spacer(Modifier.height(4.dp))
            }
            else -> Unit
        }

        when {
            !whoop.loaded -> LoadingIndicator(Modifier.size(32.dp))

            whoop.connected -> {
                StatusLine(
                    Icons.Outlined.CheckCircle,
                    whoop.userName?.let { "Connected as $it." } ?: "Connected.",
                    MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Last WHOOP sync: ${syncLabel(whoop.lastSync)}. Recovery is shown exactly as WHOOP reports it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDisconnect, enabled = !whoop.busy) { Text("Disconnect") }
                    TextButton(onClick = onForget, shapes = ButtonDefaults.shapes(), enabled = !whoop.busy) {
                        Text("Remove app credentials")
                    }
                }
            }

            whoop.hasCredentials && !editing -> {
                StatusLine(
                    Icons.Outlined.Lock,
                    "Your WHOOP app${whoop.clientIdHint?.let { " ($it)" } ?: ""} is saved on this phone.",
                    MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onConnect,
                        shapes = ButtonDefaults.shapes(),
                        enabled = !whoop.busy && whoop.auth !is WhoopAuth.AuthStatus.Exchanging
                    ) { Text("Connect WHOOP") }
                    TextButton(onClick = { editing = true }, shapes = ButtonDefaults.shapes()) { Text("Change") }
                    TextButton(onClick = onForget, shapes = ButtonDefaults.shapes(), enabled = !whoop.busy) { Text("Remove") }
                }
            }

            else -> {
                Text("Set up your WHOOP app", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                NumberedSteps(
                    listOf(
                        "Open the WHOOP Developer Dashboard and sign in with your WHOOP account.",
                        "Create a Team, then create an App (each account can have up to ${WhoopConfig.MAX_APPS_PER_ACCOUNT}).",
                        "Add the redirect URI below to the app, exactly as written.",
                        "Forge asks for: ${WhoopConfig.SCOPES.joinToString(", ")}. If the dashboard lets you choose scopes, enable all of them - 'offline' is what keeps you signed in.",
                        "New apps start on the Sandbox tier: up to ${WhoopConfig.SANDBOX_MEMBERS} WHOOP members, no approval needed. Just you is fine.",
                        "Copy the app's Client ID and Client Secret into the fields below."
                    )
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        WhoopConfig.REDIRECT_URI,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onCopyRedirect) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy redirect URI")
                    }
                }
                OutlinedButton(onClick = onOpenDashboard) { Text("Open WHOOP Developer Dashboard") }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text("Client ID") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = clientSecret,
                    onValueChange = { clientSecret = it },
                    label = { Text("Client secret") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            Icon(
                                if (showSecret) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showSecret) "Hide secret" else "Show secret"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Stored encrypted on this phone (Android Keystore) and sent only to WHOOP's token endpoint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSave(clientId, clientSecret)
                            clientSecret = ""
                            editing = false
                        },
                        shapes = ButtonDefaults.shapes(),
                        enabled = clientId.isNotBlank() && clientSecret.isNotBlank() && !whoop.busy
                    ) { Text("Save") }
                    if (editing) {
                        TextButton(onClick = { editing = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppleCard(
    apple: AppleHealthImport.State,
    status: WearableStatus,
    onPick: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ForgeCard(modifier = modifier) {
        StatusLine(
            Icons.Outlined.Block,
            "Apple Watch can't sync to an Android phone.",
            MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "It pairs only with an iPhone, and Apple has no Health app for Android and no cloud API. " +
                "If you have an iPhone, you can bring your history over: Health app → Summary → tap your profile picture → " +
                "Export All Health Data, share the zip to this phone (e.g. via Google Drive), then import it here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Imported: steps, active energy, distance, heart rate, resting heart rate, sleep, workouts, weight, body fat, " +
                "lean mass, VO2 max, blood oxygen and respiratory rate. Apple measures HRV as SDNN, not RMSSD, so it is kept " +
                "as a separate series and never mixed into your RMSSD trend or readiness.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        when (apple) {
            AppleHealthImport.State.Idle -> {
                status.appleImportSummary?.let {
                    Text(
                        "Last import (${syncLabel(status.appleImportAt)}): $it",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Button(onClick = onPick, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Outlined.UploadFile, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Import export.zip")
                }
            }
            is AppleHealthImport.State.Running -> {
                val fraction = apple.fraction
                if (fraction != null) {
                    LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "${apple.phase}… ${"%,d".format(apple.records)} records" +
                        (fraction?.let { " · ${(it * 100).toInt()}%" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (apple.phase != "Saving") {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Nothing is saved until the whole export has been read, so cancelling leaves your data untouched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            }
            is AppleHealthImport.State.Done -> {
                StatusLine(Icons.Outlined.CheckCircle, "Imported ${apple.summary.describe()}", MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("OK") }
            }
            is AppleHealthImport.State.Failed -> {
                StatusLine(Icons.Outlined.ErrorOutline, apple.message, MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPick, shapes = ButtonDefaults.shapes()) { Text("Pick another file") }
                    TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Dismiss") }
                }
            }
            AppleHealthImport.State.Cancelled -> {
                StatusLine(Icons.Outlined.Info, "Import cancelled.", MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("OK") }
            }
        }
    }
}

@Composable
private fun BrandCard(
    ui: BrandUi,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val motion = MaterialTheme.motionScheme
    ForgeCard(modifier = modifier, onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ui.brand.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    ui.brand.devices,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Hide steps" else "Show steps",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(ui.status.label, color = statusColor(ui.status), filled = true)
            ui.detail?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec())
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                Text(ui.brand.gives, style = MaterialTheme.typography.bodyMedium)
                ui.brand.missing?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    when (ui.brand.route) {
                        BrandRoute.NOT_POSSIBLE -> "What you can do"
                        BrandRoute.BRIDGE_APP -> "The only route"
                        BrandRoute.HEALTH_CONNECT -> "Turn on sharing"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                NumberedSteps(ui.brand.steps)
                ui.brand.caveat?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- pieces

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NumberedSteps(steps: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, step ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    "${i + 1}.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(20.dp)
                )
                Text(step, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun statusColor(status: BrandStatus): Color = when (status) {
    BrandStatus.CONNECTED -> MaterialTheme.colorScheme.primary
    BrandStatus.SEEN_BEFORE -> MaterialTheme.colorScheme.tertiary
    BrandStatus.VIA_HEALTH_CONNECT -> MaterialTheme.colorScheme.secondary
    BrandStatus.NEEDS_SETUP -> MaterialTheme.colorScheme.tertiary
    BrandStatus.BRIDGE_APP -> MaterialTheme.colorScheme.onSurfaceVariant
    BrandStatus.NOT_POSSIBLE -> MaterialTheme.colorScheme.error
}

internal fun syncLabel(ms: Long): String =
    if (ms <= 0L) "never" else "${Dates.dayLabel(Dates.dayOf(ms))}, ${Dates.timeLabel(ms)}"

private fun openInBrowser(context: Context, uri: Uri, onFailure: () -> Unit) {
    runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri) }
        .onFailure { onFailure() }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(label, text))
}
