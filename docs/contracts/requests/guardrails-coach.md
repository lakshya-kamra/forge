# Requests and notes from the Guardrails & coach area

No shared-file change is required for my area to compile or work. Everything below is either
an optional follow-up or a note for the areas that consume `Guardrails`.

## Optional 1. Large-deficit opt-in (SettingsStore.kt, owner: integrator)

`nutrition_max_deficit_size` allows a deficit above 500 kcal/day (still within 25% of TDEE and
all other caps) only after an explicit opt-in that warns about muscle loss. There is no
settings key for that opt-in, so `Guardrails.checkNutrition` always applies the 500 kcal cap,
which is the safe default. If the opt-in is wanted later:

```kotlin
// ForgeSettings (v2: profile & safety block)
/** Explicit opt-in to deficits above 500 kcal/day (still capped at 25% of maintenance). */
val allowLargeDeficit: Boolean = false,
// SettingsStore.K
val allowLargeDeficit = booleanPreferencesKey("allow_large_deficit")
// mapping
allowLargeDeficit = p[K.allowLargeDeficit] ?: false,
// setter
suspend fun setAllowLargeDeficit(v: Boolean) = edit { it[K.allowLargeDeficit] = v }
```

I would then add a defaulted `allowLargeDeficit: Boolean = false` field to `GuardrailProfile`.

## Notes for other areas (no file change required)

- **Kidney disease.** The flag lives in the plan feature's `PlanProfileStore`, which
  `Guardrails.profileFrom(settings, ...)` cannot see. `GuardrailProfile` has a defaulted
  `kidneyDisease` field: pass `profile.copy(kidneyDisease = planProfile.kidneyDisease)` and
  `checkNutrition` caps protein at 0.8 g/kg itself (the coach already does this).
- **Crisis lock.** The coach records a crisis disclosure with
  `PlanProfileStore.update { it.copy(crisisFlaggedAt = now) }`, the same mechanism the
  check-in and onboarding use. The words are never stored apart from the chat message the
  user typed.
- **Load jumps.** `Guardrails.loadJumpFlag` returns `BLOCK` for a single-session jump above
  10% that is bigger than the smallest increment (2.5 kg by default; optional
  `smallestIncrementKg` parameter), and `INFO` when the jump is the smallest available
  increment (double-progression case). `ProgressionEngine.guard` holds the load on `BLOCK`.
- **Guardrail APIs available** (all in `Guardrails`): `checkNutrition`, `allowedPhases`,
  `deficitBlockers`, `calorieFloor`, `maxLossPctPerWeek`, `gainBand`,
  `monthlyPctToKgPerWeek`, `startingSurplusKcal`, `cutLengthFlag` (12-week break prompt,
  16-week stop), `dietBreakFlag`, `newCutFlag`, `fibreFlag`, `energyAvailabilityFlag`,
  `lowIntakeCheckinFlag`, `rapidLossFlag`, `loadJumpFlag` (+ `maxSafeLoadKg`),
  `countsAsHardSet`, `rirTarget`, `restSeconds`, `restFlag`, `repRangeFlag`, `deloadFlag`,
  `trainingVolumeFlags`, `frequencyFlag`, `volumeIncreaseFlag`, `photoBodyFatRange`,
  `photoBodyFatMessage`, `photoBodyFatBlock`, `PHOTO_ANALYSIS_RULES`, `SCOFF_QUESTIONS`,
  `SCOFF_INTRO`, `SCOFF_POSITIVE_MESSAGE`, `scoffScore`, `crisisResources`, `CRISIS_MESSAGE`,
  `detectCrisis`, `detectMedicalRedFlags`, `detectPainStop`, `neutralizeLanguage`,
  `promptRules(profile)`. `NutritionMath.checkedTargets(...)` returns targets plus the
  guardrail flags that explain them.
- **Fibre.** `NutritionMath.targets` now returns 25 g fibre (WHO 2023 minimum); the old
  14 g/1000 kcal figure is not in the verified research.
