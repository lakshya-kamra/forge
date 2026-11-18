# Material 3 Expressive API inventory: material3 1.5.0-alpha29 (compose-bom-alpha 2026.09.01)

Generated 2026-11-17 from the exact AAR the OmniCal project (and Forge, on the same BOM) compiles against.
Full raw extraction (71 declarations/sections, ~2,000 lines): `docs/research/raw/m3_1.5.0-alpha29_expressive_api.txt`.

## 0. Provenance and method

- BOM: `~/.gradle/caches/modules-2/files-2.1/androidx.compose/compose-bom-alpha/2026.09.01/.../compose-bom-alpha-2026.09.01.pom`
  pins `androidx.compose.material3:material3` to **1.5.0-alpha29**.
- AAR: `~/.gradle/caches/modules-2/files-2.1/androidx.compose.material3/material3-android/1.5.0-alpha29/473974471875087c1b82fd3112636010add0e7f4/material3.aar`
  -> `classes.jar` extracted to a scratch dir.
- Its Gradle module metadata lists the API dependencies: compose foundation 1.13.0-alpha01, foundation-layout / runtime / ui / ui-text / material-ripple 1.12.0, **androidx.graphics:graphics-shapes 1.0.1** (source of `RoundedPolygon` and `Morph`), kotlin-stdlib 2.2.20. Kotlin metadata version in the classes: 2.2.0.
- Tools: `javap` (Temurin 17.0.20) for JVM signatures, plus a small Java program using
  `org.jetbrains.kotlin:kotlin-metadata-jvm:2.2.0` (already in the Gradle cache) that reads the `@kotlin.Metadata`
  annotation straight from the class bytes and prints the Kotlin declarations, together with the method-level
  JVM annotations (opt-in markers, `@Deprecated`).

What is exact vs inferred:

| Part of each signature below | Status |
| --- | --- |
| Function / parameter **names**, parameter **order**, **types**, nullability, receivers, `@Composable` on lambda types, extension-lambda receivers (`RowScope.() -> Unit`) | **Exact** (decoded from Kotlin metadata) |
| Whether a parameter **has** a default (`= <default>`) | **Exact** (metadata `declaresDefaultValue` flag) |
| The default **value** itself | **Not recoverable** from metadata; shown as `<default>`. Where I state a value, it is marked *(inferred)* and the evidence is given. |
| `@Composable` on the function, opt-in markers, `@Deprecated` | **Exact** (JVM annotations on the matching method) |
| Declarations deprecated with level `HIDDEN` | **Omitted** (binary-compat only, not callable from source) |

How defaults appear in the bytecode (why `javap` alone is not enough):
- **@Composable functions have no `$default` method.** The Compose compiler appends `Composer`, `int $changed` (one or more) and `int $default` bitmask parameters to the real method, e.g.
  `ShortNavigationBar-kQ6Tpik(Modifier, long, long, WindowInsets, int, Function2, Composer, int, int)`.
  Name suffixes like `-kQ6Tpik` are Kotlin mangling for inline value-class parameters (`Color` -> `long`, `Dp` -> `float`, value classes such as `ShortNavigationBarArrangement` -> `int`).
- **Non-composable functions with defaults get a synthetic `name$default(..., int mask, Object)`**, e.g.
  `toPath$default(Morph, float, Path, int, int, Object)` and `floatingToolbarVerticalNestedScroll-gKdo67w$default(...)`.
- JVM bytecode does not keep Kotlin parameter names; they came from the metadata.

## 1. Opt-in status in 1.5.0-alpha29 (important)

Only these still carry `@ExperimentalMaterial3ExpressiveApi`:

- `LoadingIndicator` (both overloads), `ContainedLoadingIndicator` (both), `object LoadingIndicatorDefaults`
- `class MaterialShapes` (and therefore `MaterialShapes.Circle` etc.)
- `Morph.toPath(...)`, `RoundedPolygon.toPath(...)`, `RoundedPolygon.toShape(...)`
- `PullToRefreshDefaults.LoadingIndicator(...)` (and two deprecated colour properties on `PullToRefreshDefaults`)

Everything else in this document has **no opt-in annotation** in alpha29 (MaterialExpressiveTheme, MotionScheme,
wavy progress indicators, ButtonGroup, ToggleButton family, SplitButtonLayout and SplitButtonDefaults, floating toolbars,
ShortNavigationBar, FlexibleBottomAppBar, WideNavigationRail, FAB menu, ToggleFloatingActionButton, extended FABs,
flexible top app bars, SegmentedListItem, emphasized typography). `@ExperimentalMaterial3Api` was not found on any
of them either.

## 2. Is there a newer compose-bom-alpha? (checked 2026-11-17 on Google Maven)

`https://dl.google.com/android/maven2/androidx/compose/compose-bom-alpha/maven-metadata.xml`: latest **2026.11.00**
(lastUpdated 2026-11-04). Material3 pinned by each newer BOM (read from each POM):

| compose-bom-alpha | material3 | (ui / foundation) |
| --- | --- | --- |
| 2026.09.01 (current) | 1.5.0-alpha29 | - |
| 2026.10.00 | 1.5.0-beta01 | - |
| 2026.10.01 | 1.5.0-beta02 | - |
| **2026.11.00** | **1.5.0-rc01** | 1.13.0-rc01 / 1.13.0-rc01 |

The material3 maven-metadata lists `1.5.0-rc01` as the newest 1.5.0 version. The stable `compose-bom` latest is
2026.09.00 and pins material3 **1.4.0** (so Expressive 1.5 APIs are still only on the alpha BOM).

