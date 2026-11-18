# Requests from the AI providers area

Everything below touches files the AI area does not own. The AI code compiles without any of
them (the old `AnthropicClient(apiKeyProvider, modelProvider)` constructor is kept as a
deprecated shim), but until request 1 lands the app-wide `repo.ai` is still Anthropic-only.

## 1. ForgeRepository: construct `MultiProviderAi` (required)

`app/src/main/java/com/laksh/forge/data/ForgeRepository.kt`

Replace

```kotlin
import com.laksh.forge.data.remote.AnthropicClient
...
    val ai: com.laksh.forge.data.ai.ForgeAi = AnthropicClient(
        apiKeyProvider = { cachedSettings.apiKey },
        modelProvider = { task -> cachedSettings.tier.modelFor(task) }
    )
```

with

```kotlin
import com.laksh.forge.data.ai.ForgeAi
import com.laksh.forge.data.ai.MultiProviderAi
...
    // Routes every task to the provider of whatever key the user saved (Settings → AI). Reads
    // the cached settings snapshot so describe()/isConfigured never suspend.
    val ai: ForgeAi = MultiProviderAi(
        context = context,
        settings = { cachedSettings },
        settingsStore = settingsStore
    )
```

Note `cachedSettings` is declared above `ai`, so the lambda captures an initialised field.
After this lands, nothing constructs `AnthropicClient` any more; it can stay (it also hosts
`AnthropicClient.JSON`, see request 3) or be removed together with request 3.

## 2. ForgeApp: migrate the v1 plaintext key at start-up (required)

`app/src/main/java/com/laksh/forge/ForgeApp.kt`, in `onCreate()` after the settings cache
collector is launched:

```kotlin
import com.laksh.forge.data.ai.AiKeys
import com.laksh.forge.data.ai.SecretStore
...
        SecretStore.attach(this)
        // Move a v1 plaintext API key into the Keystore-encrypted SecretStore, blank the
        // plaintext setting, and warm the in-memory key so the first AI call needs no decrypt.
        appScope.launch {
            runCatching { AiKeys.migrateLegacy(settingsStore) }
        }
```

`AiKeys.migrateLegacy` is idempotent (safe on every launch). It also clears `aiConfigured`
when the encrypted key is missing (e.g. after a restore, since `forge_secrets` is excluded
from backup). The AI settings screen and `MultiProviderAi` also run it lazily, so this is a
belt-and-braces call, but it is what guarantees the plaintext key leaves disk on first run
even if the user never opens Settings → AI.

## 3. OpenFoodFactsClient: stop borrowing the AI client's Json (optional, cleanup)

`app/src/main/java/com/laksh/forge/data/remote/OpenFoodFactsClient.kt` line ~60:

```kotlin
val root = runCatching { AnthropicClient.JSON.parseToJsonElement(raw).jsonObject }
```

→

```kotlin
val root = runCatching { com.laksh.forge.data.ai.AiJson.json.parseToJsonElement(raw).jsonObject }
```

Once this and request 1 are in, `data/remote/AnthropicClient.kt` has no users and the AI area
is happy for the integrator to delete it.

## 4. Settings hub (Design & shell area): drop the v1 plaintext key UI (required for privacy)

`feature/settings/SettingsScreen.kt` / `SettingsViewModel.kt` still have the v1 "Claude
connection" card that writes the key in plaintext via `settingsStore.setApiKey(...)`, and
copy saying photos go to "Anthropic's API". With any-provider support:

- Replace that card with a row that navigates to `Routes.SETTINGS_AI` (the new
  `AiSettingsScreen` handles paste/detect/save/test/remove, tiers, cost and per-task models).
  A good subtitle is `repo.ai.describe(Task.FOOD_PHOTO)`, or "Not set up" when
  `!settings.hasApiKey`.
- Remove `SettingsViewModel.setApiKey` / `testKey`. Never call `settingsStore.setApiKey(...)`
  with a real key again; the encrypted store is `com.laksh.forge.data.ai.SecretStore`.
- Make the photo-privacy copy provider-neutral, e.g. "A meal photo is sent only to the AI
  provider you chose in Settings → AI, only when you ask for an estimate. What that provider
  keeps or trains on is shown there."

`CostTier.label`, `.blurb`, `.roughMonthly` and `.modelFor(task)` still exist (blurbs are now
provider-neutral, and `roughMonthly` is computed from the registry's Anthropic prices) so the
v1 card compiles until it is replaced.

## 5. Feature copy that names Anthropic (nice to have)

`LogViewModel` shows "Add your Anthropic API key in Settings to use photo estimates." Any
provider works now: "Add an AI key in Settings → AI to use photo estimates."

## 6. Backup rules: keep photos out (information, no change needed)

`res/xml/backup_rules.xml` and `data_extraction_rules.xml` (owned by AI providers) now exclude
`datastore/forge_secrets.preferences_pb`. `meal_photos/` and `progress_photos/` are kept out
by not being included (only `database` and `datastore/` are). Lint rejects excludes outside an
included path, so they are not listed. If the Tools/Data area ever needs another directory in
backup, please ask for a specific `<include>` rather than `path="."` under the file domain,
which would pull the photo folders in.
