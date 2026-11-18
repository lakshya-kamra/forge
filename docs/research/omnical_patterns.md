# OmniCal Android: Material 3 Expressive patterns to reuse in Forge

Source studied (read only): `C:\Users\laksh\omnical\android` at commit `1954991` (app 1.3.0, versionCode 4).
Everything below was read directly from that repo on 2026-11-17. File paths are relative to
`omnical/android/app/src/main/java/com/omnical/app/` unless stated otherwise. Where something is my
interpretation rather than code, it is marked *(interpretation)*.

Companion doc: `m3e_api.md` (exact API surface of material3 1.5.0-alpha29, which this project compiles against).

---

## 1. Toolchain and dependency set (`gradle/libs.versions.toml`, `app/build.gradle.kts`)

| Item | Value |
| --- | --- |
| AGP | 9.4.0 (built-in Kotlin; `kotlin-android` plugin declared in root but **not applied** in `:app`) |
| Gradle wrapper | 9.7.1 |
| Kotlin / Compose compiler plugin | 2.2.10 (`org.jetbrains.kotlin.plugin.compose`) |
| KSP | 2.2.10-2.0.2 (needs `android.disallowKotlinSourceSets=false` in `gradle.properties` under AGP 9) |
| compileSdk / minor / target / min | 37 / `compileSdkMinor = 2` / 37 / 26 |
| JVM target | 17 |
| Compose BOM | `androidx.compose:compose-bom-alpha:2026.09.01` -> material3 **1.5.0-alpha29** |
| Material icons | `material-icons-extended` (version from BOM) |
| navigation-compose | 2.10.1 (string routes, no type-safe routes) |
| activity-compose / lifecycle / core-ktx | 1.13.0 / 2.11.0 / 1.19.0 |
| core-splashscreen | 1.2.0 |
| Glance | `glance-appwidget` + `glance-material3` 1.2.0 |
| profileinstaller | 1.4.1 |
| Room / WorkManager / DataStore | 2.8.5 / 2.11.2 / 1.2.1 |
| Tests | JUnit 4.13.2, Truth 1.4.4, Robolectric 4.17, Roborazzi 1.76.0 (+ `roborazzi-compose`, `roborazzi-junit-rule`, Gradle plugin `io.github.takahirom.roborazzi`) |

Global opt-ins are passed as compiler flags, not per-file `@OptIn`:

```kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}
```

Note from the API inventory: in 1.5.0-alpha29 most Expressive APIs (MaterialExpressiveTheme,
MotionScheme, ShortNavigationBar, ToggleButton, ButtonGroup, FloatingToolbar, wavy progress, FAB menu,
flexible app bars) carry **no** opt-in marker any more; only `LoadingIndicator`, `ContainedLoadingIndicator`,
`LoadingIndicatorDefaults`, `MaterialShapes`, `RoundedPolygon.toShape/toPath`, `Morph.toPath` and
`PullToRefreshDefaults.LoadingIndicator` still require `ExperimentalMaterial3ExpressiveApi`. The global flag is
harmless but most of it is now redundant.

Release build: `isMinifyEnabled = true`, `isShrinkResources = true`, `proguard-android-optimize.txt`, signed
with the debug key (`signingConfig = signingConfigs.getByName("debug")`) so `assembleRelease` gives an
installable side-load APK. README says the release build is "much smoother than debug".

`testOptions`: `unitTests.isReturnDefaultValues = true`, `unitTests.isIncludeAndroidResources = true`
(needed for Robolectric to render Compose with real resources/fonts).

---

## 2. Theme setup (`ui/theme/Theme.kt`)

Yes, it uses **`MaterialExpressiveTheme`** with **`MotionScheme.expressive()`**:

```kotlin
val OmniShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun OmniCalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val typography = remember { omniTypography() }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = OmniShapes,
        typography = typography,
        content = content,
    )
}
```

Points worth copying:
- Shapes are rounder than M3 defaults (8/12/16/24/32 dp).
- Typography is built once (`remember`), because it allocates 25 `Font` objects (5 families x 5 weights).
- Theme mode and dynamic colour are user settings (DataStore) passed in from `MainActivity` (see section 6).

---