Release notes (https://developer.android.com/jetpack/androidx/releases/compose-material3, fetched 2026-11-17), changes after alpha29 relevant to this inventory:
- **1.5.0-beta01 (2026-10-07)** API changes: `TopAppBarDefaults.enterAlwaysScrollBehavior` overload with `reverseLayout` restored as deprecated; Slider/RangeSlider compat overloads restored as hidden; **"Updated PolygonShape APIs: moved CornerRounding to top-level with dp() and fraction() factories, replaced transformed() and scaledToFit() extensions with in-place mutable PolygonShape.transform(...) and PolygonShape.copy()"**; `HorizontalCenteredHeroCarousel` `maxItemWidth` renamed `preferredItemWidth`; `CarouselDefaults.ItemSpacing` / `ContentPadding` added. (`PolygonShape` does not exist in the alpha29 material3 jar; which artifact it lives in was not verified.)
- **1.5.0-beta02 (2026-10-21)**: no listed API changes.
- **1.5.0-rc01 (2026-11-04)**: bug fixes only, including "Resolve hardcoded pressed shape in ToggleButton to use design token ButtonSmallTokens.PressedContainerShape".
- alpha29 itself (2026-09-23): source-breaking Slider change (`onValueChange` required, order changed).

Implication *(interpretation)*: moving Forge to `compose-bom-alpha:2026.11.00` (material3 1.5.0-rc01) is low-risk for the
APIs listed here; signatures below were not re-extracted for rc01.

---

## 3. Theme and motion

```kotlin
// androidx.compose.material3.MaterialThemeKt
@Composable fun MaterialExpressiveTheme(
    colorScheme: ColorScheme? = <default>,
    motionScheme: MotionScheme? = <default>,
    shapes: Shapes? = <default>,
    typography: Typography? = <default>,
    content: @Composable () -> Unit,
)
@Composable fun MaterialTheme(                       // overload that also takes a MotionScheme
    colorScheme: ColorScheme = <default>,
    motionScheme: MotionScheme = <default>,
    shapes: Shapes = <default>,
    typography: Typography = <default>,
    content: @Composable () -> Unit,
)
@Composable fun MaterialTheme(colorScheme: ColorScheme = <default>, shapes: Shapes = <default>,
                              typography: Typography = <default>, content: @Composable () -> Unit)

object MaterialTheme {
    val colorScheme: ColorScheme   @Composable get
    val typography: Typography     @Composable get
    val shapes: Shapes             @Composable get
    val motionScheme: MotionScheme @Composable get
    val LocalMaterialTheme: CompositionLocal<MaterialTheme.Values>
}

fun expressiveLightColorScheme(): ColorScheme          // ColorSchemeKt
```

`MaterialExpressiveTheme` null handling *(inferred from `javap -c` of `MaterialThemeKt`)*: it reads a private
`LocalUsingExpressiveTheme`; if already inside an expressive theme, null arguments fall back to the current
`MaterialTheme.colorScheme / motionScheme / typography / shapes`; otherwise (outermost) the lambda calls
`expressiveLightColorScheme()`, `MotionScheme.expressive()`, `Shapes()` and `Typography()` for null arguments.
It then delegates to the 4-argument `MaterialTheme` and provides `LocalUsingExpressiveTheme = true`.

```kotlin
@Immutable interface MotionScheme {
    fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T>
    fun <T> fastSpatialSpec(): FiniteAnimationSpec<T>
    fun <T> slowSpatialSpec(): FiniteAnimationSpec<T>
    fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T>
    fun <T> fastEffectsSpec(): FiniteAnimationSpec<T>
    fun <T> slowEffectsSpec(): FiniteAnimationSpec<T>
    companion object {
        fun standard(): MotionScheme
        fun expressive(): MotionScheme
    }
}
```
(None of these six members is `@Composable`; they are called on `MaterialTheme.motionScheme`.)

Typography (class `Typography`) has 30 styles: the 15 baseline ones plus `displayLargeEmphasized` ... `labelSmallEmphasized`. Constructors:
```kotlin
Typography(displayLarge: TextStyle = <default>, ... labelSmall: TextStyle = <default>,
           displayLargeEmphasized: TextStyle = <default>, ... labelSmallEmphasized: TextStyle = <default>)
Typography(fontFamily: FontFamily, displayLarge: TextStyle? = <default>, ..., labelSmallEmphasized: TextStyle? = <default>)
```
plus `copy(...)` with all 30 parameters.

## 4. Loading and progress

```kotlin
// LoadingIndicatorKt  -- all @ExperimentalMaterial3ExpressiveApi
@Composable fun LoadingIndicator(                                  // determinate
    progress: () -> Float,
    modifier: Modifier = <default>,
    color: Color = <default>,
    polygons: List<RoundedPolygon> = <default>,
)
@Composable fun LoadingIndicator(                                  // indeterminate
    modifier: Modifier = <default>,
    color: Color = <default>,
    polygons: List<RoundedPolygon> = <default>,
)
@Composable fun ContainedLoadingIndicator(
    progress: () -> Float,
    modifier: Modifier = <default>,
    containerColor: Color = <default>,
    indicatorColor: Color = <default>,
    containerShape: Shape = <default>,
    polygons: List<RoundedPolygon> = <default>,
)
@Composable fun ContainedLoadingIndicator(
    modifier: Modifier = <default>,
    containerColor: Color = <default>,
    indicatorColor: Color = <default>,
    containerShape: Shape = <default>,
    polygons: List<RoundedPolygon> = <default>,
)

@ExperimentalMaterial3ExpressiveApi object LoadingIndicatorDefaults {
    val ContainerWidth: Dp; val ContainerHeight: Dp; val IndicatorSize: Dp
    val containerShape: Shape @Composable get
    val indicatorColor: Color @Composable get
    val containedIndicatorColor: Color @Composable get
    val containedContainerColor: Color @Composable get
    val IndeterminateIndicatorPolygons: List<RoundedPolygon>
    val DeterminateIndicatorPolygons: List<RoundedPolygon>
}

// pulltorefresh.PullToRefreshDefaults  (@ExperimentalMaterial3ExpressiveApi)
@Composable fun LoadingIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = <default>,
    containerColor: Color = <default>,
    color: Color = <default>,
    elevation: Dp = <default>,
    maxDistance: Dp = <default>,
)
```

```kotlin
// WavyProgressIndicatorKt  (no opt-in)
@Composable fun LinearWavyProgressIndicator(                       // determinate
    progress: () -> Float,
    modifier: Modifier = <default>,
    color: Color = <default>,
    trackColor: Color = <default>,
    stroke: Stroke = <default>,
    trackStroke: Stroke = <default>,
    gapSize: Dp = <default>,
    stopSize: Dp = <default>,
    amplitude: (Float) -> Float = <default>,      // (progress) -> amplitude fraction; param is @ParameterName-annotated
    wavelength: Dp = <default>,
    waveSpeed: Dp = <default>,
)
@Composable fun LinearWavyProgressIndicator(                       // indeterminate
    modifier: Modifier = <default>,
    color: Color = <default>,
    trackColor: Color = <default>,
    stroke: Stroke = <default>,
    trackStroke: Stroke = <default>,
    gapSize: Dp = <default>,
    amplitude: Float = <default>,
    wavelength: Dp = <default>,
    waveSpeed: Dp = <default>,
)
@Composable fun CircularWavyProgressIndicator(                     // determinate
    progress: () -> Float,
    modifier: Modifier = <default>,
    color: Color = <default>,
    trackColor: Color = <default>,
    stroke: Stroke = <default>,
    trackStroke: Stroke = <default>,
    gapSize: Dp = <default>,
    amplitude: (Float) -> Float = <default>,
    wavelength: Dp = <default>,
    waveSpeed: Dp = <default>,
)
@Composable fun CircularWavyProgressIndicator(                     // indeterminate
    modifier: Modifier = <default>,
    color: Color = <default>,
    trackColor: Color = <default>,
    stroke: Stroke = <default>,
    trackStroke: Stroke = <default>,
    gapSize: Dp = <default>,
    amplitude: Float = <default>,
    wavelength: Dp = <default>,
    waveSpeed: Dp = <default>,
)

object WavyProgressIndicatorDefaults {
    val ProgressAnimationSpec: AnimationSpec<Float>
    val indicatorColor: Color @Composable get; val trackColor: Color @Composable get
    val linearIndicatorStroke: Stroke @Composable get; val circularIndicatorStroke: Stroke @Composable get
    val linearTrackStroke: Stroke @Composable get; val circularTrackStroke: Stroke @Composable get
    val LinearDeterminateWavelength: Dp; val LinearIndeterminateWavelength: Dp
    val LinearContainerHeight: Dp; val LinearContainerWidth: Dp; val LinearTrackStopIndicatorSize: Dp
    val CircularContainerSize: Dp; val CircularWavelength: Dp
    val LinearIndicatorTrackGapSize: Dp; val CircularIndicatorTrackGapSize: Dp
    val indicatorAmplitude: (Float) -> Float
}
```
*(inferred)* `WavyProgressIndicatorDefaults.indicatorAmplitude` is the natural default for the determinate `amplitude` parameter (same type); not verified from bytecode.

## 5. Buttons: ButtonGroup, ToggleButton, SplitButton

```kotlin
// ButtonGroupKt -- an adaptive row with an overflow menu; content is a NON-composable DSL
@Composable fun ButtonGroup(
    overflowIndicator: @Composable (ButtonGroupMenuState) -> Unit,
    modifier: Modifier = <default>,
    expandedRatio: Float = <default>,
    horizontalArrangement: Arrangement.Horizontal = <default>,
    verticalAlignment: Alignment.Vertical = <default>,
    content: ButtonGroupScope.() -> Unit,
)
interface ButtonGroupScope {
    fun Modifier.weight(weight: Float): Modifier
    fun Modifier.animateWidth(interactionSource: InteractionSource): Modifier
    fun Modifier.animateWidth(interactionSource: InteractionSource, compressionLimit: Dp): Modifier
    fun Modifier.align(alignment: Alignment.Vertical): Modifier
    fun clickableItem(onClick: () -> Unit, label: String, icon: @Composable (() -> Unit)? = <default>,
                      weight: Float = <default>, enabled: Boolean = <default>)
    fun toggleableItem(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit,
                       icon: @Composable (() -> Unit)? = <default>, weight: Float = <default>, enabled: Boolean = <default>)
    fun customItem(buttonGroupContent: @Composable () -> Unit, menuContent: @Composable (ButtonGroupMenuState) -> Unit)
}
class ButtonGroupMenuState(initialIsShowing: Boolean = <default>) { var isShowing: Boolean; fun show(); fun dismiss() }

object ButtonGroupDefaults {
    val ExpandedRatio: Float
    val HorizontalArrangement: Arrangement.Horizontal
    val ConnectedSpaceBetween: Dp
    val connectedLeadingButtonShape: Shape @Composable get
    val connectedLeadingButtonPressShape: Shape @Composable get
    val connectedTrailingButtonShape: Shape @Composable get
    val connectedTrailingButtonPressShape: Shape @Composable get
    val connectedButtonCheckedShape: RoundedCornerShape
    val connectedMiddleButtonPressShape: Shape @Composable get
    @Composable fun connectedLeadingButtonShapes(shape: Shape = <default>, pressedShape: Shape = <default>, checkedShape: Shape = <default>): ToggleButtonShapes
    @Composable fun connectedMiddleButtonShapes(shape: Shape = <default>, pressedShape: Shape = <default>, checkedShape: Shape = <default>): ToggleButtonShapes
    @Composable fun connectedTrailingButtonShapes(shape: Shape = <default>, pressedShape: Shape = <default>, checkedShape: Shape = <default>): ToggleButtonShapes
    @Composable fun OverflowIndicator(menuState: ButtonGroupMenuState, modifier: Modifier = <default>, enabled: Boolean = <default>,
                                      shape: Shape = <default>, colors: IconButtonColors = <default>,
                                      interactionSource: MutableInteractionSource? = <default>)
}
```
A "connected button group" (single-select segmented replacement) is built manually: a `Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween))` of `ToggleButton`s using the three `connected*ButtonShapes()` (this is what OmniCal does, see omnical_patterns.md section 5).

```kotlin
// ToggleButtonKt -- four variants with identical parameter lists
@Composable fun ToggleButton(               // also: ElevatedToggleButton, FilledTonalToggleButton, OutlinedToggleButton
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = <default>,
    buttonSize: ToggleButtonSize = <default>,
    enabled: Boolean = <default>,
    icon: @Composable (() -> Unit)? = <default>,
    shapes: ToggleButtonShapes = <default>,
    colors: ToggleButtonColors = <default>,
    elevation: ToggleButtonElevation? = <default>,
    border: BorderStroke? = <default>,
    contentPadding: PaddingValues = <default>,
    interactionSource: MutableInteractionSource? = <default>,
    content: @Composable RowScope.() -> Unit,
)
@JvmInline value class ToggleButtonSize { val height: Dp
    companion object { val ExtraSmall; val Small; val Medium; val Large; val ExtraLarge } }
@Immutable class ToggleButtonShapes(shape: Shape, pressedShape: Shape, checkedShape: Shape) { fun copy(shape: Shape? = <default>, pressedShape: Shape? = <default>, checkedShape: Shape? = <default>) }

object ToggleButtonDefaults {   // ElevatedToggleButtonDefaults / FilledTonalToggleButtonDefaults / OutlinedToggleButtonDefaults mirror it
    val size: ToggleButtonSize; val MinHeight: Dp; val IconSpacing: Dp; val IconSize: Dp
    val shape: Shape @Composable get; val pressedShape: Shape @Composable get; val checkedShape: Shape @Composable get
    fun contentPaddingFor(buttonHeight: Dp, hasStartIcon: Boolean = <default>, hasEndIcon: Boolean = <default>): PaddingValues
    fun contentPaddingFor(buttonSize: ToggleButtonSize, hasStartIcon: Boolean = <default>, hasEndIcon: Boolean = <default>): PaddingValues
    @Composable fun shapesFor(buttonHeight: Dp): ToggleButtonShapes
    @Composable fun shapesFor(buttonSize: ToggleButtonSize): ToggleButtonShapes
    @Composable fun colors(): ToggleButtonColors
    @Composable fun colors(containerColor: Color = <default>, contentColor: Color = <default>, disabledContainerColor: Color = <default>,
                           disabledContentColor: Color = <default>, checkedContainerColor: Color = <default>, checkedContentColor: Color = <default>): ToggleButtonColors
    @Composable fun elevation(defaultElevation: Dp = <default>, pressedElevation: Dp = <default>, focusedElevation: Dp = <default>,
                              hoveredElevation: Dp = <default>, disabledElevation: Dp = <default>): ToggleButtonElevation
    // OutlinedToggleButtonDefaults additionally: @Composable fun border(enabled: Boolean, checked: Boolean): BorderStroke?
    // OutlinedToggleButtonDefaults has no elevation(); Elevated/FilledTonal have elevation()
}
```

Ordinary buttons gained shape morphing via `shapes`:
```kotlin
class ButtonShapes(shape: Shape, pressedShape: Shape)
ButtonDefaults: @Composable fun shapes(): ButtonShapes ; @Composable fun shapes(shape: Shape? = <default>, pressedShape: Shape? = <default>): ButtonShapes
                sizes: ExtraSmall/Medium/Large/ExtraLargeContainerHeight, matching *ContentPadding, *IconSize, *IconSpacing, *PressedShape
IconButtonDefaults: @Composable fun shapes(shape: Shape? = <default>, pressedShape: Shape? = <default>): IconButtonShapes ; shapes() ; toggleableShapes(...)
```

```kotlin
// SplitButtonKt -- there is NO `SplitButton(...)` composable in alpha29, only the layout + Defaults slots
@Composable fun SplitButtonLayout(
    leadingButton: @Composable () -> Unit,
    trailingButton: @Composable () -> Unit,
    modifier: Modifier = <default>,
    spacing: Dp = <default>,
)
class SplitButtonShapes(shape: Shape, pressedShape: Shape?, checkedShape: Shape?)

object SplitButtonDefaults {
    // constants: LeadingIconSize, TrailingIconSize, Spacing, {ExtraSmall..ExtraLarge}InnerCornerSize(+Pressed): CornerSize,
    // OuterCornerSize, {ExtraSmall..ExtraLarge}{Leading,Trailing}ButtonContentPadding, {..}TrailingButtonIconSize, {..}ContainerHeight
    @Composable fun leadingButtonShapesFor(buttonHeight: Dp): SplitButtonShapes
    @Composable fun trailingButtonShapesFor(buttonHeight: Dp): SplitButtonShapes
    fun leadingButtonContentPaddingFor(buttonHeight: Dp): PaddingValues
    fun trailingButtonContentPaddingFor(buttonHeight: Dp): PaddingValues
    fun leadingButtonIconSizeFor(buttonHeight: Dp): Dp
    fun trailingButtonIconSizeFor(buttonHeight: Dp): Dp
    @Composable fun LeadingButton(onClick: () -> Unit, modifier: Modifier = <default>, enabled: Boolean = <default>,
        shapes: SplitButtonShapes = <default>, colors: ButtonColors = <default>, elevation: ButtonElevation? = <default>,
        border: BorderStroke? = <default>, contentPadding: PaddingValues = <default>,
        interactionSource: MutableInteractionSource? = <default>, content: @Composable RowScope.() -> Unit)
    @Composable fun TrailingButton(onClick: () -> Unit, /* same tail as LeadingButton */)
    @Composable fun TrailingButton(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = <default>, /* same tail */)
    // Variants with the same shapes: TonalLeadingButton(onClick..), TonalTrailingButton(checked, onCheckedChange..),
    // OutlinedLeadingButton / OutlinedTrailingButton(checked..), ElevatedLeadingButton / ElevatedTrailingButton(checked..)
}
```
Usage shape *(inferred from the API)*: `SplitButtonLayout(leadingButton = { SplitButtonDefaults.LeadingButton(onClick) { ... } }, trailingButton = { SplitButtonDefaults.TrailingButton(checked = menuOpen, onCheckedChange = { menuOpen = it }) { Icon(...) } })`.

## 6. Floating toolbars

```kotlin
// FloatingToolbarKt
@Composable fun HorizontalFloatingToolbar(
    expanded: Boolean,
    modifier: Modifier = <default>,
    colors: FloatingToolbarColors = <default>,
    contentPadding: PaddingValues = <default>,
    scrollBehavior: FloatingToolbarScrollBehavior? = <default>,
    shape: Shape = <default>,
    leadingContent: @Composable (RowScope.() -> Unit)? = <default>,
    trailingContent: @Composable (RowScope.() -> Unit)? = <default>,
    expandedShadowElevation: Dp = <default>,
    collapsedShadowElevation: Dp = <default>,
    content: @Composable RowScope.() -> Unit,
)
@Composable fun HorizontalFloatingToolbar(                 // with FAB
    expanded: Boolean,
    floatingActionButton: @Composable () -> Unit,
    modifier: Modifier = <default>,
    colors: FloatingToolbarColors = <default>,
    contentPadding: PaddingValues = <default>,
    scrollBehavior: FloatingToolbarScrollBehavior? = <default>,
    shape: Shape = <default>,
    floatingActionButtonPosition: FloatingToolbarHorizontalFabPosition = <default>,
    animationSpec: FiniteAnimationSpec<Float> = <default>,
    expandedShadowElevation: Dp = <default>,
    collapsedShadowElevation: Dp = <default>,
    content: @Composable RowScope.() -> Unit,
)
// VerticalFloatingToolbar: same two overloads with ColumnScope lambdas and FloatingToolbarVerticalFabPosition
@Composable fun rememberFloatingToolbarState(initialOffsetLimit: Float = <default>, initialOffset: Float = <default>,
                                             initialContentOffset: Float = <default>): FloatingToolbarState

object FloatingToolbarDefaults {
    val ContainerSize: Dp; val ContainerExpandedElevation: Dp; val ContainerCollapsedElevation: Dp
    val ContainerExpandedElevationWithFab: Dp; val ContainerCollapsedElevationWithFab: Dp
    val ContainerShape: Shape @Composable get; val ContentPadding: PaddingValues
    val ScreenOffset: Dp; val ScrollDistanceThreshold: Dp
    @Composable fun <T> animationSpec(): FiniteAnimationSpec<T>
    @Composable fun exitAlwaysScrollBehavior(exitDirection: FloatingToolbarExitDirection, state: FloatingToolbarState = <default>,
        snapAnimationSpec: AnimationSpec<Float> = <default>, flingAnimationSpec: DecayAnimationSpec<Float> = <default>): FloatingToolbarScrollBehavior
    @Composable fun horizontalEnterTransition(expandFrom: Alignment.Horizontal): EnterTransition
    @Composable fun verticalEnterTransition(expandFrom: Alignment.Vertical): EnterTransition
    @Composable fun horizontalExitTransition(shrinkTowards: Alignment.Horizontal): ExitTransition
    @Composable fun verticalExitTransition(shrinkTowards: Alignment.Vertical): ExitTransition
    @Composable fun standardFloatingToolbarColors(): FloatingToolbarColors
    @Composable fun standardFloatingToolbarColors(toolbarContainerColor: Color = <default>, toolbarContentColor: Color = <default>,
        fabContainerColor: Color = <default>, fabContentColor: Color = <default>): FloatingToolbarColors
    @Composable fun vibrantFloatingToolbarColors() / vibrantFloatingToolbarColors(same 4 params): FloatingToolbarColors
    @Composable fun VibrantFloatingActionButton(onClick: () -> Unit, modifier: Modifier = <default>, shape: Shape = <default>,
        containerColor: Color = <default>, contentColor: Color = <default>, interactionSource: MutableInteractionSource? = <default>,
        content: @Composable () -> Unit)
    @Composable fun StandardFloatingActionButton(/* same params */)
    fun Modifier.floatingToolbarVerticalNestedScroll(expanded: Boolean, onExpand: () -> Unit, onCollapse: () -> Unit,
        expandScrollDistanceThreshold: Dp = <default>, collapseScrollDistanceThreshold: Dp = <default>,
        reverseLayout: Boolean = <default>): Modifier
}
@Immutable class FloatingToolbarColors(toolbarContainerColor: Color, toolbarContentColor: Color, fabContainerColor: Color, fabContentColor: Color)
@Stable interface FloatingToolbarScrollBehavior : NestedScrollConnection {
    val exitDirection: FloatingToolbarExitDirection; val state: FloatingToolbarState
    val snapAnimationSpec: AnimationSpec<Float>; val flingAnimationSpec: DecayAnimationSpec<Float>
    val floatingScrollBehaviorModifier: Modifier
    @Deprecated("Use floatingScrollBehaviorModifier instead.") fun Modifier.floatingScrollBehavior(): Modifier
}
interface FloatingToolbarState { var offsetLimit: Float; var offset: Float; var contentOffset: Float }
value class FloatingToolbarExitDirection { companion object { val Bottom; val Top; val Start; val End } }
value class FloatingToolbarHorizontalFabPosition { companion object { val Start; val End } }
value class FloatingToolbarVerticalFabPosition { companion object { val Top; val Bottom } }
```

## 7. Navigation: ShortNavigationBar, WideNavigationRail, FlexibleBottomAppBar

```kotlin
// ShortNavigationBarKt
@Composable fun ShortNavigationBar(
    modifier: Modifier = <default>,
    containerColor: Color = <default>,
    contentColor: Color = <default>,
    windowInsets: WindowInsets = <default>,
    arrangement: ShortNavigationBarArrangement = <default>,
    content: @Composable () -> Unit,
)
@Composable fun ShortNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: @Composable (() -> Unit)?,            // nullable but REQUIRED (no default)
    modifier: Modifier = <default>,
    enabled: Boolean = <default>,
    iconPosition: NavigationItemIconPosition = <default>,
    colors: NavigationItemColors = <default>,
    interactionSource: MutableInteractionSource? = <default>,
)
value class ShortNavigationBarArrangement { companion object { val EqualWeight; val Centered } }
value class NavigationItemIconPosition { companion object { val Top; val Start } }
object ShortNavigationBarDefaults { val containerColor: Color @Composable get; val contentColor: Color @Composable get
    val arrangement: ShortNavigationBarArrangement; val windowInsets: WindowInsets @Composable get }
object ShortNavigationBarItemDefaults { @Composable fun colors(): NavigationItemColors
    @Composable fun colors(selectedIconColor: Color = <default>, selectedTextColorTopIconPosition: Color = <default>,
        selectedTextColorStartIconPosition: Color = <default>, selectedIndicatorColor: Color = <default>,
        unselectedIconColor: Color = <default>, unselectedTextColor: Color = <default>,
        disabledIconColor: Color = <default>, disabledTextColor: Color = <default>): NavigationItemColors }
```

```kotlin
// WideNavigationRailKt
@Composable fun WideNavigationRail(
    modifier: Modifier = <default>,
    state: WideNavigationRailState = <default>,
    shape: Shape = <default>,
    colors: WideNavigationRailColors = <default>,
    header: @Composable (() -> Unit)? = <default>,
    windowInsets: WindowInsets = <default>,
    arrangement: Arrangement.Vertical = <default>,
    contentPadding: PaddingValues = <default>,
    content: @Composable () -> Unit,
)
@Composable fun ModalWideNavigationRail(
    modifier: Modifier = <default>,
    state: WideNavigationRailState = <default>,
    hideOnCollapse: Boolean = <default>,
    collapsedShape: Shape = <default>,
    expandedShape: Shape = <default>,
    colors: WideNavigationRailColors = <default>,
    header: @Composable (() -> Unit)? = <default>,
    expandedHeaderTopPadding: Dp = <default>,
    windowInsets: WindowInsets = <default>,
    arrangement: Arrangement.Vertical = <default>,
    expandedProperties: ModalWideNavigationRailProperties = <default>,
    contentPadding: PaddingValues = <default>,
    content: @Composable () -> Unit,
)
@Composable fun WideNavigationRailItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: @Composable (() -> Unit)?,            // required
    railExpanded: Boolean,                       // required
    modifier: Modifier = <default>,
    enabled: Boolean = <default>,
    iconPosition: NavigationItemIconPosition = <default>,
    colors: NavigationItemColors = <default>,
    interactionSource: MutableInteractionSource? = <default>,
    indicatorPadding: PaddingValues = <default>,
)
@Composable fun rememberWideNavigationRailState(initialValue: WideNavigationRailValue = <default>): WideNavigationRailState
interface WideNavigationRailState { val isAnimating: Boolean; val targetValue: WideNavigationRailValue; val currentValue: WideNavigationRailValue
    suspend fun expand(); suspend fun collapse(); suspend fun toggle(); suspend fun snapTo(targetValue: WideNavigationRailValue) }
enum class WideNavigationRailValue { Collapsed, Expanded }
class ModalWideNavigationRailProperties(securePolicy: SecureFlagPolicy = <default>, shouldDismissOnBackPress: Boolean = <default>)
object WideNavigationRailDefaults { val shape; val arrangement: Arrangement.Vertical; val windowInsets; val modalCollapsedShape
    val modalExpandedShape; val ModalExpandedProperties; val ContentPadding
    @Composable fun colors(containerColor: Color = <default>, contentColor: Color = <default>, modalContainerColor: Color = <default>,
        modalScrimColor: Color = <default>, modalContentColor: Color = <default>): WideNavigationRailColors }
object WideNavigationRailItemDefaults { val IndicatorCollapsedPadding: PaddingValues; val IndicatorExpandedPadding: PaddingValues
    @Composable fun indicatorPadding(railExpanded: Boolean, collapsedPadding: PaddingValues = <default>, expandedPadding: PaddingValues = <default>): PaddingValues
    fun iconPositionFor(railExpanded: Boolean): NavigationItemIconPosition
    @Composable fun colors(/* same 8 params as ShortNavigationBarItemDefaults.colors */): NavigationItemColors }
```
(For a phone-only app like Forge, `ShortNavigationBar` is the relevant one; WideNavigationRail targets medium/expanded widths.)

```kotlin
// AppBarKt
@Composable fun FlexibleBottomAppBar(
    modifier: Modifier = <default>,
    containerColor: Color = <default>,
    contentColor: Color = <default>,
    contentPadding: PaddingValues = <default>,
    horizontalArrangement: Arrangement.Horizontal = <default>,
    expandedHeight: Dp = <default>,
    windowInsets: WindowInsets = <default>,
    scrollBehavior: BottomAppBarScrollBehavior? = <default>,
    content: @Composable RowScope.() -> Unit,
)
// BottomAppBarDefaults (Expressive additions): FlexibleContentPadding, FlexibleBottomAppBarHeight,
//   FlexibleHorizontalArrangement, FlexibleFixedHorizontalArrangement, snapAnimationSpec/flingAnimationSpec (@Composable get),
//   @Composable fun exitAlwaysScrollBehavior(state: BottomAppBarState = <default>, canScroll: () -> Boolean = <default>,
//       snapAnimationSpec: AnimationSpec<Float>? = <default>, flingAnimationSpec: DecayAnimationSpec<Float>? = <default>): BottomAppBarScrollBehavior

@Composable fun LargeFlexibleTopAppBar(            // MediumFlexibleTopAppBar has the identical parameter list
    title: @Composable () -> Unit,
    modifier: Modifier = <default>,
    subtitle: @Composable (() -> Unit)? = <default>,
    navigationIcon: @Composable () -> Unit = <default>,
    actions: @Composable RowScope.() -> Unit = <default>,
    titleHorizontalAlignment: Alignment.Horizontal = <default>,
    collapsedHeight: Dp = <default>,
    expandedHeight: Dp = <default>,
    windowInsets: WindowInsets = <default>,
    colors: TopAppBarColors = <default>,
    scrollBehavior: TopAppBarScrollBehavior? = <default>,
)
```

## 8. Shapes and shape morphing

```kotlin
@ExperimentalMaterial3ExpressiveApi class MaterialShapes {
    companion object {   // each is androidx.graphics.shapes.RoundedPolygon (normalised to a unit square)
        val Circle; val Square; val Slanted; val Arch; val Fan; val Arrow; val SemiCircle; val Oval; val Pill
        val Triangle; val Diamond; val ClamShell; val Pentagon; val Gem; val Sunny; val VerySunny
        val Cookie4Sided; val Cookie6Sided; val Cookie7Sided; val Cookie9Sided; val Cookie12Sided
        val Ghostish; val Clover4Leaf; val Clover8Leaf; val Burst; val SoftBurst; val Boom; val SoftBoom
        val Flower; val Puffy; val PuffyDiamond; val PixelCircle; val PixelTriangle; val Bun; val Heart
    }
}   // 35 shapes

// MaterialShapesKt (all @ExperimentalMaterial3ExpressiveApi)
@Composable fun RoundedPolygon.toShape(startAngle: Int = <default>): Shape
@Composable fun RoundedPolygon.toPath(startAngle: Int = <default>): Path            // Compose Path
fun Morph.toPath(progress: Float, path: Path = <default>, startAngle: Int = <default>): Path   // NOT @Composable; reuse `path`
```
"Unit square" note is *(inferred)* from OmniCal scaling `Morph.toPath` output by the canvas size with a `Matrix`.

Shape-morph primitives come from `androidx.graphics:graphics-shapes:1.0.1` (transitive):
```kotlin
class Morph(start: RoundedPolygon, end: RoundedPolygon) {
    fun asCubics(progress: Float): List<Cubic>
    fun calculateBounds(bounds: FloatArray = <default>, approximate: Boolean = <default>): FloatArray
    fun calculateMaxBounds(bounds: FloatArray = <default>): FloatArray
    inline fun forEachCubic(progress: Float, mutableCubic: MutableCubic = <default>, callback: (MutableCubic) -> Unit)
}
fun RoundedPolygon(numVertices: Int, radius: Float = <default>, centerX: Float = <default>, centerY: Float = <default>,
                   rounding: CornerRounding = <default>, perVertexRounding: List<CornerRounding>? = <default>): RoundedPolygon
fun RoundedPolygon(vertices: FloatArray, rounding: CornerRounding = <default>, perVertexRounding: List<CornerRounding>? = <default>,
                   centerX: Float = <default>, centerY: Float = <default>): RoundedPolygon
fun RoundedPolygon.Companion.circle(...) / pill(...) / pillStar(...) / rectangle(...) / star(...)
// android-only helpers in graphics-shapes (android.graphics.Path, not Compose): Morph.toPath(progress, path), RoundedPolygon.toPath(path), RoundedPolygon.transformed(matrix)
```
Use the **material3** `toPath` (Compose `Path`, has `startAngle`) inside Compose `Canvas`; the graphics-shapes ones return `android.graphics.Path`.

Other morph-capable components (state-driven shape change built in): `ToggleButton*` (`ToggleButtonShapes`: shape / pressedShape / checkedShape), `Button*` (`ButtonShapes`), `IconButton*` (`IconButtonShapes`), `SplitButtonShapes`, `ListItemShapes` (`ListItemDefaults.shapes(shape, selectedShape, pressedShape, focusedShape, hoveredShape, draggedShape)` and `ListItemDefaults.segmentedShapes(index: Int, count: Int, defaultShapes: ListItemShapes = <default>)`), `ToggleFloatingActionButton` (corner radius as a function of progress).

## 9. FABs: FloatingActionButtonMenu, ToggleFloatingActionButton, extended FABs

```kotlin
// FloatingActionButtonMenuKt
@Composable fun FloatingActionButtonMenu(
    expanded: Boolean,
    button: @Composable () -> Unit,
    modifier: Modifier = <default>,
    horizontalAlignment: Alignment.Horizontal = <default>,
    content: @Composable FloatingActionButtonMenuScope.() -> Unit,
)
@Composable fun FloatingActionButtonMenuScope.FloatingActionButtonMenuItem(
    onClick: () -> Unit,
    text: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = <default>,
    containerColor: Color = <default>,
    contentColor: Color = <default>,
)
interface FloatingActionButtonMenuScope { val horizontalAlignment: Alignment.Horizontal }

@Composable fun ToggleFloatingActionButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = <default>,
    containerColor: (Float) -> Color = <default>,
    contentAlignment: Alignment = <default>,
    containerSize: (Float) -> Dp = <default>,
    containerCornerRadius: (Float) -> Dp = <default>,
    content: @Composable ToggleFloatingActionButtonScope.() -> Unit,
)
interface ToggleFloatingActionButtonScope { val checkedProgress: Float }
object ToggleFloatingActionButtonDefaults {
    @Composable fun containerColor(initialColor: Color = <default>, finalColor: Color = <default>): (Float) -> Color
    fun containerSize(initialSize: Dp, finalSize: Dp = <default>): (Float) -> Dp
    fun containerSize(): (Float) -> Dp; fun containerSizeMedium(): (Float) -> Dp; fun containerSizeLarge(): (Float) -> Dp
    fun containerCornerRadius(initialSize: Dp, finalSize: Dp = <default>): (Float) -> Dp
    fun containerCornerRadius() / containerCornerRadiusMedium() / containerCornerRadiusLarge(): (Float) -> Dp
    @Composable fun iconColor(initialColor: Color = <default>, finalColor: Color = <default>): (Float) -> Color
    fun iconSize(initialSize: Dp, finalSize: Dp = <default>): (Float) -> Dp
    fun iconSize() / iconSizeMedium() / iconSizeLarge(): (Float) -> Dp
    @Composable fun Modifier.animateIcon(checkedProgress: () -> Float, color: (Float) -> Color = <default>, size: (Float) -> Dp = <default>): Modifier
}
```
Typical wiring *(inferred from the types)*: `FloatingActionButtonMenu(expanded, button = { ToggleFloatingActionButton(checked = expanded, onCheckedChange = { expanded = it }) { Icon(..., Modifier.animateIcon({ checkedProgress })) } }) { FloatingActionButtonMenuItem(onClick, text = { Text("...") }, icon = { Icon(...) }) }`.

```kotlin
// FloatingActionButtonKt -- two shapes of API for extended FABs
@Composable fun ExtendedFloatingActionButton(                 // also Small/Medium/LargeExtendedFloatingActionButton
    text: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = <default>,
    expanded: Boolean = <default>,
    shape: Shape = <default>,
    containerColor: Color = <default>,
    contentColor: Color = <default>,
    elevation: FloatingActionButtonElevation = <default>,
    interactionSource: MutableInteractionSource? = <default>,
)
@Composable fun ExtendedFloatingActionButton(                 // slot overload; also Small/Medium/Large variants
    onClick: () -> Unit,
    modifier: Modifier = <default>,
    shape: Shape = <default>,
    containerColor: Color = <default>,
    contentColor: Color = <default>,
    elevation: FloatingActionButtonElevation = <default>,
    interactionSource: MutableInteractionSource? = <default>,
    content: @Composable RowScope.() -> Unit,
)
// FloatingActionButton / SmallFloatingActionButton / MediumFloatingActionButton / LargeFloatingActionButton:
//   (onClick, modifier, shape, containerColor, contentColor, elevation, interactionSource, content: @Composable () -> Unit)
fun Modifier.animateFloatingActionButton(
    visible: Boolean,
    alignment: Alignment,
    targetScale: Float = <default>,
    scaleAnimationSpec: AnimationSpec<Float>? = <default>,
    alphaAnimationSpec: AnimationSpec<Float>? = <default>,
): Modifier
```

## 10. Grouped (segmented) lists, used heavily by OmniCal

```kotlin
// ListItemKt -- four overloads; all share the tail below
@Composable fun SegmentedListItem(shapes: ListItemShapes, modifier: Modifier = <default>, enabled: Boolean = <default>, ...)       // static
@Composable fun SegmentedListItem(onClick: () -> Unit, shapes: ListItemShapes, ...)                                                // clickable
@Composable fun SegmentedListItem(selected: Boolean, onClick: () -> Unit, shapes: ListItemShapes, ...)                             // selectable
@Composable fun SegmentedListItem(checked: Boolean, onCheckedChange: (Boolean) -> Unit, shapes: ListItemShapes, ...)               // toggleable
//   tail: leadingContent, trailingContent, overlineContent, supportingContent: @Composable (() -> Unit)? = <default>,
//         verticalAlignment: Alignment.Vertical = <default>,
//         onLongClick: (() -> Unit)? = <default>, onLongClickLabel: String? = <default>   (interactive overloads only),
//         colors: ListItemColors = <default>, elevation: ListItemElevation = <default>, contentPadding: PaddingValues = <default>,
//         interactionSource: MutableInteractionSource? = <default> (interactive overloads only),
//         content: @Composable () -> Unit
ListItemDefaults: val SegmentedGap: Dp
                  @Composable fun segmentedShapes(index: Int, count: Int, defaultShapes: ListItemShapes = <default>): ListItemShapes
                  @Composable fun segmentedColors(containerColor: Color = <default>, contentColor, leading/trailing/overline/supportingContentColor,
                        disabled*, selected*, dragged* ... all = <default>): ListItemColors
                  @Composable fun shapes(shape, selectedShape, pressedShape, focusedShape, hoveredShape, draggedShape: Shape? = <default>): ListItemShapes
                  fun elevation(elevation: Dp = <default>, draggedElevation: Dp = <default>): ListItemElevation
```

## 11. Not found / caveats

- No `SplitButton(...)` composable; only `SplitButtonLayout` + `SplitButtonDefaults.*Button` slots.
- No `PolygonShape` / top-level `CornerRounding` in the material3 alpha29 jar (the beta01 release note mentions them; `CornerRounding` here is graphics-shapes').
- Default values are not in this document except the `MaterialExpressiveTheme` fallbacks read from bytecode. If an exact default matters, read the KDoc/source for 1.5.0-alpha29 or call the corresponding `XxxDefaults` member explicitly.
- Signatures are for **1.5.0-alpha29**. BOM 2026.11.00 (material3 1.5.0-rc01) was not re-extracted.
