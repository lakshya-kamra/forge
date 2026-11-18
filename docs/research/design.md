# Forge design and motion spec

Researched 2026-11-17. Target: one user, vivo X200 Pro (6.78" LTPO AMOLED, 120 Hz, Dimensity 9400), Kotlin + Jetpack Compose, `compose-bom-alpha 2026.09.01`, Navigation 2.10.1, AGP 9.4 (from Forge's `gradle/libs.versions.toml`).

Goal: make Forge feel as smooth as PixelPlayer and Gradient Weather. Every item below is either (a) read directly from source code, with a link, or (b) marked **[Forge proposal]** when it is my recommendation and not copied from a reference app.

## Reference sources (pinned commits)

| Source | Pin | What it gave us |
|---|---|---|
| PixelPlayer (`PixelPlayerHQ/PixelPlayer`, formerly `theovilardo/PixelPlayer`, ~7k stars) | `63cb59b97aff1d60c5aa46afdf5ce489e632b267` | Nav transitions, springs, blur, shapes, typography, haptics, perf config |
| AndroidX Material3 (`androidx/androidx`, `androidx-main`) | `06a91ebff72e98820de5d991cbdb64c474418f80` | Exact MotionScheme spring values, MaterialShapes, LoadingIndicator, FloatingToolbar, ShortNavigationBar |
| Breezy Weather (`breezy-weather/breezy-weather`), open-source weather app with animated backgrounds | `5ec3b9bcb5a993dafd2eb2399b24fe97421c9831` | How a weather app crossfades and animates sky backgrounds |
| Gradient Weather (Subtle Signals, `com.subtlesignals.gradientweather`) | closed source | Only user-visible behaviour from its site and press. No code is available. |

Link prefixes used below:
- `PP` = https://github.com/PixelPlayerHQ/PixelPlayer/blob/63cb59b97aff1d60c5aa46afdf5ce489e632b267/
- `PPJ` = `PP` + `app/src/main/java/com/theveloper/pixelplay/`
- `AX` = https://github.com/androidx/androidx/blob/06a91ebff72e98820de5d991cbdb64c474418f80/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/
- `BW` = https://github.com/breezy-weather/breezy-weather/blob/5ec3b9bcb5a993dafd2eb2399b24fe97421c9831/ui-weather-view/src/main/kotlin/org/breezyweather/ui/theme/weatherView/materialWeatherView/

---

## 1. Theme root

- Wrap the app in `MaterialExpressiveTheme(colorScheme, motionScheme = MotionScheme.expressive(), shapes, typography)`. Its signature is `colorScheme: ColorScheme? = null, motionScheme: MotionScheme? = null, shapes: Shapes? = null, typography: Typography? = null`. If you pass null it falls back to `expressiveLightColorScheme()` and `MotionScheme.expressive()`. Source: `AX` `MaterialTheme.kt` L239-262.
- After that, read every motion spec from `MaterialTheme.motionScheme`. Don't hard-code springs. PixelPlayer does `val motionScheme = remember { MotionScheme.expressive() }`, then `motionScheme.defaultSpatialSpec<Float>()` for its player sheet. Sources: `PPJ` `presentation/components/UnifiedPlayerSheetV2.kt` L256-257 and `presentation/components/player/AnimatedPlaybackControls.kt` L91-92.
- Edge-to-edge: call `enableEdgeToEdge()` in `MainActivity`. Make status and navigation bars transparent and set `isStatusBarContrastEnforced = false` and `isNavigationBarContrastEnforced = false`. Choose light or dark icons from `ColorUtils.calculateLuminance(bg) > 0.55`. Source: `PPJ` `ui/theme/Theme.kt` (`PixelPlayStatusBarStyle`).
- Manifest: set `android:enableOnBackInvokedCallback="true"` on `<application>`. PixelPlayer does this in `PP` `app/src/main/AndroidManifest.xml` L41. Google's docs say predictive back is enabled by default, and from Android 15 the system back-to-home and cross-activity animations show for apps that opt in (https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture).

## 2. Motion system (springs and easing)

### 2.1 Token values (read from AndroidX source)

`MotionScheme.expressive()`, from `AX` `tokens/ExpressiveMotionTokens.kt`:

| Spec | dampingRatio | stiffness | Use in Forge |
|---|---|---|---|
| `fastSpatialSpec()` | 0.6 | 800 | Press scale, chip and toggle position, nav indicator, FAB morph |
| `defaultSpatialSpec()` | 0.8 | 380 | Sheet expand/collapse, card expand, container size and offset |
| `slowSpatialSpec()` | 0.8 | 200 | Full-screen hero moves (rest timer expanding to full screen) |
| `fastEffectsSpec()` | 1.0 | 3800 | Icon crossfade, colour flips on toggles |
| `defaultEffectsSpec()` | 1.0 | 1600 | Alpha and colour of content |
| `slowEffectsSpec()` | 1.0 | 800 | Background gradient colour drift (section 7) |

`MotionScheme.standard()`, from `AX` `tokens/StandardMotionTokens.kt`, for comparison: spatial springs are 0.9 damping at 1400/700/300 stiffness. Effects springs are the same as expressive.

Rule (from the token naming): **spatial** specs (position, size, scale) may bounce, so damping can be below 1. **Effects** specs (alpha, colour) never bounce, so damping is 1.0. Never put a bouncy spring on alpha or colour.

### 2.2 Easing tokens (for tween-based screen transitions)

From `AX` `tokens/MotionTokens.kt`:
- Emphasized: `CubicBezierEasing(0.2f, 0f, 0f, 1f)`
- EmphasizedDecelerate: `(0.05f, 0.7f, 0.1f, 1f)`
- EmphasizedAccelerate: `(0.3f, 0f, 0.8f, 0.15f)`
- Standard: `(0.2f, 0f, 0f, 1f)`
- Durations: Short1-4 = 50/100/150/200 ms, Medium1-4 = 250/300/350/400 ms, Long1-4 = 450/500/550/600 ms.

### 2.3 Concrete springs PixelPlayer uses (copy these)

| Interaction | Spec | Source |
|---|---|---|
| Nav item icon scale 1.0 to 1.1 on select | `spring(DampingRatioMediumBouncy, StiffnessMedium)` | `PPJ` `presentation/components/scoped/CustomNavigationBarItem.kt` |
| Nav indicator pill appears | `fadeIn(tween(100)) + scaleIn(spring(DampingRatioMediumBouncy, StiffnessLow))`. Exit: `fadeOut(tween(100)) + scaleOut(tween(100, EaseInQuart))` | same file |
| Press-to-shrink back button: 1.0 to 0.82 on press | `spring(stiffness = StiffnessMedium, dampingRatio = DampingRatioMediumBouncy)` | `PPJ` `presentation/components/LyricsFloatingToolbar.kt` L84-90 |
| Progress bar value | `spring(DampingRatioNoBouncy, StiffnessLow)` | `PPJ` `MainActivity.kt` L1074-1080 |
| List reorder placement while dragging | `animateItem(placementSpec = spring(DampingRatioNoBouncy, StiffnessMediumLow))`. Dragged item scale 1.015 with the same spring | `PPJ` `presentation/components/QueueBottomSheet.kt` L852-870 |
| Swipe-to-dismiss snap after the tension zone | `spring(dampingRatio = 0.8f, stiffness = StiffnessLow)`. Spring back: `spring(DampingRatioMediumBouncy, StiffnessMedium)` | `PPJ` `presentation/components/scoped/QueueItemDismissGestureHandler.kt` |
| Selection scale 1.0 to 0.98 | `spring(DampingRatioMediumBouncy, StiffnessLow)` | `PPJ` `presentation/components/subcomps/EnhancedSongListItem.kt` L127-136 |

### 2.4 Rules

- Drive every gesture-follow animation with `Animatable` plus `snapTo` while dragging, then `animateTo(target, spec, initialVelocity = velocity)` on release. PixelPlayer's `SheetMotionController` runs both the translation and the fraction under one `MutatorMutex` (`mutex.mutate { ... }`), so a fling can never fight a programmatic expand. It also scales velocity for the fraction: `initialVelocity / (collapsedY - expandedY)`. Source: `PPJ` `presentation/components/scoped/SheetMotionController.kt`.
- Use one `updateTransition` per item and derive all visuals from one or two `Float` progress values with `lerp`. Don't run N separate `animate*AsState`. PixelPlayer switched to this after its own audit flagged "7 parallel animations per list item". Sources: `PPJ` `presentation/components/subcomps/EnhancedSongListItem.kt` L106-160 and `PP` `app/performance_analysis.md` (Hallazgo 3).

## 3. Navigation pattern

### 3.1 Bottom navigation

- Forge currently uses `NavigationBar` and `NavigationBarItem` (`MainActivity.kt` L101-122). **[Forge proposal]** Replace it with M3 Expressive `ShortNavigationBar` and `ShortNavigationBarItem(selected, onClick, icon, label)`. The signature is `ShortNavigationBar(modifier, containerColor, contentColor, windowInsets, arrangement, content)` (`AX` `ShortNavigationBar.kt` L96-103). It is the shorter Expressive replacement for the classic bar.
- Alternative (PixelPlayer style): a custom floating pill bar. The item is a 64x32 dp indicator with `RoundedCornerShape(16.dp)`, a 48x24 dp icon box, selected icon scale 1.1, label `labelMedium` at 13 sp, weight `Medium` when selected and `Normal` when not, and colour animations of `tween(150)`. Ripple is disabled (`indication = null`) because the bouncing pill is the feedback. Source: `PPJ` `presentation/components/scoped/CustomNavigationBarItem.kt`.
- Hide the bar with pure `graphicsLayer { translationY = (height + shadow + padding) * hideFraction }`. Never animate its height. That keeps children from re-laying out or squishing. The bar's corner radius is animated **inside `graphicsLayer`** (`shape = cachedShape`, `clip = true`), so only the layer re-clips each frame. Source: `PPJ` `MainActivity.kt` L840-890.
- Use filled icons for selected tabs and outlined icons for unselected ones (`selectedIcon` and `icon` slots in the same file).

### 3.2 Floating toolbar (active workout session)

- **[Forge proposal]** In `SessionScreen`, use `HorizontalFloatingToolbar(expanded, floatingActionButton = { /* Log set */ }, scrollBehavior = FloatingToolbarDefaults.exitAlwaysScrollBehavior(...), colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors()) { prev / rest-timer / next }`. The overload with `floatingActionButton` is at `AX` `FloatingToolbar.kt` L278-293. `exitAlwaysScrollBehavior` is at L722, and `standardFloatingToolbarColors` and `vibrantFloatingToolbarColors` are at L762-806.
- PixelPlayer uses `HorizontalFloatingToolbar(expanded = true, expandedShadowElevation = 0.dp, colors = standardFloatingToolbarColors(toolbarContainerColor = surfaceContainerLow))` for Cancel/Save at the bottom of edit sheets. It is wrapped in `AnimatedVisibility(visible = !isKeyboardVisible, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut())` and placed `padding(bottom = innerPadding.bottom + 24.dp)`. Source: `PPJ` `presentation/components/EditSongSheet.kt` L640-690. Use this exact pattern for Forge's "edit set", "edit meal" and "edit plan" sheets.

### 3.3 Screen transitions (NavHost)

From `PPJ` `presentation/navigation/Transitions.kt`:
- **Push (enter)**: `slideInHorizontally(initialOffsetX = { it * 0.5f })` + `scaleIn(initialScale = 0.92f)` + `fadeIn`. Duration 450 ms. Slide and scale use `CubicBezierEasing(0.2f, 0.85f, 0.7f, 1f)`, and the fade uses the accelerate curve `(0.3f, 0f, 0.8f, 0.15f)`, so alpha does not arrive before the slide.
- **Push (exit)**: `slideOutHorizontally(targetOffsetX = { -it * 0.25f })` (parallax) + `fadeOut(tween(225))`.
- **Pop enter**: slide in from -25% + `scaleIn(0.95f)` + `fadeIn(tween(225))`.
- **Pop exit**: slide out to +50% + `scaleOut(0.92f)` + `fadeOut(tween(225))`.
- An AOSP-style variant in the same file uses 350 ms with `M3EmphasizedEasing (0.2,0,0,1)`, enter from 1/3 width, and exit scale 0.92. Its pop-exit uses easing `f*f*f`, so that during a predictive-back drag the page barely moves until commit.
- **Between top-level tabs**: use a directional slide. Index increases mean forward (`slideIn` from +50%, `slideOut` to -50%), and decreases mean backward. Spec is `tween(380, CubicBezierEasing(0.2f,0f,0f,1f))`, with fade at `tween(190)`. Source: `PPJ` `presentation/navigation/AppNavigation.kt` L523-575 (`BOTTOM_NAV_TRANSITION_DURATION = 380`, `mainRootRouteIndex`).
- **[Forge proposal]** Tabs in order: Today=0, Train=1, Log=2, Progress=3, Coach=4. Map them to the same `mainRootDirection` logic.

### 3.4 Predictive back

- In-app sheets and overlays: `PredictiveBackHandler(enabled) { progress -> progress.collect { e -> fraction = e.progress; edge = e.swipeEdge }; /* commit */ }` and `catch (CancellationException) { animate fraction back to 0 }`. Fall back to `BackHandler` below API 34. PixelPlayer feeds `backEvent.progress` into a collapse fraction. On commit it `snapTo`s the visual position the gesture reached and only then animates the rest, so there is no jump. Source: `PPJ` `presentation/components/scoped/PlayerSheetPredictiveBackHandler.kt`.
- Scale UI with the gesture: `graphicsLayer { val s = lerp(1f, 0.7f, backProgress()); scaleX = s; scaleY = s }`. Read the progress through a `() -> Float` lambda so there's no recomposition per frame. Source: `PPJ` `presentation/components/LyricsFloatingToolbar.kt` L69, L94-100.
- **[Forge proposal]** Session screen (active workout): during the predictive back gesture, scale content to `lerp(1f, 0.9f, p)`, round its corners `lerp(0.dp, 28.dp, p)`, and translate X by `p * 24.dp` toward the swipe edge. Commit means minimising to a "workout in progress" pill above the nav bar, not ending the workout.

## 4. Shared-element and container transforms

- PixelPlayer does **not** use `SharedTransitionLayout` (repository-wide grep: 0 hits). Its player expand is a hand-rolled container transform: an `Animatable` expansion fraction drives `graphicsLayer` translation and scale, `Modifier.layout {}` drives size, and a corner radius is read in the draw phase. Source: `PPJ` `presentation/components/UnifiedPlayerSheetV2.kt` L640-670 (graphicsLayer plus `.layout { measurable, constraints -> ... playerContentAreaHeightPxProvider() ... }`).
- **[Forge proposal]** For list to detail (exercise card to `ExerciseDetailScreen`, workout card to `SessionScreen`), use the official API:
  - Wrap `NavHost` in `SharedTransitionLayout { }` and expose `LocalSharedTransitionScope` and `LocalNavAnimatedVisibilityScope` (the `AnimatedContentScope` from each `composable {}`) via `CompositionLocal`. That is Google's documented pattern.
  - Card container: `Modifier.sharedBounds(rememberSharedContentState(ExerciseKey(id, Bounds)), animatedVisibilityScope, enter = fadeIn(), exit = fadeOut(), resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(), clipInOverlayDuringTransition = OverlayClip(cardShape))`.
  - Exercise image or icon: `Modifier.sharedElement(rememberSharedContentState(ExerciseKey(id, Image)), animatedVisibilityScope)`.
  - Title text: `sharedBounds` (not `sharedElement`) so that font size and colour can change.
  - Keys: use a `data class ExerciseKey(val id: Long, val type: SharedType)` with an enum, not strings.
  - Put size and padding modifiers **after** `sharedBounds()`, in the same order on both sides, or the bounds jump.
  - Limits (from the docs): no automatic shape morph between shapes, no `ContentScale` animation, and no support inside `Dialog` or `ModalBottomSheet`.
  - Source: https://developer.android.com/develop/ui/compose/animation/shared-elements
- Set `boundsTransform = BoundsTransform { _, _ -> MaterialTheme.motionScheme.defaultSpatialSpec() }` so container transforms use the 0.8 / 380 spring. **[Forge proposal]**

## 5. Shape language and shape morphing

### 5.1 Corner scale

- PixelPlayer's theme shapes are `small 8dp`, `medium 16dp`, `large 24dp` (`PPJ` `ui/theme/Shape.kt`). Almost every surface uses **smooth (squircle) corners**: `AbsoluteSmoothCornerShape(cornerRadius = X.dp, smoothnessAsPercent = 60)` from `com.github.racra:smooth-corner-rect-android-compose:v1.0.0` (`PP` `gradle/libs.versions.toml` L204).
- Cache shapes as singletons. Smooth-corner shapes compute Beziers, so a new instance per list item is expensive. `ShapeCache.smooth8 / 12 / 16 / 20 / 24 / 28 / 32 / smoothPill (50dp)`. Source: `PPJ` `ui/theme/ShapeCache.kt`.
- **[Forge proposal]** Forge scale: chips 8, list rows 16, cards 24, sheets and hero cards 32, buttons and pills full. Use smooth corners at 60% on cards and sheets only.

### 5.2 State-driven corner morphs (cheap and effective)

- **Active or "now" item** (PixelPlayer's currently playing song): the row corner goes 22dp to 50dp, its thumbnail corner goes 10dp to 50dp, and the container colour goes `surfaceContainerLow` to `primaryContainer`. Content colour goes `onSurface` to `onPrimaryContainer`. All are driven by one `highlightProgress` tween of 400 ms. Source: `PPJ` `presentation/components/subcomps/EnhancedSongListItem.kt` L138-160. **[Forge use]** Apply this to the current set row in a workout and to today's meal slot.
- **Selected item**: 2.5dp `primary` border, `secondaryContainer` fill, scale 0.98 (same file).
- **Play/pause button**: the corner is `60.dp` when playing and `26.dp` when paused, animated with `motionScheme.defaultSpatialSpec<Dp>()`. The shape is applied in `graphicsLayer { clip = true; shape = AbsoluteSmoothCornerShape(...) }`. The icon swap is a `Crossfade` with `motionScheme.fastEffectsSpec()`. Source: `PPJ` `presentation/components/player/AnimatedPlaybackControls.kt` L174-269. **[Forge use]** Use it for the Start/Pause workout and Start/Pause rest timer buttons.
- **Pressed button grows, neighbours shrink**: in a row of three buttons, the pressed one gets weight 1.1, the others get 0.65, and the rest get 1.0. Weights are animated with a press spec, and the state resets after 220 ms (600 ms for skip). Same file, L52-160. The M3 equivalent is `ButtonGroup(expandedRatio = ButtonGroupDefaults.ExpandedRatio /* 0.15f */)` with `Modifier.animateWidth(interactionSource)` on each child (`AX` `ButtonGroup.kt` L131-188, L856). **[Forge use]** Use it for the `-2.5 kg | Log | +2.5 kg` row and the RPE picker.
- **ToggleButton** morphs between three shapes (unchecked, pressed, checked) by itself (`AX` `ToggleButton.kt` L86-159). Use it for unit toggles (kg/lb) and "warm-up set" flags.

### 5.3 Polygon shapes (androidx.graphics.shapes and MaterialShapes)

- Material3 exposes 35 preset `RoundedPolygon`s in `MaterialShapes`: Circle, Square, Slanted, Arch, Fan, Arrow, SemiCircle, Oval, Pill, Triangle, Diamond, ClamShell, Pentagon, Gem, Sunny, VerySunny, Cookie4Sided, Cookie6Sided, Cookie7Sided, Cookie9Sided, Cookie12Sided, Ghostish, Clover4Leaf, Clover8Leaf, Burst, SoftBurst, Boom, SoftBoom, Flower, Puffy, PuffyDiamond, PixelCircle, PixelTriangle, Bun, Heart. Convert with `RoundedPolygon.toShape(startAngle = 0)`, and draw a morph with `Morph(start, end).toPath(progress, path)`. Source: `AX` `MaterialShapes.kt` L56, L91, L140+.
- PixelPlayer does **not** use `MaterialShapes` or `Morph`. It uses hand-written `Shape`s (`PolygonShape(sides, rotation)` and `RoundedStarShape(sides, curve = 0.09, rotation, iterations = 360)`), and it "morphs" by animating four corner percentages (`50/50/50/50` circle, `26` rounded square, `18/50/18/50` leaf) with `tween(600, FastOutSlowInEasing)` plus a 360-degree rotation per pager page over 900 ms. Sources: `PPJ` `utils/shapes/PolygonShape.kt`, `utils/shapes/RoundedStarShape.kt`, `presentation/screens/SetupScreen.kt` L2053-2080.
- **[Forge proposal]** Use real polygon morphs where the user achieves something:
  - **PR badge**: morph `MaterialShapes.Circle` to `MaterialShapes.Cookie12Sided` (or `SoftBurst`) with `fastSpatialSpec()` when a set beats a personal record, and rotate 45 degrees. Pair with `HapticFeedbackType.Confirm`.
  - **Avatar or streak tile**: `MaterialShapes.Cookie9Sided.toShape()` clip.
  - **Muscle-group icon containers** in the exercise library: rotate through `Cookie4Sided`, `Clover4Leaf`, `Pentagon`, `Gem` by muscle group, so the shapes act as a visual index.
  - Build `Morph` objects once with `remember(start, end) { Morph(start, end) }`, then draw in `drawWithCache { val p = Path(); onDrawBehind { morph.toPath(progress(), p); scale(size.minDimension); drawPath(p, color) } }`. Read `progress` in the draw phase.

## 6. Typography

- PixelPlayer bundles **Google Sans Flex** as a variable font (`R.font.gflex_variable`) and sets the roundness axis to max: `FontVariation.Settings(FontVariation.weight(w), FontVariation.Setting("ROND", 100f))`. It defines one `Font` per weight (Light, Normal, Medium, SemiBold, Bold). Source: `PPJ` `ui/theme/Type.kt` L66-110. Google Sans Flex is open source under SIL OFL 1.1 and is on Google Fonts. It has a ROND (roundness) axis from 0 to 100, per Fontsource (https://fontsource.org/fonts/google-sans-flex). The exact axis list (5 vs 6 axes) differs between sources, so verify it on fonts.google.com.
- PixelPlayer type scale (`PPJ` `ui/theme/Type.kt` L114-220):

| Role | Weight | Size / line height | Letter spacing |
|---|---|---|---|
| displayLarge | Bold | 48/56 sp | 0 |
| displayMedium | Bold | 36/44 | 0 |
| displaySmall | Normal | 30/38 | 0 |
| headlineLarge / Medium / Small | SemiBold | 32/40, 28/36, 24/32 | 0 |
| titleLarge | Normal | 22/28 | 0 |
| titleMedium | Medium | 18/24 | 0.15 |
| titleSmall | Medium | 14/20 | 0.1 |
| bodyLarge / Medium / Small | Normal | 16/24, 14/20, 12/16 | 0.5, 0.25, 0.4 |
| labelLarge / Medium / Small | Medium | 16/20, 14/16, 11/16 | 0.1, 0.5, 0.5 |

- Expressive "hero" titles: Montserrat (downloadable through the GMS font provider) at `displayLarge` SemiBold 60 sp, `TextGeometricTransform(scaleX = 1.5f)`, letterSpacing -0.02 em, lineHeight 0.95 em, `includeFontPadding = false`. `titleMedium` is Bold 32 sp at scaleX 1.3. Source: same file, L35-63.
- Material3 `Typography` also has `*Emphasized` slots (`displayLargeEmphasized` through `labelSmallEmphasized`) (`AX` `Typography.kt` L127-141). **[Forge proposal]** Fill the emphasized slots with weight +200 of the base style and use them for the numbers that matter (today's calories remaining, the current set's weight x reps, PR values).
- **[Forge proposal]** Forge currently uses `FontFamily.SansSerif` (`ui/theme/Type.kt` L9). Switch to bundled Google Sans Flex with ROND=100, as PixelPlayer does. Bundle it instead of downloading so the first frame has no font swap. Add `fontFeatureSettings = "tnum"` (tabular figures) to every style that shows changing numbers (timer, weight, reps, macros), so digits don't jitter while animating.
- Use `AnimatedContent(targetState = value, transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) using SizeTransform(clip = false) })` for counters that tick (reps, kg, kcal). **[Forge proposal]**

## 7. Colour, gradients and dynamic colour

### 7.1 Dynamic colour

- PixelPlayer's priority order is: a per-content scheme override, then `dynamicDarkColorScheme(context)` / `dynamicLightColorScheme(context)` on API 31+ inside `try/catch` (some OEM ROMs throw), then a static fallback. Source: `PPJ` `ui/theme/Theme.kt` L110-140. Forge should keep the `try/catch`, because vivo's Funtouch OS behaviour with Monet is **unverified**.
- **Per-content colour schemes**: PixelPlayer generates a full `ColorScheme` pair from album art (`generateColorSchemeFromSeed`), caches it in an `LruCache<String, ColorSchemePair>(20)`, then Room, then regenerates. Decoding happens through Coil with the cache disabled for local art. Source: `PPJ` `presentation/viewmodel/ColorSchemeProcessor.kt` L47-200. **[Forge use]** Seed a scheme per training split (Push, Pull, Legs, Core) or per muscle group, cache it the same way, and swap `MaterialExpressiveTheme(colorScheme = ...)` inside the session screen. Animate the swap by animating each role with `animateColorAsState(motionScheme.slowEffectsSpec())` in a small wrapper.

### 7.2 Gradient Weather (what is observable)

- From its site (https://gradientweather.app/), the palette, gradients and animated effects adapt to conditions: "deep charcoal" during thunderstorms, warm amber on clear afternoons, soft blue-white at night. It also has live weather effects (rain, snow, clouds, lightning) with haptic thunder, a starfield on clear nights, and a humidity card whose water level "sloshes" with phone orientation. Widgets use M3 Expressive shape morphing to switch styles. Fonts are Default/Chunky/Tall plus Lexend and OpenDyslexic. Store listing details are via https://appgoblin.info/apps/com.subtlesignals.gradientweather.
- **How it is implemented is not public.** Nothing below is Gradient Weather's code.

### 7.3 How Breezy Weather animates its sky (open source, verified)

- Each weather kind and day/night pair has a background drawable (`WeatherImplementorFactory.getBackgroundId(kind, daylight)`) plus a particle "implementor" (Rain, Snow, Cloud, Sun, Wind, Hail, MeteorShower). Source: `BW` `MaterialPainterView.kt` L318-345.
- A weather change crossfades two painter views: `ObjectAnimator` alpha 0 to 1 and old to 0, 300 ms, `AccelerateDecelerateInterpolator`. Source: `BW` `MaterialWeatherView.kt` L175-191, L227.
- Particles get a gravity-sensor parallax (`Sensor.TYPE_GRAVITY` turned into `rotation2D` and `rotation3D`, then smoothed by `DelayRotateController`). Drawing stops once the view is scrolled away (`scrollRate >= 1`). Source: `BW` `MaterialPainterView.kt` L104-160, L244-296.

### 7.4 Forge "context gradient" background **[Forge proposal]**

Gradient Weather maps weather to colour. Forge maps **training context** to colour:
- States: `Rest day`, `Pre-workout`, `In session` (per split), `Rest timer running`, `PR just hit`, `Evening / wind-down`, plus time of day (dawn, day, dusk, night) from the device clock.
- Each state has 3 stops (top, mid, bottom). Derive them from the active scheme (`primaryContainer`, `surfaceContainerHigh`, `surface`) so they stay harmonious with dynamic colour. For example, the rest timer shifts toward `tertiaryContainer` as time runs out.
- Animate the stops with `animateColorAsState(target, MaterialTheme.motionScheme.slowEffectsSpec())`, which is critically damped and doesn't flash. Draw them in **one** `Modifier.drawWithCache { onDrawBehind { drawRect(Brush.verticalGradient(listOf(c0(), c1(), c2()))) } }`, reading the colours as `State` inside the draw lambda so the composable never recomposes per frame.
- Ambient drift: an `rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(20_000, easing = LinearEasing), RepeatMode.Reverse))` that shifts the gradient's `startY` by about 8% of height. Read it in the draw phase only. Pause it when the app is not resumed (`LocalLifecycleOwner` with `repeatOnLifecycle`), and respect `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (reduce motion).
- API 33+ option (the X200 Pro runs Android 15): an AGSL `RuntimeShader` mesh gradient applied with `Modifier.graphicsLayer { renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect() }`, or drawn with `ShaderBrush(shader)`. Update uniforms in the draw phase. Fall back to the `Brush.verticalGradient` path below API 33.
- Optional "weather" particles for a PR celebration: a short (1.2 s) confetti or spark burst drawn in a `Canvas` with `withFrameNanos`. Stop the loop as soon as the burst finishes. Don't run a permanent particle loop on a fitness app's main screens.

## 8. Blur and translucency

- PixelPlayer blurs the whole app content behind the expanding player sheet. In `graphicsLayer { renderEffect = ... }` on API 31+, the radius is `fraction * 120px`, **quantised to 2 px steps**, and the `RenderEffect` object is cached and rebuilt only when the quantised radius changes (`BlurEffectCache`). That gives about 25 allocations per full expand instead of 60-120 per second. Source: `PPJ` `MainActivity.kt` L944-966 and L1150-1175.
- It also caches the bar's animated `Shape` per quantised radius pair (rebuilt only when the radius moves by 0.5 px or more), so the graphics layer can reuse its `Outline`. Source: `PPJ` `MainActivity.kt` L1176-1268 (`NavBarShapeCache`, `DynamicSmoothCornerShape`).
- A user setting `disableBlurAllOver` turns blur off entirely (same location). Forge should keep the same escape hatch.
- **[Forge proposal]**
  - When a modal sheet opens (set editor, food search), blur the screen behind it with the same quantised and cached `RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)`, max 60 px. Tie it to the sheet's offset fraction.
  - For a frosted nav bar or top bar over scrolling content, use Haze (`dev.chrisbanes.haze`, Compose Multiplatform, hardware-accelerated). Use `Modifier.hazeSource(state)` on the content and `Modifier.hazeBlur(input = HazeInput.Sources(state), style = HazeBlurStyle { blurRadius(20.dp) })` on the bar. That is the Haze 2.x API from its README (https://github.com/chrisbanes/haze). 2.1.0 was released 2026-11-02, so pin a 2.x version that has been out for at least two weeks.
  - Translucency without blur: `surfaceContainer.copy(alpha = 0.85f)` for the bar over content when blur is off. PixelPlayer's loading overlay uses `background.copy(alpha = 0.9f)` (`PPJ` `MainActivity.kt` L1085-1090).

## 9. Haptics

- Compose `HapticFeedbackType` values (from https://developer.android.com/reference/kotlin/androidx/compose/ui/hapticfeedback/HapticFeedbackType):
  - Added in UI 1.8: `Confirm`, `Reject`, `ContextClick`, `GestureEnd`, `GestureThresholdActivate`, `SegmentTick`, `SegmentFrequentTick`, `ToggleOn`, `ToggleOff`, `VirtualKey`.
  - Added in 1.9: `KeyboardTap`.
  - Original: `LongPress`, `TextHandleMove`.
- PixelPlayer's usage (counted across `presentation/`): `TextHandleMove` 17x for slider ticks and button taps, and `LongPress` 14x for long-press and "sheet hit top edge". It also uses `HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_ACTIVATE` / `_DEACTIVATE` when a swipe crosses 40% of item width, and `GESTURE_END` on release. Source: `PPJ` `presentation/components/scoped/QueueItemDismissGestureHandler.kt` L95-150.
- Slider haptic throttling: tick only when `(normalized * 50).roundToInt()` changes (50 steps), "to reduce binder chatter". Source: `PPJ` `presentation/components/WavyMusicSlider.kt` L160-178.
- A global kill switch: `staticCompositionLocalOf { AppHapticsConfig(enabled = true) }` plus a `NoOpHapticFeedback` (`PPJ` `presentation/utils/AppHaptics.kt`).
- **[Forge haptic map]**

| Event | Type |
|---|---|
| Set logged | `Confirm` |
| PR achieved | `Confirm`, then 120 ms later `Confirm` again (double pulse) |
| Invalid input / sync failed | `Reject` |
| Weight / reps stepper +/- | `SegmentTick` |
| Scrolling a number wheel (weight, kg ticks) | `SegmentFrequentTick`, throttled to value changes |
| Swipe-to-delete crosses threshold / pull-to-refresh armed | `GestureThresholdActivate`, plus the `_DEACTIVATE` constant when backing out |
| Toggle (warm-up, unit) | `ToggleOn` / `ToggleOff` |
| Long-press to reorder | `LongPress` |
| Rest timer reaches 0 | `Confirm` plus a system vibration pattern (the app may be in background) |

## 10. Lists and list-item animation

- Every `items()` call gets a stable `key` and a `contentType`. Examples: `key = { "album_song_${it.id}" }, contentType = { "album_song" }` (`PPJ` `presentation/screens/AlbumDetailScreen.kt` L329-330), and section-level `contentType = "daily_mix_section"` and others (`PPJ` `presentation/screens/HomeScreen.kt` L350-486).
- Use `Modifier.animateItem(fadeInSpec = tween(180), fadeOutSpec = tween(120), placementSpec = tween(200))` for expand/collapse sections, **instead of** wrapping each row in `AnimatedVisibility`. PixelPlayer's own audit found per-item `AnimatedVisibility` ran "50 expandVertically + 50 fadeIn" at once and caused jank. Sources: `PPJ` `presentation/screens/ArtistDetailScreen.kt` L335-365 and `PP` `app/performance_analysis.md` (Hallazgo 2, Fase 1.3).
- For drag-reorder: `placementSpec = spring(DampingRatioNoBouncy, StiffnessMediumLow)` while dragging, and the tween spec otherwise (`QueueBottomSheet.kt` L852-870). The library is `sh.calvin.reorderable:reorderable:3.1.0` (`PP` `gradle/libs.versions.toml` L216). **[Forge use]** Use it for reordering exercises in a plan.
- Swipe-to-dismiss with a "tension" zone: the first 60 dp of drag moves the row at most 20 dp (resistance). After that it springs to the finger (`0.8 / StiffnessLow`). Crossing 40% of width arms the delete (with haptic), and releasing below the threshold springs back with `MediumBouncy / Medium`. Source: `QueueItemDismissGestureHandler.kt` L60-200. **[Forge use]** Use it for deleting sets and meal items, with an undo snackbar.
- **[Forge proposal]** First-appearance stagger on Today: cards enter with `alpha 0 to 1` and `translationY 24dp to 0`, delay `index * 40 ms` capped at 6 items, using `defaultSpatialSpec`. Do it only once per process (remember a flag) so it never replays on tab switch.

## 11. Pull-to-refresh

- `PullToRefreshBox(isRefreshing, onRefresh, state = rememberPullToRefreshState(), indicator = { PullToRefreshDefaults.LoadingIndicator(state = state, isRefreshing = isRefreshing, modifier = Modifier.align(Alignment.TopCenter)) })`. The `LoadingIndicator` variant is the M3 Expressive shape-morphing indicator, not the old circular spinner. Source: `PPJ` `presentation/screens/LibraryMediaTabs.kt` L310-322, also used in `LibrarySongsTab.kt`, `StatsScreen.kt` and `PlaylistContainer.kt`.
- **[Forge use]** Use it for Health Connect sync on Today and Progress, and the wearables refresh. Fire `GestureThresholdActivate` when the pull passes `state.distanceFraction >= 1f`.

## 12. Loading and progress indicators

- `LoadingIndicator()` (indeterminate) morphs through 7 shapes: SoftBurst, Cookie9Sided, Pentagon, Pill, Sunny, Cookie4Sided, Oval. The determinate `LoadingIndicator(progress = { p })` morphs Circle (rotated 18 degrees) to SoftBurst. `ContainedLoadingIndicator()` adds a container. Source: `AX` `LoadingIndicator.kt` L99-237, L545-575.
- PixelPlayer uses:
  - `LoadingIndicator(Modifier.size(128.dp), color = primary)` for empty or loading sections (`PPJ` `presentation/screens/HomeScreen.kt` L595-602) and size 64 dp in the library.
  - `ContainedLoadingIndicator()` for detail screens (`AlbumDetailScreen.kt` L168).
  - `CircularWavyProgressIndicator()` for full-screen sync.
  - `LinearWavyProgressIndicator(progress = { animatedProgress })` where `animatedProgress` comes from `spring(NoBouncy, StiffnessLow)` (`PPJ` `MainActivity.kt` L1070-1110).
- Wavy slider: `WavySliderExpressive` uses wavelength `WavyProgressIndicatorDefaults.LinearDeterminateWavelength`, wave speed = wavelength / 2, amplitude 4 dp when active and 0 when idle, stroke 5 dp, thumb radius 8 dp, and thumb line 24 dp while dragging. Source: `PPJ` `presentation/components/WavySliderExpressive.kt` L58-82.
- Skeletons: `ShimmerBox`, a 3-stop `Brush.linearGradient(surfaceContainerHigh, surfaceContainerHighest, surfaceContainerHigh)` translated 0 to 1000 px with `infiniteRepeatable(tween(1000, delayMillis = 200))` (`PPJ` `presentation/components/ShimmerBox.kt`). **[Forge improvement]** Read the translate value inside `drawWithCache` / `onDrawBehind` rather than building the `Brush` in composition, which recomposes every frame.
- **[Forge mapping]**

| Use | Component |
|---|---|
| AI coach thinking | `LoadingIndicator()` 48 dp |
| PDF import / backup restore | `LinearWavyProgressIndicator(progress)` |
| Rest timer ring | `CircularWavyProgressIndicator(progress = { remaining / total })`, amplitude only while running |
| Daily-goal ring on Today | determinate `LoadingIndicator(progress = { kcal / goal })` (Circle to SoftBurst as you hit target) |
| Waiting on Health Connect | `ContainedLoadingIndicator()` |

## 13. Large top app bars and headers

- PixelPlayer uses `LargeFlexibleTopAppBar` on detail screens (`PPJ` `presentation/screens/PlaylistDetailScreen.kt` L267) and `MediumFlexibleTopAppBar` in sheets (`FileExplorerBottomSheet.kt`). Both take a `title` and a `subtitle` and collapse with `TopAppBarDefaults.exitUntilCollapsedScrollBehavior()`.
- A custom collapsing header reads collapse progress with `derivedStateOf` and applies alpha and translation in `graphicsLayer`. It does not scale text there, because scaling in `graphicsLayer` breaks ellipsis. Source: `PPJ` `presentation/components/ExpressiveTopBarContent.kt` L145-155.
- **[Forge use]** Exercise detail and Progress use `LargeFlexibleTopAppBar(title = { Text(name) }, subtitle = { Text("Chest · Barbell") })`.

## 14. Performance (making 120 Hz real)

### 14.1 Build

- **R8 full mode**: the default since AGP 8.0. Don't add `android.enableR8.fullMode=false` (https://developer.android.com/topic/performance/app-optimization/full-mode). Keep `isMinifyEnabled = true`, `isShrinkResources = true` and `proguard-android-optimize.txt` in release. PixelPlayer's `gradle.properties` also sets `android.r8.optimizedResourceShrinking=true` and `android.r8.strictFullModeForKeepRules=true` (`PP` `gradle.properties`). Full mode strips `Signature` and no-arg constructors unless kept, so check the Gson, Room and kotlinx-serialization paths after enabling it.
- **Baseline profiles**: PixelPlayer has a `:baselineprofile` module (`com.android.test` plus `androidx.baselineprofile` plugin, `targetProjectPath = ":app"`, `useConnectedDevices = true`). The app module sets `baselineProfile { automaticGenerationDuringBuild = false; saveInSrc = true; dexLayoutOptimization = true }` and depends on `androidx.profileinstaller` and `"baselineProfile"(project(":baselineprofile"))`. The generator has two rules:
  - `generateStartupProfile` with `includeInStartupProfile = true`
  - `generateBaselineProfile` with `includeInStartupProfile = false`, which runs real journeys: home scroll down/up x3, library tabs, search, open detail and return, player sheet expand/collapse, settings
  - A `benchmark` build type does `initWith(release)`, `isDebuggable = false`.
  - Sources: `PP` `baselineprofile/build.gradle.kts`, `PP` `baselineprofile/src/main/java/com/theveloper/pixelplay/baselineprofile/BaselineProfileGenerator.kt`, `PP` `app/build.gradle.kts` L129-245.
- **[Forge journeys]** For Forge's baseline profile: cold start to Today, scroll Today, switch all tabs, open the exercise library and scroll it, open exercise detail and go back, start a session and log 3 sets, open the rest timer, Progress charts scroll, Coach screen open. Forge already has `profileinstaller` 1.4.1.
- Add a `FrameTimingMetric` macrobenchmark on "scroll exercise library" and "log set". Watch `frameDurationCpuMs` P90/P99 against the 8.3 ms budget at 120 Hz.

### 14.2 Compose stability

- Strong skipping is on by default from Kotlin 2.0.20, and Forge is on 2.2.10. PixelPlayer keeps a stability config (`PP` `app/compose_stability.conf`) that lists its domain models and `kotlinx.coroutines.flow.StateFlow`, passed via `-P plugin:androidx.compose.compiler.plugins.kotlin:stabilityConfigurationPath=...`. The modern DSL is `composeCompiler { stabilityConfigurationFile = rootProject.layout.projectDirectory.file("stability_config.conf") }` (https://developer.android.com/develop/ui/compose/performance/stability/fix).
- **Caveat from Google's docs (newer than PixelPlayer's approach):** under strong skipping, plain `List` skips by reference (`===`), while `ImmutableList` or `@Immutable` collection holders force element-by-element `.equals()` every recomposition. Prefer keeping the **same list instance** across emissions (cache in the ViewModel, `distinctUntilChanged()`). Only put small, flat models with cheap `equals` (for example `java.time.LocalDate`, Room entity classes from non-Compose modules) in the stability config. PixelPlayer's own audit flagged "32 `.toImmutableList()`" calls as a GC-pressure risk (`PP` `app/performance_analysis.md` Hallazgo 5).
- Enable compiler reports behind a flag, as PixelPlayer does: `reportsDestination` and `metricsDestination` (`PP` `app/build.gradle.kts` L222-235).

### 14.3 Recomposition hygiene (patterns from PixelPlayer)

- **State slicing**: never `collectAsStateWithLifecycle()` a god-state in a screen that needs two fields. Use `remember { vm.state.map { Slice(it.a, it.b) }.distinctUntilChanged() }.collectAsStateWithLifecycle(Slice())`. PixelPlayer's audit rates the un-sliced version CRITICAL, because a 250 ms position ticker recomposed whole tabs (`PP` `app/performance_analysis.md` Hallazgo 1, Fase 1.1). **[Forge]** The rest timer and session clock must live in their own `StateFlow`, never inside `SessionUiState`.
- **Defer reads to draw or layout**: pass `() -> Float` providers and read them in `graphicsLayer {}`, `drawBehind {}`, `Modifier.offset { IntOffset(...) }` or `Modifier.layout {}`. Examples: the nav bar hide, blur radius, sheet translation and back-gesture scale (sections 3.1, 3.4 and 8). Don't use `Modifier.offset(x.dp)` or `alpha(x)` with animating values.
- **`derivedStateOf` for thresholds**: for example `val showFab by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > threshold } }` (`PPJ` `presentation/screens/HomeScreen.kt` L266). PixelPlayer also gates `BackHandler` enablement through `derivedStateOf` (`LibraryScreen.kt` L685-700).
- **Stable lambdas**: `remember(vm) { { vm.onX() } }` for callbacks passed into rows (`PPJ` `MainActivity.kt` L846-848).
- **Prewarm heavy content**: PixelPlayer has `PrewarmFullPlayerState.kt` and `FullPlayerCompositionPolicy.kt` (`PPJ` `presentation/components/scoped/`) to compose the expensive player before it's shown. **[Forge proposal]** Prewarm `SessionScreen` when the user taps "Start workout" on Today (compose it at alpha 0 under the current screen for one frame, or use `LazyLayoutPrefetchState`-style idle prefetch).
- Images: decode at target size (`albumArtTargetSizePx = albumArtSize.roundToPx()`) (`EnhancedSongListItem.kt` L105) and use Coil `size()` to match. Forge's exercise images and meal photos should do the same.

### 14.4 120 Hz on the X200 Pro (LTPO)

- The panel is LTPO, so the system lowers the refresh rate when idle. Compose UI 1.9+ has `Modifier.preferredFrameRate(Float)`, and 1.10+ has `Modifier.preferredFrameRate(FrameRateCategory.High)`. Docs: "the preferred frame rate affects the frame rate for the next frame, so use this method carefully", and "valid as long as the Composable is drawn" (https://developer.android.com/reference/kotlin/androidx/compose/ui/preferredFrameRate.modifier).
- **[Forge proposal]** Apply `preferredFrameRate(FrameRateCategory.High)` **only** on composables that are animating: the rest-timer wavy ring while running, the gradient background while it is transitioning, and sheets during drag. Apply it conditionally (`if (isAnimating) Modifier.preferredFrameRate(High) else Modifier`) so LTPO can drop to low Hz on static screens and save battery.
- Use `Animatable` and `withFrameNanos`-based loops (which follow vsync). Never use `delay(16)` loops.

## 15. Implementation checklist for Forge (ordered)

1. `MaterialExpressiveTheme` + `MotionScheme.expressive()` + Google Sans Flex (ROND 100, `tnum`) + dynamic colour with `try/catch`.
2. `ShortNavigationBar` (or a PixelPlayer-style floating pill) with `graphicsLayer` hide, and the directional 380 ms tab transitions.
3. Push/pop transitions from `Transitions.kt` (450 ms). Predictive back on the session screen and sheets.
4. `SharedTransitionLayout` around `NavHost`. Exercise card to detail, and workout card to session.
5. Lists: keys + `contentType` + `animateItem`, the swipe-to-delete tension handler, and reorderable plan editor.
6. Shapes: `ShapeCache`, the "current set" corner/colour morph, play/pause-style corner morph on timer buttons, a `ButtonGroup` stepper row, and the `MaterialShapes` PR badge morph.
7. Indicators: `PullToRefreshDefaults.LoadingIndicator`, `LoadingIndicator` for AI, and a wavy rest-timer ring.
8. Haptics map (section 9) behind a `LocalAppHapticsConfig` kill switch.
9. Context gradient background (section 7.4), plus blur behind sheets with the quantised `RenderEffect` cache.
10. Perf: baseline profile module, `FrameTimingMetric` benchmarks, stability config for flat models only, state slicing for timers, and `preferredFrameRate` only while animating.

## Unverified or open items

- Gradient Weather's implementation (shader vs Brush, durations, haptic patterns) is not public. Section 7.4 is a Forge design inspired by its observable behaviour, not a port.
- Whether vivo Funtouch OS on the X200 Pro supplies Monet dynamic colour to `dynamicColorScheme`. Test on the device. The fallback is already designed.
- Google Sans Flex axis list and ranges (ROND 0-100 is from Fontsource). Confirm on fonts.google.com before bundling.
- No Reddit thread specifically about PixelPlayer's smoothness was found. The claims here rest on its source code and its own `performance_analysis.md`.
- The Haze 2.x API names come from its README on `main`. Confirm them against the pinned release you choose.