## 3. Typography with the variable font (`ui/theme/Type.kt`)

Font: `app/src/main/res/font/google_sans_flex.ttf` (393,648 bytes). The code comment says it is a Latin subset
with three live axes **wght 300-900, ROND 0-100, opsz 12-72** (I did not re-verify the subset/axis ranges
inside the TTF). Licence file shipped at `app/src/main/assets/licenses/GoogleSansFlex-OFL.txt` (SIL OFL).

Mechanism: one TTF resource, many `Font` instances with different `FontVariation.Settings`, grouped into
per-role `FontFamily`s:

```kotlin
private val Weights = listOf(400, 500, 600, 700, 800)

@OptIn(ExperimentalTextApi::class)
private fun flexFont(weight: Int, opticalSize: Float, roundness: Float): Font = Font(
    resId = R.font.google_sans_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("opsz", opticalSize),
        FontVariation.Setting("ROND", roundness),
    ),
)

private fun flexFamily(opticalSize: Float, roundness: Float): FontFamily =
    FontFamily(Weights.map { flexFont(it, opticalSize, roundness) })
```

| Family | opsz | ROND | Used for |
| --- | --- | --- | --- |
| `DisplayFamily` | 56 | 100 | display* |
| `HeadlineFamily` | 32 | 100 | headline* |
| `TitleFamily` | 20 | 40 | title* |
| `BodyFamily` | 15 | 0 | body* |
| `LabelFamily` | 12 | 20 | label* |

`omniTypography()` starts from `Typography()` and calls `base.copy(...)` for all 15 baseline styles **and all 15
Expressive `*Emphasized` styles** (the `Typography` class in 1.5.0-alpha29 has `displayLargeEmphasized` ...
`labelSmallEmphasized`). Pattern: baseline = Normal/Medium weight; Emphasized = SemiBold (display/headline/title),
Medium (body) or Bold (label), with slightly tighter letter spacing on display/headline, e.g.
`displayLargeEmphasized = base.displayLarge.with(DisplayFamily, FontWeight.SemiBold, (-1.0).sp)`.

How the styles are used in screens: screen titles `displaySmallEmphasized`, kickers `labelLargeEmphasized` in
`primary`, section headers `titleMediumEmphasized`, key row text `bodyLargeEmphasized`, count chips
`labelMediumEmphasized`.

Note: 1.5.0-alpha29 also has a `Typography(fontFamily: FontFamily, ...)` constructor (see `m3e_api.md`), which
OmniCal does not use; OmniCal needs per-role families so the `copy` approach is still the right one for
optical-size/roundness variation.

---

## 4. Colour scheme (`ui/theme/Color.kt`)

- Android 12+ and `dynamicColor == true`: `dynamicLightColorScheme` / `dynamicDarkColorScheme` (Material You).
- Otherwise: hand-written full `lightColorScheme(...)` / `darkColorScheme(...)` (every role including all
  `surfaceContainer*`, `surfaceBright/Dim`), a tonal palette from a cyan seed (primary `0xFF00677F` light,
  `0xFF5DD5FC` dark).
- Brand/source accents live outside the scheme in `object OmniColors` and are adapted per scheme with
  extension helpers. These helpers are the reusable part:

```kotlin
/** Page background: soft grey in light mode (so white cards pop), near-black in dark mode. */
val ColorScheme.appBackground: Color get() = if (isDark) surface else surfaceContainer
/** Container for grouped list rows and cards that sit on appBackground. */
val ColorScheme.cardContainer: Color get() = if (isDark) surfaceContainerHigh else surfaceContainerLowest
/** True when the scheme is a dark one (works for dynamic schemes too). */
val ColorScheme.isDark: Boolean get() = surface.luminance() < 0.5f
/** Brand colours are tuned for light backgrounds; lift toward white in dark mode. */
fun ColorScheme.accentFor(source: Color): Color = if (isDark) lerp(source, Color.White, 0.35f) else source
/** A soft container tinted with the source colour. */
fun ColorScheme.tintedContainer(source: Color, amount: Float = 0.14f): Color =
    lerp(surfaceContainerHigh, accentFor(source), if (isDark) amount + 0.06f else amount)
```

