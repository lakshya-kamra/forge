# Requests from the Programs area

None of these block compilation: the Programs code builds against the files as they are today.

## 1. (Optional) Baseline profile entries for the training screens

`app/src/main/baseline-prof.txt` (shell/integrator owned). The session screen is the hottest
interactive path in the app; adding these keeps its first frames AOT-compiled on side-loaded APKs.

```
HSPLcom/laksh/forge/feature/train/SessionScreenKt;->**(**)**
HSPLcom/laksh/forge/feature/train/SessionComponentsKt;->**(**)**
HSPLcom/laksh/forge/feature/train/SessionViewModel;->**(**)**
HSPLcom/laksh/forge/domain/program/ProgressionEngine;->**(**)**
HSPLcom/laksh/forge/feature/train/TrainScreenKt;->**(**)**
HSPLcom/laksh/forge/feature/library/LibraryScreenKt;->**(**)**
```

## 2. (Optional, Design & shell) Next program day on Today

If Today wants a "Next workout" card, it can compute it without touching Programs internals:

```kotlin
val program = repo.programDao.observeActive()            // Flow<Program?>
val config = ProgramStore.decodeConfig(program.progressionJson)
val weeks = ProgramStore.decodeWeeks(program.weeksJson)
val next = ProgramSchedule.nextDayName(config, weeks, dayNames, recentFinishedDayNamesNewestFirst)
val label = ProgramSchedule.weekLabel(config, weeks)    // "Week 2 of 4 · 3s week"
```

(`com.laksh.forge.domain.program.ProgramStore` / `ProgramSchedule`; recent day names come from
`ProgramStore.training.observeFinishedForProgram(programId)` mapped through the program's days.)

## Notes for the integrator

- No schema changes: TrainingDao only gained queries over existing tables/columns.
- No new routes, manifest entries or dependencies. Custom Tabs uses the existing
  `androidx.browser`; shape morphing uses `MaterialShapes` + `androidx.graphics.shapes.Morph`,
  which material3 exposes transitively (OmniCal uses the same imports on this toolchain).
- Program progression state lives inside `Program.progressionJson` (format: `ProgressionConfig`
  in `domain/program/ProgressionModels.kt`); training maxes live in `training_max`.
- `assets/programs/*.json` were generated from `docs/research/programs/verified/*.json` (all 24,
  none rejected). The converter adds an `engine` block and per-exercise `rule` fields; every
  parameter in them is taken from the program's own progression/deload text.
