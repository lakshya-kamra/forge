# Forge v2 — implementation contracts

Forge is a single-user Android fitness app (Kotlin, Jetpack Compose, Material 3 Expressive).
Package `com.laksh.forge`, source root `app/src/main/java/com/laksh/forge/`.
Several agents implement features **in parallel in the same working tree**. These rules keep
that safe.

## Hard rules

1. **Only edit files you own** (table below). Create new files only inside your own packages.
   Never edit another feature's files, `MainActivity.kt`, `ForgeNavHost.kt`, `ForgeApp.kt`,
   `AndroidManifest.xml`, Gradle files, `Entities.kt`, `ForgeDatabase.kt`, `Daos.kt`,
   `SettingsStore.kt`, or anything under `docs/research/`.
2. **Do not run Gradle** (the machine has little free RAM and builds are serialized by the
   integrator). Write code carefully against the APIs documented here and in the existing
   source; the integrator compiles and fixes integration errors.
3. Need a change in a file you don't own (a manifest entry, a dependency, a new route, a DAO
   column, a settings key)? Write it to `docs/contracts/requests/<your-area>.md` with the exact
   snippet. The integrator applies it. Design so your code still compiles before it lands
   (e.g. don't reference a dependency that isn't in `gradle/libs.versions.toml`).
4. Keep every **contract signature** in the stub you replace (listed below). Callers depend on it.
5. Never invent facts. Program prescriptions, guardrail numbers, tutorial videos and motion
   specs come from the verified files in `docs/research/`. If something isn't there, leave it out.

## Available dependencies (see gradle/libs.versions.toml)

Compose BOM alpha 2026.09.01 → material3 1.5 alpha (Expressive APIs, opt-ins already enabled
globally: ExperimentalMaterial3Api, ExperimentalMaterial3ExpressiveApi, ExperimentalFoundationApi,
ExperimentalLayoutApi, ExperimentalSharedTransitionApi, coroutines/serialization experimental),
material-icons-extended, navigation-compose 2.10, lifecycle 2.11 (viewmodel-compose,
runtime-compose), Room 2.8 (KSP), DataStore 1.2, WorkManager 2.11, Glance 1.2 (appwidget,
material3), CameraX 1.6, ML Kit barcode, Health Connect connect-client 1.1.0, OkHttp 5,
kotlinx-serialization-json 1.9, Coil 2.7 (`coil.compose.AsyncImage`), PDFBox-Android,
androidx.browser 1.9 (Custom Tabs), androidx.core 1.19, splashscreen 1.2, profileinstaller.
JUnit4, Truth, Robolectric 4.17, Roborazzi for tests.
Exact Material 3 Expressive signatures: `docs/research/m3e_api.md` (when present).

## Shared APIs you can use

- Repository: `ForgeApp.instance.repository` (`ForgeRepository`). View models get it through
  `forgeFactory { repo -> MyViewModel(repo) }` in `ui/Vm.kt`.
  - DAOs: `foodDao, weightDao, programDao, workoutDao, chatDao, healthDao` (v1) and, via
    `ForgeDatabase.get(ctx)`: `bodyDao(), planDao(), trainingDao(), wearableDao(),
    insightsDao(), exerciseDao(), backupDao()` — each feature owns its DAO file and may add
    queries to it (only tables/columns that exist in `Entities.kt`).
  - `repo.settingsStore` (`SettingsStore`): `settings: Flow<ForgeSettings>` + setters for every
    field (see the file). `repo.settings()` suspends for a snapshot.
  - `repo.ai` (`data/ai/ForgeAi.kt`): `isConfigured`, `describe(task)`, the food/label/program/
    chat calls, and `structured(task, system, parts, schemaName, schema, maxTokens)` for new
    features. Tasks: FOOD_PHOTO, LABEL, PROGRAM, COACH, BODY, PLAN, PING. Every failure is an
    exception whose message is safe to show. JSON schemas: every object needs
    `additionalProperties: false` and all properties in `required`; no min/max/minLength.
  - Existing helpers: `NutritionMath` (BMR, adaptive TDEE, trend, targets), `Dates`,
    `ImageUtils` (prepare/downscale/base64, savePhoto), `KnowledgeBase`.
- Navigation: `Routes` in `ForgeNavHost.kt` — screens receive callbacks, never a NavController.
- Domain contracts: `domain/guardrails/Guardrails.kt`, `domain/exercise/ExerciseCatalog.kt`,
  `domain/program/ProgramLibrary.kt`.
- UI: `ui/theme` (`ForgeTheme`, `Macro` colours) and `ui/components` (`ForgeCard`,
  `SectionHeader`, `MacroBar`, `StatTile`, `Pill`, `EmptyState`, `CalorieRing`, `LineChart`,
  `BarChart`). The design agent keeps these APIs source-compatible.

## Cross-feature APIs (implement exactly; other agents code against these)