The "Android 16 style grouped list" look = `appBackground` page + `cardContainer` rows via
`ListItemDefaults.segmentedColors(containerColor = cardContainer)`; inside bottom sheets rows use
`surfaceContainerHighest` (dark) / `surfaceContainerLowest` (light).

Glance widget reuses the same fallback schemes via `androidx.glance.material3.ColorProviders(LightColorScheme, DarkColorScheme)`.

---

## 5. Expressive components actually used, with parameters

| Component | Where | Parameters / notes |
| --- | --- | --- |
| `MaterialExpressiveTheme` | `ui/theme/Theme.kt` | `colorScheme`, `motionScheme = MotionScheme.expressive()`, `shapes`, `typography` |
| `ShortNavigationBar` | `ui/navigation/OmniCalRoot.kt` `AppNavigationBar` | `containerColor = if (isDark) surfaceContainer else surfaceContainerLowest`; `modifier = Modifier.drawWithContent { drawContent(); drawLine(outlineVariant.copy(alpha = 0.6f), top edge, 1.dp) }` (hairline so scrolled cards meet an edge). Default arrangement/insets. |
| `ShortNavigationBarItem` | same | `selected`, `onClick`, `icon = { BadgedBox(badge = { Badge(containerColor = error or tertiary) { Text(n or "99+") } }) { Icon(filled if selected else outlined) } }`, `label = { Text(tab.label) }` |
| `FloatingActionButton` (plain) | `QuickAddFab` | `containerColor = primaryContainer`, `contentColor = onPrimaryContainer`, `Icons.Rounded.Add` |
| `LargeFlexibleTopAppBar` | `ui/settings/SettingsScreen.kt` | `title`, `subtitle` (nullable lambda), `navigationIcon` (`IconButton` + `AutoMirrored.Rounded.ArrowBack`), `windowInsets = WindowInsets(0,0,0,0)` (root Scaffold already applied insets), `colors = TopAppBarDefaults.topAppBarColors(containerColor = appBackground, scrolledContainerColor = appBackground)`, `scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()` + `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)` on the parent Column |
| `LoadingIndicator` (morphing shapes) | `SyncPill`, Settings "Sync now" trailing, Quick-add submit button, Task sheet loading/busy | `Modifier.size(24.dp)` in pills/buttons (with `color = primary` or `onPrimary` inside a filled Button), `Modifier.size(32.dp)` as list trailing, default size centred in a 160dp box for sheet loading |
| `PullToRefreshBox` + `PullToRefreshDefaults.LoadingIndicator` | `OmniPullToRefresh` in `ui/components/CommonComponents.kt` | `state = rememberPullToRefreshState()`, `indicator = { PullToRefreshDefaults.LoadingIndicator(state, isRefreshing, Modifier.align(Alignment.TopCenter)) }` |
| `LinearWavyProgressIndicator` (determinate) | `ui/components/EventItems.kt` (ongoing event card) | `progress = { elapsed }`, `color = accent`, `trackColor = accent.copy(alpha = 0.22f)`, `Modifier.fillMaxWidth().padding(top = 16.dp)` |
| `ToggleButton` as a connected group | `ConnectedToggleGroup<T>` in `CommonComponents.kt` (replacement for SegmentedButton) | Row with `Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)`; per item `shapes = connectedLeadingButtonShapes() / connectedMiddleButtonShapes() / connectedTrailingButtonShapes()` by index, `contentPadding = PaddingValues(horizontal = 8.dp)`, `colors = ToggleButtonDefaults.colors(containerColor = cardContainer)`, `Modifier.weight(1f).semantics { role = Role.RadioButton }`, haptic `SegmentTick` on change, label `labelLarge`. Note: it does **not** use the `ButtonGroup` composable (that one is an overflow-menu DSL, see m3e_api.md). |
| `MaterialShapes.Cookie9Sided.toShape()` | `EmptyState` (88dp, `secondaryContainer`, 36dp icon), `ShapeBadge` (52dp default, icon 46%) | used with `Modifier.clip(shape).background(...)` |
| `Morph(MaterialShapes.Circle, MaterialShapes.Cookie4Sided)` + material3 `Morph.toPath(progress, path)` | `ExpressiveCheck` in `ui/components/TaskItems.kt` | see section 7 |
| `SegmentedListItem` + `ListItemDefaults.segmentedShapes(index, count)` + `ListItemDefaults.segmentedColors(...)` + `ListItemDefaults.SegmentedGap` | all grouped lists (tasks, events, settings, sheets) | helpers `segmentedShapesFor(index, count)`, `Modifier.segmentGap(index, count)` (bottom padding `SegmentedGap` except last), `cardListColors()`, `sheetListColors()`. Overloads used: click (`onClick`), toggle (`checked`/`onCheckedChange` with a `Switch(onCheckedChange = null)` trailing), and non-interactive. `contentPadding = TaskRowPadding` (start 4, end 16, vertical 10 dp) so a 48dp check target sits against the card edge. |
| `Button`/`TextButton`/`FilledTonalIconButton` with morphing shapes | Quick-add, Task detail | `shapes = ButtonDefaults.shapes()` / `IconButtonDefaults.shapes()`; full-width primary buttons use `Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight)` and `titleMediumEmphasized` label |
| `SwipeToDismissBox` | `TaskListItem` | start-to-end = complete, end-to-start = snooze; stable `onDismiss` lambda (via `rememberUpdatedState`) because it re-runs when lambda identity changes; haptic `GestureThresholdActivate` when target changes; `reset()` after 650 ms; background colour animated with `motionScheme.fastEffectsSpec()` |
| `FilterChip` | Tasks filters | `shape = CircleShape`, leading check icon only when selected (`FilterChipDefaults.IconSize`) |
| `ModalBottomSheet` | quick-add, task detail, event detail, source editors | `sheetState = rememberModalBottomSheetState(...)` |

