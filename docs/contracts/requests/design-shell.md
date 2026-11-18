# Requests from: Design system & app shell

## 1. `ForgeNavHost.kt` — screen transitions + predictive-back-friendly motion (required)

The transition specs live in `ui/theme/Motion.kt` (`ForgeNavTransitions`), so the change to
`ForgeNavHost.kt` is only wiring. They are tweens on purpose: predictive back seeks the
transition, and a tween's timeline maps 1:1 onto the gesture. With Settings > Appearance >
Reduce motion on they collapse to 150 ms cross-fades.

Add imports:

```kotlin
import com.laksh.forge.ui.theme.ForgeNavTransitions
import com.laksh.forge.ui.theme.LocalReduceMotion
```

Replace the `NavHost(...)` call header inside `ForgeNavHost` with:

```kotlin
    val reduceMotion = LocalReduceMotion.current

    NavHost(
        navController = nav,
        startDestination = Routes.TODAY,
        modifier = modifier,
        enterTransition = ForgeNavTransitions.enter(reduceMotion),
        exitTransition = ForgeNavTransitions.exit(reduceMotion),
        popEnterTransition = ForgeNavTransitions.popEnter(reduceMotion),
        popExitTransition = ForgeNavTransitions.popExit(reduceMotion)
    ) {
```

(`ForgeNavTransitions.*` return `AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition/ExitTransition`,
the exact parameter types of navigation-compose 2.10 `NavHost`.)

## 2. `ForgeNavHost.kt` — Today opens Wearables from the readiness card / steps tile

`TodayScreen` gained an optional trailing parameter (default `{}`; the old call still compiles):

```kotlin
        composable(Routes.TODAY) {
            TodayScreen(
                onAddFood = { nav.navigate(Routes.LOG) },
                onOpenEntry = { },
                onOpenPlan = { nav.navigate(Routes.PLAN) },
                onOpenProgress = { nav.navigate(Routes.PROGRESS) },
                onOpenWearables = { nav.navigate(Routes.WEARABLES) }
            )
        }
```

## 3. Notes for other areas (no file change needed)

- **Shell insets.** `MainActivity` pads the NavHost by the status-bar inset (consumed, so
  `windowInsetsPadding`/`TopAppBar` default insets do not double it). Non-top-level routes also
  get the navigation-bar inset at the bottom; Train and Coach get the floating bar's height.
  Today, Trends and Settings draw under the floating bar and pad their lists with
  `LocalFloatingBarClearance` (`ui/components/FloatingNavBar.kt`). Train/Coach can opt in the
  same way: read `LocalFloatingBarClearance.current` for the list's bottom content padding and
  ask the design area to add the route to `DrawsUnderBar` in `MainActivity.kt`.
- **Snackbars.** One host for the app: `LocalForgeSnackbar.current` (nullable outside the shell).
  `SnackbarHostState.showUndo(message, onUndo, onExpired)` is the undo helper.
- **Theme locals.** `LocalForgeSettings` (live `ForgeSettings?`), `LocalReduceMotion`,
  `LocalHapticsEnabled`. `LocalHapticFeedback` is already gated on Settings > Haptics, so
  feature code can call it unconditionally.
- **Toolkit** (`ui/components`): `ForgeSubScreen` / `ForgeTopBar` (large collapsing app bar),
  `Modifier.pressScale` / `Modifier.bounceClick` / `Modifier.enterStagger`, `AnimatedCounter`,
  `ExpressiveLoading`, `Modifier.shimmer` / `ShimmerBlock` / `ShimmerCard`, `SwipeToDeleteRow`,
  `SegmentedNavRow` / `SegmentedSwitchRow` / `SegmentedContentRow` / `IconBadge`,
  `ConnectedChoiceGroup`, `ErrorNotice`, `ExpressiveFab`, `FloatingNavBar`.
- **Scheme helpers** (`ui/theme/Theme.kt`): `ColorScheme.appBackground`, `.cardContainer`,
  `.isDark`, `.accentFor(color)`, `.tintedContainer(color)`. `ForgeCard` now defaults to
  `cardContainer` (white cards on a soft grey page in light mode).
- **Settings hub** no longer contains the API key / cost tier, goal / targets, or Health Connect
  sections; they belong to AI Settings, Profile Settings and Wearables respectively.
- `app/src/main/baseline-prof.txt` is unchanged (it already covers every class under
  `com/laksh/forge/`), so the new shell and components are AOT-compiled too.