| Provider | API |
|---|---|
| AI providers | `com.laksh.forge.data.ai.SecretStore` — `object SecretStore { suspend fun put(key: String, value: String); suspend fun get(key: String): String?; suspend fun remove(key: String) }` — Android-Keystore AES-GCM, its own DataStore file excluded from backup. Wearables uses it for WHOOP tokens and client secret. |
| Onboarding & plan | `com.laksh.forge.domain.plan.PlanTargets` — `fun observe(repo: ForgeRepository): Flow<com.laksh.forge.domain.MacroTargets?>` — the active plan's daily targets (after check-in adjustments), or null when there is no active plan. Today/Trends prefer it over `NutritionMath.targets`. |
| Guardrails | Keep everything in `Guardrails.kt` and add: `data class CrisisResource(val name: String, val contact: String, val description: String)`, `val crisisResources: List<CrisisResource>` (India-first: Tele MANAS 14416, plus the eating-disorder/crisis lines from `docs/research/guardrails.final.json`), `fun detectCrisis(text: String): Boolean` (deterministic phrase screen for self-harm, suicidal thoughts, purging), `fun profileFrom(s: ForgeSettings, weightKg: Double, bodyFatPct: Double?): GuardrailProfile`. |
| Wearables | `com.laksh.forge.data.wearables.WearableSync` — `object WearableSync { suspend fun syncAll(): SyncReport }` with `data class SyncReport(val days: Int, val sources: List<String>, val errors: List<String>)` — syncs Health Connect, WHOOP (if connected). The shell/Today/Settings call this for refresh. |
| Exercises | `ExerciseCatalog` as in the contract file (lazy asset load; `resolve` must handle common aliases like "BB bench", "lat pull-down", "RDL"). |
| Programs | `ProgramLibrary` as in the contract file. |

## Ownership

| Area | Owns (edit/create) | Contract it must keep |
|---|---|---|
| **AI providers** | `data/ai/**`, `data/remote/AnthropicClient.kt`, `data/remote/ModelPolicy.kt`, `feature/settings/AiSettingsScreen.kt` (+ its VM file), `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` | `ForgeAi` interface; `repo.ai` construction may be requested via requests/ |
| **Onboarding & plan** | `feature/onboarding/**`, `feature/plan/**`, `feature/progress/**`, `feature/settings/ProfileSettingsScreen.kt` (+VM), `domain/plan/**`, `data/db/BodyDao.kt`, `data/db/PlanDao.kt` | `OnboardingFlow(onDone)`, `PlanScreen`, `CheckinScreen`, `PlanSummaryCard`, `ProgressScreen`, `ProfileSettingsScreen` |
| **Programs** | `feature/train/**`, `feature/library/**`, `domain/program/**`, `data/db/TrainingDao.kt`, `app/src/main/assets/programs/**` | `TrainScreen`, `SessionScreen`, `LibraryScreen`, `ProgramDetailScreen`, `ProgramLibrary` |
| **Exercises & 3D** | `feature/exercise/**`, `domain/exercise/**`, `ui/skeleton/**`, `data/db/ExerciseDao.kt`, `app/src/main/assets/exercises/**` | `ExerciseDetailScreen`, `ExerciseBrowserScreen`, `ExerciseCatalog` |
| **Wearables** | `data/health/**`, `data/wearables/**`, `domain/readiness/**`, `feature/wearables/**`, `data/db/WearableDao.kt` | `WearablesScreen`, `ReadinessCard`, `WearableTrendsSection` |
| **Guardrails & coach** | `domain/guardrails/**`, `domain/KnowledgeBase.kt`, `domain/NutritionMath.kt`, `domain/training/**`, `feature/coach/**`, `feature/trends/TrainingVolumeSection.kt`, `data/db/InsightsDao.kt` | `Guardrails` object API, `TrainingVolumeSection` |
| **Design & shell** | `ui/theme/**`, `ui/components/**`, `MainActivity.kt` (shell only — keep calling `ForgeNavHost`), `feature/today/**`, `feature/trends/TrendsScreen.kt` + VM, `feature/log/**`, `feature/settings/SettingsScreen.kt` + `SettingsViewModel.kt`, `feature/settings/AppearanceSettingsScreen.kt`, `res/values/**`, `res/drawable/**`, `res/mipmap-*/**`, `res/font/**` | component APIs above; `SettingsScreen(onNavigate)` |
| **Tools, widgets, data** | `feature/tools/**`, `feature/widgets/**`, `data/backup/**`, `data/reminders/**`, `feature/settings/DataSettingsScreen.kt` (+VM), `data/db/BackupDao.kt`, `res/xml/*widget*` | `ToolsScreen`, `DataSettingsScreen` |

## Quality bar

- **Smooth above all.** State in view models as immutable data classes exposed as `StateFlow`;
  collect with `collectAsStateWithLifecycle`. No I/O or heavy math in composition. Lazy lists
  use stable `key`s and `contentType`, and `Modifier.animateItem()`. Animate with
  `graphicsLayer`/`animate*AsState`/springs, not layout-thrashing size changes. Use
  `MaterialTheme.motionScheme` spatial/effects specs for motion where applicable.
- **Material 3 Expressive look**: generous rounded shapes, `MaterialTheme` tokens only (no
  hard-coded colours except the macro palette in `ui/theme`), expressive loading indicators,
  haptic feedback (`LocalHapticFeedback`) on meaningful actions, edge-to-edge insets handled.
- Every screen has real empty, loading and error states. Errors show the exception message.
- Comments explain *why*, not what — match the existing code's style.
- Privacy: body/progress photos stay in app-private storage, are excluded from backup, and are
  only ever sent to an AI provider after an explicit user action on that screen.
- Safety: anything that sets calories, rates, phases or training load goes through `Guardrails`.