Not used anywhere in OmniCal: `ButtonGroup` (DSL), `SplitButtonLayout`, `HorizontalFloatingToolbar` /
`VerticalFloatingToolbar`, `FlexibleBottomAppBar`, `WideNavigationRail`, `FloatingActionButtonMenu` /
`ToggleFloatingActionButton`, `ExtendedFloatingActionButton` variants, `ContainedLoadingIndicator`,
`CircularWavyProgressIndicator`. Their signatures are in `m3e_api.md`.

---

## 6. Navigation shell (`ui/navigation/*`, `MainActivity.kt`)

**Activity** (`MainActivity`, a plain `ComponentActivity`):
1. `installSplashScreen()` then `enableEdgeToEdge()` before `super.onCreate`; splash held with
   `setKeepOnScreenCondition { !settingsLoaded }` until DataStore settings arrive, so the first frame is already in the right theme.
2. Settings flow collected with `.catch { emit(AppSettings()) }` (a corrupt DataStore must not crash the app); content renders only when settings are non-null.
3. `ThemeMode` (SYSTEM/LIGHT/DARK) resolved to `darkTheme`; `LaunchedEffect(darkTheme)` re-calls
   `enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { darkTheme }, navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkTheme })`
   so system bar icons follow the in-app theme, not only the system one.
4. Window theme: `Theme.OmniCal` parents `android:Theme.Material(.Light).NoActionBar`; splash theme parents `Theme.SplashScreen` with `windowSplashScreenAnimatedIcon` and `postSplashScreenTheme`.

**Root** (`OmniCalRoot`):
- `Scaffold(containerColor = appBackground, bottomBar = ..., floatingActionButton = ..., snackbarHost = ...)`.
- Tabs are an enum `TopLevelDestination(route, label, selectedIcon, unselectedIcon)` (filled vs outlined icons); a non-tab route (`settings`) hides chrome: `showTabChrome = currentTab != null || currentRoute == null`.
- Bottom bar and FAB are wrapped in `AnimatedVisibility` using the theme motion scheme:
  ```kotlin
  val motion = MaterialTheme.motionScheme
  enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
  exit  = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec()),
  // FAB: scaleIn(motion.defaultSpatialSpec()) + fadeIn(...)  /  scaleOut(motion.fastSpatialSpec()) + fadeOut(...)
  ```
