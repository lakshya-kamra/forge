# Requests from the Exercises & 3D area

Nothing here blocks compilation: the area builds against the shared files as they are.
Routes, the Settings entry point (`Routes.EXERCISES`), INTERNET permission and Coil are all
already in place.

## 1. Note: `ExerciseBrowserScreen` moved files (no action)

The contract stub had both composables in `feature/exercise/ExerciseDetailScreen.kt`.
`ExerciseBrowserScreen(onBack, onOpen)` now lives in `feature/exercise/ExerciseBrowserScreen.kt`
in the same package with the same signature, so the import in `ForgeNavHost.kt`
(`com.laksh.forge.feature.exercise.ExerciseBrowserScreen`) is unchanged.

## 2. Note: new query in `ExerciseDao` (no action)

`ExerciseDao.observeHistoryForNames(exerciseId: String?, names: List<String>)` was added
(reads `set_log` only; no schema change). Room generates it with the rest of the DAO.

## 3. (Optional) Baseline profile entries

`app/src/main/baseline-prof.txt` (integrator owned). The 3D renderer runs every frame at
120 Hz; AOT-compiling it avoids JIT warm-up jank in the first seconds of a demo, and the
browser list draws a skeleton thumbnail per row.

```
HSPLcom/laksh/forge/ui/skeleton/SkeletonScene;->**(**)**
HSPLcom/laksh/forge/ui/skeleton/PoseSolver;->**(**)**
HSPLcom/laksh/forge/ui/skeleton/MotionClip;->**(**)**
HSPLcom/laksh/forge/ui/skeleton/TwoBoneIk;->**(**)**
HSPLcom/laksh/forge/ui/skeleton/EquipmentBuilder;->**(**)**
HSPLcom/laksh/forge/ui/skeleton/OrbitCamera;->**(**)**
HSPLcom/laksh/forge/ui/skeleton/MuscleGlows;->**(**)**
HSPLcom/laksh/forge/ui/skeleton/SkeletonColors;->**(**)**
HSPLcom/laksh/forge/ui/skeleton/DemoController;->**(**)**
HSPLcom/laksh/forge/ui/skeleton/SkeletonViewerKt;->**(**)**
HSPLcom/laksh/forge/domain/exercise/ExerciseCatalog;->**(**)**
HSPLcom/laksh/forge/domain/exercise/ExerciseNameMatcher;->**(**)**
HSPLcom/laksh/forge/feature/exercise/ExerciseDetailScreenKt;->**(**)**
HSPLcom/laksh/forge/feature/exercise/ExerciseBrowserScreenKt;->**(**)**
```

## 4. (Optional, Programs / Train) Link to the library

`ExerciseBrowserScreen` is reachable from Settings today. If Train or Library want a
"Browse exercises" entry, navigate to `Routes.EXERCISES`; opening a lift by any name goes
through `Routes.exercise(ExerciseCatalog.keyFor(name))`, which already handles ids, gym
slang ("BB bench", "RDL", "lat pull-down") and unknown free-text names.
