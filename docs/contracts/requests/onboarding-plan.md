# Requests and notes from the Onboarding & plan area

No shared-file change is required for this area to compile or work. Everything it needs
already exists: `Routes.ONBOARDING / PLAN / CHECKIN / PROGRESS / SETTINGS_PROFILE`, the
`CAMERA` permission, CameraX (core, camera2, lifecycle, view), Coil, the v2 tables
(`body_measurement`, `progress_photo`, `phase_plan`, `weekly_checkin`) and every
`SettingsStore` key it writes.

## Notes for other areas (no file change required)

- **PlanTargets.observe(repo)** emits the active `PhasePlan`'s targets. Check-in adjustments
  are written onto the plan row itself, so the flow always reflects the latest adjustment.
  It emits `null` when there is no active plan (including v1 users with
  `onboardingVersion < 2`), in which case callers fall back to `NutritionMath.targets`.
- **PlanSummaryCard(modifier, onOpen)** is self-contained (own view model). With no active
  plan it shows the "Build your personalised plan" invitation; `onOpen` should go to
  `Routes.PLAN`, whose empty state calls `onReplan` (-> `Routes.ONBOARDING`).
- **Onboarding answers without a SettingsStore key** (kidney disease, diabetes medication,
  other condition, South Asian background, detrained, goal, preference, days per week,
  equipment, crisis-flag timestamp, photo-consent timestamp, last photo estimate) live in
  `domain/plan/PlanProfileStore` (DataStore file `forge_plan_profile`). The Coach already
  reads it for the kidney flag and the 30-day crisis lock.
- **Progress photos** are written only to `filesDir/progress_photos` (staged first in
  `cacheDir/progress_staging`), which the current backup rules already leave out.
- **First run**: `OnboardingFlow` sets `onboardingVersion = 2` and then `onboarded = true` as
  its very last write, so the shell's `!settings.onboarded` switch swaps to the main UI only
  after every row is saved.

## Optional 1. Back up the plan profile with the JSON export (data/backup, owner: Tools/data)

`PlanProfileStore` is included in Android Auto Backup through the existing `datastore/`
include, but not in the app's own JSON export. If the export should carry it, the
exporter can read `PlanProfileStore.current()` and restore with
`PlanProfileStore.update { exported }` - both are public in `domain/plan/PlanProfileStore.kt`.