- Tab navigation: `navigate(route) { launchSingleTop = true; restoreState = true; popUpTo(START) { saveState = true } }` (state saved per tab, Back always returns to Today).
- Haptics: `SegmentTick` on tab change, `Confirm` on FAB.
- One shared `SnackbarHostState` and an app `CoroutineScope` exposed through `CompositionLocalProvider(LocalAppSnackbar ..., LocalAppScope ...)`; sync failures are reported once per result list via `repeatOnLifecycle(STARTED)`.
- Root-level sheets (quick-add, task detail, event detail) are hosted outside the `NavHost`, driven by `rememberSaveable` ids.
- Each screen gets `Modifier.padding(innerPadding)`; lists add a 120dp bottom spacer so the FAB never covers the last row.

**NavHost motion** (`ui/navigation/NavTransitions.kt`, `OmniNavHost`): deliberately **tweens, not springs**, "so system predictive back can scrub the transition smoothly" (code comment):
- Between tabs, Material "fade through": outgoing `fadeOut(tween(90, FastOutLinearInEasing))`; incoming `fadeIn + scaleIn(initialScale = 0.94f)` with `tween(210, delayMillis = 90, LinearOutSlowInEasing)`. The comment says the earlier overlapping cross-fade "looked like a glitch".
- Into/out of Settings, "shared axis X": `slideIn/OutHorizontally(tween(300, CubicBezierEasing(0.2f, 0f, 0f, 1f)))` by width/10, plus the same fades.
- Predictive back: manifest `<application android:enableOnBackInvokedCallback="true">`; in-screen sub-navigation (Settings source editor) uses `BackHandler(enabled = editingType != null) { closeEditor() }`. Activity is `launchMode="singleTask"`, `windowSoftInputMode="adjustResize"`, `allowBackup="false"`.

---

## 7. Motion patterns

All component-level motion reads from `MaterialTheme.motionScheme` (expressive scheme), never hard-coded springs:

| Use | Spec |
| --- | --- |
| Crossfading text (`AnimatedContent` in `ScreenHeader` subtitle, `SyncPill` label) | `fadeIn(defaultEffectsSpec()) togetherWith fadeOut(fastEffectsSpec())` |
| Calendar grid height | `animateDpAsState(..., motionScheme.defaultSpatialSpec())` |
| Month-grid day cell bg/text colour | `animateColorAsState(..., fastEffectsSpec())` |
| Month-grid selected-day scale (0.92 -> 1) | `animateFloatAsState(..., fastSpatialSpec())` |
| Swipe background colour | `animateColorAsState(..., fastEffectsSpec())` |
| Checkbox morph | `animateFloatAsState(if (checked) 1f else 0f, fastSpatialSpec())` |
| Month title change | `AnimatedContent` with `slideInVertically { +-it/2 } + fadeIn()` (default specs) |
| Lists | `Modifier.animateItem()` on LazyColumn items |

**`ExpressiveCheck`** (the signature micro-interaction, `ui/components/TaskItems.kt`), worth copying for set-completion checkmarks in Forge:

```kotlin
private val CheckMorph: Morph by lazy { Morph(MaterialShapes.Circle, MaterialShapes.Cookie4Sided) }

@Composable
fun ExpressiveCheck(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
                    accent: Color = MaterialTheme.colorScheme.primary, enabled: Boolean = true) {
    val haptics = LocalHapticFeedback.current
    val progress = animateFloatAsState(if (checked) 1f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "checkMorph")
    val path = remember { Path() }; val matrix = remember { Matrix() }
    Box(modifier.size(48.dp).clip(CircleShape).toggleable(value = checked, enabled = enabled, role = Role.Checkbox,
        onValueChange = { next ->
            haptics.performHapticFeedback(if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onCheckedChange(next)
        }), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(24.dp).graphicsLayer { rotationZ = progress.value * 45f }) {
            val f = progress.value.coerceIn(0f, 1f)
            CheckMorph.toPath(f, path)                 // androidx.compose.material3.toPath (unit-size path)
            matrix.reset(); matrix.scale(size.width, size.height); path.transform(matrix)
            if (f > 0f) drawPath(path, color = accent.copy(alpha = f))
            if (f < 1f) drawPath(path, color = outline.copy(alpha = 1f - f), style = Stroke(2.dp.toPx()))
        }
        Icon(Icons.Rounded.Check, null, tint = onAccent,
             modifier = Modifier.size(16.dp).graphicsLayer { val s = progress.value.coerceIn(0f, 1.2f); scaleX = s; scaleY = s })
    }
}
```

