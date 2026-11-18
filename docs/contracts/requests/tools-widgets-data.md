# Requests from: Tools, widgets, data (backup/import), reminders

Everything below compiles without these changes landing (reminders also re-sync from the Data
settings screen, widgets also refresh on their 30-minute period), but the features are only
complete once they are applied.

## 1. `AndroidManifest.xml`: home-screen widget receivers (required for widgets)

Inside `<application>`. The provider XMLs are this area's files in `res/xml/`. `exported="true"`
is what the launcher needs to bind the widgets; the receivers only accept the system's
APPWIDGET_UPDATE broadcast.

```xml
<receiver
    android:name=".feature.widgets.TodayWidgetReceiver"
    android:exported="true"
    android:label="@string/widget_today_label">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data
        android:name="android.appwidget.provider"
        android:resource="@xml/forge_today_widget_info" />
</receiver>

<receiver
    android:name=".feature.widgets.NextWorkoutWidgetReceiver"
    android:exported="true"
    android:label="@string/widget_next_workout_label">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data
        android:name="android.appwidget.provider"
        android:resource="@xml/forge_next_workout_widget_info" />
</receiver>
```

No new permission is needed: `POST_NOTIFICATIONS` is already declared for reminders.

## 2. `res/values/strings.xml`: widget labels and descriptions (required with item 1)

```xml
<string name="widget_today_label">Forge · Today</string>
<string name="widget_today_description">Calories left, protein, and a one-tap Log food button.</string>
<string name="widget_next_workout_label">Forge · Next workout</string>
<string name="widget_next_workout_description">The next day of your active program, or the session in progress.</string>
```

Once those exist, please also add the description attribute to this area's provider XMLs
(left out so they compile before the strings land):

- `res/xml/forge_today_widget_info.xml`: `android:description="@string/widget_today_description"`
- `res/xml/forge_next_workout_widget_info.xml`: `android:description="@string/widget_next_workout_description"`

## 3. `ForgeApp.kt`: start the reminder scheduler and widget refresher (required)

In `onCreate()`, after `repository` is created (both use `ForgeApp.instance.repository`) and
after the settings-cache collector. Both are cheap: one settings/prefs collector, and a
debounced Room observer that does nothing when no widget is on the home screen.

```kotlin
import com.laksh.forge.data.reminders.ReminderScheduler
import com.laksh.forge.feature.widgets.ForgeWidgets

// in onCreate(), after `repository = ForgeRepository(this, settingsStore)`:
        // Keeps the WorkManager reminder queue in step with the reminder toggles, and
        // re-anchors each reminder on every launch.
        ReminderScheduler.start(this, appScope)
        // Refreshes home-screen widgets whenever food, weights, sessions, programs or
        // settings change.
        ForgeWidgets.start(this, appScope)
```

## 4. `ForgeNavHost.kt`: deep links for notifications and widgets (required)

Notifications and widgets open MainActivity with an explicit intent carrying a `forge://` URI
(`data/reminders/DeepLinks.kt`) and `NEW_TASK | CLEAR_TASK`, so NavController handles it when
the graph is first set; no manifest intent-filter is needed and other apps can't trigger them.
Add `deepLinks` to these existing `composable(...)` calls (keep everything else as is):

```kotlin
import androidx.navigation.navDeepLink
import com.laksh.forge.data.reminders.DeepLinks

composable(Routes.TODAY, deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.TODAY })) { ... }
composable(Routes.TRAIN, deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.TRAIN })) { ... }
composable(Routes.LOG, deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.LOG })) { ... }
composable(
    Routes.SESSION,
    arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
    deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.SESSION_PATTERN })
) { ... }
composable(Routes.TOOLS, deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.TOOLS })) { ... }
composable(Routes.SETTINGS_DATA, deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.SETTINGS_DATA })) { ... }
```

`DeepLinks.SESSION_PATTERN` is `forge://session/{sessionId}`, matching the existing
`sessionId` LongType argument.

**MainActivity (note, no change needed today):** the intents use `CLEAR_TASK`, so the activity
is recreated and the NavHost reads the deep link from the launch intent. If the shell ever
switches MainActivity to `singleTop`/`singleTask` and stops recreating, forward
`onNewIntent(intent)` to `navController.handleDeepLink(intent)`.

## 5. Notes (no change needed)

- **Room:** `BackupDao` only gained queries and `@Insert`s on existing tables (no schema change,
  no migration). `TableCounts` is a POJO in `BackupDao.kt`.
- **Backups (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`):** this area's DataStore
  files (`forge_gym_profile`, `forge_reminders`) live under `files/datastore/`, which the
  current rules already include. Meal photos (`files/meal_photos/`) stay excluded.
- **Notification icon:** reminders use `R.drawable.ic_launcher_foreground` (a transparent "F"
  glyph that renders correctly as a status-bar silhouette). If the design area renames that
  drawable or adds a dedicated monochrome `ic_stat_forge`, update
  `data/reminders/ReminderNotifications.kt` (one line).
- **Settings hub:** `SettingsScreen` already links `Routes.TOOLS` and `Routes.SETTINGS_DATA`;
  nothing else is needed there. Reminder toggles and the POST_NOTIFICATIONS permission flow
  live on `DataSettingsScreen`.
- **WorkManager / Glance:** default WorkManager initialisation (androidx.startup) is assumed;
  don't remove `androidx.work.WorkManagerInitializer` from the merged manifest.
