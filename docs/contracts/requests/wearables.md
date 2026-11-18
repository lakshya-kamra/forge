# Requests from the Wearables area

Everything below touches files Wearables doesn't own. The Wearables code already compiles
without these changes; they turn on the features at runtime.

## 1. `AndroidManifest.xml`: Health Connect read permissions (required)

Add next to the existing `android.permission.health.*` entries. (`READ_OXYGEN_SATURATION` is
already there.) Without these, Health Connect silently drops the types from the permission
request, so HRV, VO2 max, respiratory rate, lean mass and skin temperature never sync, and the
history and background options never appear.

```xml
<uses-permission android:name="android.permission.health.READ_HEART_RATE_VARIABILITY" />
<uses-permission android:name="android.permission.health.READ_VO2_MAX" />
<uses-permission android:name="android.permission.health.READ_RESPIRATORY_RATE" />
<uses-permission android:name="android.permission.health.READ_LEAN_BODY_MASS" />
<uses-permission android:name="android.permission.health.READ_SKIN_TEMPERATURE" />
<uses-permission android:name="android.permission.health.READ_HEALTH_DATA_HISTORY" />
<uses-permission android:name="android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" />
```

At runtime the code only requests skin temperature, history and background when the installed
Health Connect reports the matching `HealthConnectFeatures` feature as available.

## 2. `AndroidManifest.xml`: WHOOP OAuth redirect activity (required for WHOOP)

Inside `<application>`. The redirect URI `forge://whoop/callback` is what the user registers on
their own WHOOP developer app; the activity hands the URI to `WhoopAuth` and returns to
MainActivity with `CLEAR_TOP | SINGLE_TOP`, which closes the Custom Tab.

```xml
<activity
    android:name=".data.wearables.whoop.WhoopCallbackActivity"
    android:exported="true"
    android:noHistory="true"
    android:excludeFromRecents="true"
    android:theme="@android:style/Theme.Translucent.NoTitleBar">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data
            android:scheme="forge"
            android:host="whoop"
            android:path="/callback" />
    </intent-filter>
</activity>
```

## 3. `AndroidManifest.xml`: Custom Tabs package visibility (recommended)

Add inside the existing `<queries>` element so androidx.browser can find a Custom Tabs browser
on Android 11+. Without it the sign-in still opens, but possibly in a plain browser window.

```xml
<intent>
    <action android:name="android.support.customtabs.action.CustomTabsService" />
</intent>
```

## 4. `ForgeApp.kt`: keep the 6-hourly background sync scheduled (recommended)

The Wearables code schedules `WearableSyncWorker` itself, after Health Connect access is granted,
after WHOOP connects, and after every foreground sync. This is a safety net for installs that
were set up before v2 (scheduling is idempotent; it uses `ExistingPeriodicWorkPolicy.KEEP`).

```kotlin
// in onCreate(), after `repository` is created
appScope.launch {
    val s = settingsStore.settings.first()
    if (s.healthConnectEnabled || s.whoopConnected) {
        com.laksh.forge.data.wearables.WearableSyncWorker.schedule(this@ForgeApp)
    }
}
```

(needs `import kotlinx.coroutines.flow.first`)

## 5. Replace `ForgeRepository.syncHealth()` callers with `WearableSync.syncAll()` (required)

`WearableSync.syncAll(): SyncReport` (package `com.laksh.forge.data.wearables`) syncs Health
Connect and WHOOP, merges every source per field (dedicated device > phone, then WHOOP API >
Health Connect > Apple import), imports scale weigh-ins (same one-per-day rule as v1, now with
same-day body fat), recomputes adaptive TDEE when weights were added, and sets
`lastHealthSync` / `lastWhoopSync`.

- `feature/trends/TrendsViewModel.syncHealth()`: call `WearableSync.syncAll()` and build the
  message from the report, e.g.
  `if (r.days == 0 && r.errors.isEmpty()) "No new wearable data." else "Synced ${r.days} days from ${r.sources.joinToString()}."`
  plus `r.errors.joinToString("\n")` when non-empty. The current text "Check that Zepp's Health
  Connect sync is on" is Zepp-specific; v2 supports many brands.
- `feature/settings/SettingsViewModel.syncHealth()`: same replacement.
- `data/ForgeRepository.syncHealth()`: make it a thin delegate so any remaining caller is safe:

  ```kotlin
  /** Kept for source compatibility; all wearable syncing goes through WearableSync. */
  suspend fun syncHealth(): Int =
      com.laksh.forge.data.wearables.WearableSync.syncAll().days
  ```

  Until this lands, the old body is still safe: `HealthConnectManager.readDailies()` now writes
  through the layered merge and returns merged rows, so its `upsertAll` can't wipe WHOOP or Apple
  fields.
- Pull-to-refresh on Today (design spec) should call `WearableSync.syncAll()` too.

## 6. Shell / Today / Trends / Settings wiring (owners: integrator, Design & shell)

Already wired by Design & shell when this was written: Today calls `ReadinessCard(onOpen =
onOpenWearables)` and `WearableSync.syncAll()` for pull-to-refresh; Trends hosts
`WearableTrendsSection()`; Settings links to `Routes.WEARABLES`. Remaining:

- `ForgeNavHost.kt`: pass `onOpenWearables = { nav.navigate(Routes.WEARABLES) }` to `TodayScreen`
  (it currently defaults to a no-op, so tapping the readiness card does nothing).
- `WearableTrendsSection` is a plain `Column` (not lazy) with its own 30d/90d/1y toggle, view model
  and empty/loading states; host it in a single lazy `item`.
- Settings' own Health Connect card can shrink to a status line plus the Wearables link: the
  Wearables screen covers the permission flow (including history/background access). Note that
  `HealthConnectManager.permissions` now includes the v2 types, while `hasAllPermissions()` still
  means "the v1 core set is granted".

## 7. Coach context (optional, owner: ForgeRepository)

`buildCoachContext()` lists steps/kcal/sleep/RHR from `health_daily`. v2 rows also carry `hrvMs`
(RMSSD), `recoveryScore` (WHOOP, as reported), `strain`, `vo2max`, `spo2Pct`. The header line
"from Health Connect" and the fallback "(Zepp sync may be off)" could become source-neutral.

## 8. Backup (owner: AI providers, `backup_rules.xml` / `data_extraction_rules.xml`)

- SecretStore keys used by Wearables: `whoop.client_id`, `whoop.client_secret`, `whoop.tokens`
  (all must stay excluded from backup with the rest of SecretStore).
- Wearables keeps per-source layer files in `files/wearables/*.json` (per-field provenance that
  `health_daily` has no column for). They should be backed up together with `forge.db`; if they
  are excluded, the app still works, it just re-learns provenance on the next sync.
- DataStore `forge_wearables` holds sync reports, sources seen and a short-lived OAuth `state`;
  harmless either way.