Key details: the `Morph` is a lazy top-level singleton (immutable, shared by all rows); the animated value is
read only inside `Canvas`/`graphicsLayer` lambdas, so it never recomposes per frame (code comment says so);
48dp touch target / 24dp visual; the check icon is allowed to overshoot to 1.2x when the spring overshoots.
`Morph` itself comes from `androidx.graphics.shapes` (graphics-shapes 1.0.1, transitive via material3).

Haptic vocabulary used: `SegmentTick` (tab/segment change), `Confirm` (FAB, mark complete), `ToggleOn`/`ToggleOff`
(checkbox), `GestureThresholdActivate` (swipe passes threshold).

---

## 8. Baseline profile approach (`app/src/main/baseline-prof.txt`)

Hand-written, no Macrobenchmark module and no `androidx.baselineprofile` Gradle plugin. The entire file:

```
# Hand-written baseline profile for OmniCal. AGP merges it with the Compose/AndroidX library
# profiles; profileinstaller writes it on first launch so the app code is AOT-compiled
# even when the APK is side-loaded (no Play Store cloud profiles).
HSPLcom/omnical/app/**;->**(**)**
Lcom/omnical/app/**;
```

- Rule 1: every method in every class under `com/omnical/app/` flagged Hot, Startup, Post-startup.
- Rule 2: every class under that package in the startup class set.
- `implementation(libs.androidx.profileinstaller)` (1.4.1) installs the merged profile on first launch for side-loaded APKs.
- Library profiles shipped inside Compose/AndroidX AARs are merged automatically by AGP.
- The wildcard rule syntax (`*`, `**`, `?`) and the `src/main/baseline-prof.txt` location are documented by Android:
  https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure (fetched 2026-11-17; the page notes wildcards are not supported by the Compiler Explorer HRF tool, only by the build).
- *(interpretation)* This is a deliberately blunt profile: AOT-compiling all app code costs some APK/disk and install time but is simple and suits a one-user side-loaded app. Forge can copy it verbatim with its own package path.
- Pair it with the release build (R8). The README tells the user to install release for daily use.

---

## 9. Glance widget (`widget/*`, `res/xml/today_widget_info.xml`, manifest)

- `TodayWidget : GlanceAppWidget()` with `sizeMode = SizeMode.Responsive(setOf(SMALL = DpSize(250.dp, 110.dp), LARGE = DpSize(250.dp, 200.dp)))`; `LocalSize.current.height >= LARGE.height` decides 4 vs 2 rows.
- `provideGlance` loads all data first (suspend, `Dispatchers.IO`, from the app container's repositories; any non-cancellation exception falls back to an empty state), then `provideContent { GlanceTheme(colors = if (SDK >= S) GlanceTheme.colors else ColorProviders(LightColorScheme, DarkColorScheme)) { WidgetContent(state) } }`. Composition stays pure.
- Layout: `Column(GlanceModifier.fillMaxSize().background(colors.widgetBackground).cornerRadius(24.dp).padding(14.dp).clickable(actionStartActivity<MainActivity>()))`; colour dots via `GlanceModifier.size(8.dp).cornerRadius(4.dp).background(ColorProvider(color))`; text with `androidx.glance.text.TextStyle(color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)`.
- `TodayWidgetReceiver : GlanceAppWidgetReceiver()` with `override val glanceAppWidget = TodayWidget()`; manifest `<receiver android:exported="true">` with `APPWIDGET_UPDATE` filter and `android.appwidget.provider` meta-data.
- Provider XML: `minWidth 250dp`, `minHeight 110dp`, `targetCellWidth 4`, `targetCellHeight 2`, `resizeMode horizontal|vertical`, `widgetCategory home_screen`, `updatePeriodMillis 1800000` (30 min), `initialLayout @layout/glance_default_loading_layout` (Glance-provided layout), `description` string.
- Refresh: `TodayWidgetUpdater.updateAll(context)` calls `TodayWidget().updateAll(context.applicationContext)`, swallowing non-cancellation exceptions ("a widget problem must not fail a sync"); called by the sync manager after every sync.
- No widget preview layout/image, no Glance actions beyond opening the activity, no per-widget state.

---

## 10. Roborazzi screenshot tests (`app/src/test/java/com/omnical/app/screenshots/`)

Three JVM (Robolectric) test classes, no device needed:

```kotlin
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = Application::class)
class ScreenshotTests {
    @get:Rule val compose = createComposeRule()

    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OmniCalTheme(darkTheme = dark, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.appBackground, modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
    @Test fun today_light() = capture("today_light") { Chrome(TopLevelDestination.TODAY) { TodayContent(todayState(), ...) } }
    ...
}
```

- `ScreenshotTests` (309 lines): every screen light + dark with hand-built sample state; `dynamicColor = false` so output is deterministic; `application = Application::class` avoids running the real `OmniCalApp` (its DI container) under Robolectric.
- Pattern that makes this possible: every screen is split into a ViewModel-bound wrapper and a **stateless `XxxContent(state, callbacks..., modifier)`** composable (TodayContent, CalendarContent, TasksContent, InboxContent, SettingsListContent, QuickAddContent, TaskDetailContent, EventDetailContent). Tests render the `Content` inside a fake `Chrome` (Scaffold with `AppNavigationBar` + `QuickAddFab`) or a fake `SheetFrame` (scrim + 28dp top-rounded surface + drag handle) because real `ModalBottomSheet` windows are not captured.
- Infinite animations (LoadingIndicator, wavy progress) are tamed with `mainClock.autoAdvance = false` + a fixed `advanceTimeBy(1_500)`.
- `NavTransitionFilmstrip` (123 lines): `createAndroidComposeRule<ComponentActivity>()`, navigates Calendar then presses Back via `activity.onBackPressedDispatcher.onBackPressed()`, and captures frames at 0, 16, 33, 50, 66, 83, 100, 133, 166, 200, 250, 300, 400, 600 ms to compare old vs new NavHost motion side by side. A good technique for tuning Forge's transitions without a phone.
- `IconPreviewTests` (129 lines): renders launcher-icon candidates (`ic_cand_<name>_bg/_fg/_mono` drawables) masked as circle/squircle/rounded square at large and real sizes on light/dark wallpapers plus the themed monochrome variant.
- Commands (from README / KDoc): `./gradlew :app:recordRoborazziDebug --tests "*ScreenshotTests*"` (also `*NavTransitionFilmstrip*`, `*IconPreviewTests*`); output `app/build/outputs/roborazzi/`. Only `record` is used; there is no committed golden set or `verifyRoborazzi` step.

---

## 11. Checklist for Forge (direct reuse)

1. Same dependency set and compiler opt-ins (or drop the Expressive opt-in except where `LoadingIndicator`/`MaterialShapes` are used).
2. `ForgeTheme` = `MaterialExpressiveTheme(colorScheme = dynamic on S+ else brand, motionScheme = MotionScheme.expressive(), shapes = rounder Shapes, typography = remember { forgeTypography() })`.
3. Google Sans Flex via `FontVariation.Settings(weight, opsz, ROND)` per role; fill the 15 `*Emphasized` styles; ship the OFL text.
4. `appBackground` / `cardContainer` / `accentFor` / `tintedContainer` helpers + `SegmentedListItem` grouped lists.
5. `ShortNavigationBar` + badges + chrome hidden on detail routes with motion-scheme `AnimatedVisibility`; fade-through tweens in `NavHost` for predictive back.
6. `ExpressiveCheck` morph for set completion; `LoadingIndicator` for busy states; `LinearWavyProgressIndicator` for in-progress (e.g. rest timer / workout progress).
7. `src/main/baseline-prof.txt` with `HSPL<forge package>/**;->**(**)**` and `L<forge package>/**;` + profileinstaller; daily-use release build signed with debug key.
8. Glance widget: load-then-provideContent, `SizeMode.Responsive`, `GlanceTheme` with fallback `ColorProviders`, `updateAll` after data changes.
9. Stateless `XxxContent` composables + Roborazzi/Robolectric capture helper with `dynamicColor = false`, frozen clock, and a filmstrip test for transitions.
