package com.laksh.forge.ui.skeleton

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.laksh.forge.domain.exercise.ExerciseCatalog
import com.laksh.forge.domain.exercise.ExerciseMotion
import com.laksh.forge.domain.exercise.MotionPhase
import com.laksh.forge.domain.exercise.PhaseKind
import com.laksh.forge.domain.exercise.TempoKind
import com.laksh.forge.ui.components.ConnectedChoiceGroup
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.components.ShimmerBlock
import com.laksh.forge.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Camera presets offered as chips. [BEST] is the per-exercise default from the catalog. */
enum class ViewPreset(val label: String) {
    BEST("Best"), FRONT("Front"), SIDE("Side"), ANGLE("45°");

    fun yaw(m: ExerciseMotion): Float = when (this) {
        BEST -> m.viewYaw
        FRONT -> 0f
        SIDE -> -90f
        ANGLE -> -45f
    }

    fun pitch(m: ExerciseMotion): Float = when (this) {
        BEST -> m.viewPitch
        FRONT -> 10f
        SIDE -> 10f
        ANGLE -> 18f
    }
}

/**
 * Playback and camera state for one viewer.
 *
 * [time] and [tick] are only read inside draw lambdas, so a running animation re-draws the
 * canvas and the scrub bar without recomposing anything. The frame loop sleeps (no
 * Choreographer callbacks) whenever playback is paused and the camera is at rest.
 */
@Stable
class DemoController(val scene: SkeletonScene, autoPlay: Boolean) {
    val camera = OrbitCamera().apply { setView(scene.motion.viewYaw, scene.motion.viewPitch) }

    var playing by mutableStateOf(autoPlay)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var view by mutableStateOf<ViewPreset?>(ViewPreset.BEST)
        private set
    var interacted by mutableStateOf(false)
        private set

    /** Rep time, 0 until [SkeletonScene.period]. A paused demo rests on the most telling pose. */
    val time = mutableFloatStateOf(if (autoPlay) 0f else scene.motion.keyPoseT)
    val tick = mutableLongStateOf(0L)

    private val wake = MutableStateFlow(0L)
    private var scrubbing = false
    private var resumeAfterScrub = false

    val needsFrames: Boolean get() = (playing && !scrubbing) || camera.isMoving

    fun togglePlay() {
        playing = !playing
        poke()
    }

    fun toggleSpeed() {
        speed = if (speed == 1f) 0.5f else 1f
    }

    fun step(dt: Float) {
        if (playing && !scrubbing) {
            val p = scene.period
            var t = time.floatValue + dt * speed / scene.motion.durationS.coerceAtLeast(0.2f)
            if (t >= p) t -= p * floor(t / p)
            time.floatValue = t
        }
        if (camera.isMoving) {
            camera.step(dt)
            tick.longValue = tick.longValue + 1
        }
    }

    /** Suspends until something needs animating. */
    suspend fun awaitWork() {
        val seen = wake.value
        if (needsFrames) return
        wake.first { it != seen }
    }

    private fun poke() {
        wake.value = wake.value + 1
        tick.longValue = tick.longValue + 1
    }

    fun touchDown() {
        camera.stop()
    }

    fun orbit(dx: Float, dy: Float, width: Float, height: Float) {
        camera.orbitBy(dx, dy, width, height)
        view = null
        interacted = true
        tick.longValue = tick.longValue + 1
    }

    fun zoom(factor: Float) {
        camera.zoomBy(factor)
        interacted = true
        tick.longValue = tick.longValue + 1
    }

    fun fling(vx: Float, vy: Float, width: Float, height: Float) {
        camera.fling(vx, vy, width, height)
        poke()
    }

    fun select(preset: ViewPreset) {
        view = preset
        camera.animateTo(preset.yaw(scene.motion), preset.pitch(scene.motion), 1f)
        poke()
    }

    fun beginScrub() {
        if (scrubbing) return
        scrubbing = true
        resumeAfterScrub = playing
        playing = false
    }

    /** Jumps within the current rep; alternating lifts keep their side. */
    fun seek(fraction: Float) {
        val rep = floor(time.floatValue)
        time.floatValue = rep + fraction.coerceIn(0f, 0.9999f)
    }

    fun endScrub() {
        if (!scrubbing) return
        scrubbing = false
        if (resumeAfterScrub) {
            playing = true
            poke()
        }
    }
}

/**
 * The hero 3D demonstration: an orbitable skeleton with controls underneath. Building the
 * scene (a few hundred pose solves to size the camera) happens off the main thread; the
 * caller shows [fallback] if that fails.
 */
@Composable
fun ExerciseDemo(
    motion: ExerciseMotion,
    primaryMuscles: List<String>,
    secondaryMuscles: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 340.dp,
    fallback: @Composable (String) -> Unit
) {
    // The hero sits in a lazy list: scrolling it away disposes it, so the built scene (and its
    // controller, below) are cached outside composition and come back on the first frame.
    val built by produceState(DemoScenes.peek(motion)?.let { Result.success(it) }, motion) {
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                runCatching { DemoScenes.getOrBuild(motion) { SkeletonScene(motion, primaryMuscles, secondaryMuscles) } }
            }
        }
    }
    val result = built
    when {
        // Same total height as DemoContent (hero + controls), so nothing below jumps when it lands.
        result == null -> Column(modifier.fillMaxWidth()) {
            ShimmerBlock(
                Modifier
                    .fillMaxWidth()
                    .height(height),
                RoundedCornerShape(32.dp)
            )
            Spacer(Modifier.height(12.dp))
            ShimmerBlock(
                Modifier
                    .fillMaxWidth()
                    .height(DEMO_CONTROLS_HEIGHT),
                RoundedCornerShape(24.dp)
            )
        }
        result.isFailure -> fallback(result.exceptionOrNull()?.message ?: "The 3D demo could not be built.")
        else -> DemoContent(result.getOrThrow(), modifier, height)
    }
}

/** Approximate height of the play/scrub/speed row, phase legend and view choices. */
private val DEMO_CONTROLS_HEIGHT = 138.dp

/**
 * Built demo scenes and their controllers for the last few exercises opened, so returning to
 * the hero keeps its orbit, zoom, view and playback position instead of rebuilding.
 */
private object DemoScenes {
    private const val MAX = 4
    private class Entry(val scene: SkeletonScene) { var controller: DemoController? = null }
    private val cache = object : LinkedHashMap<ExerciseMotion, Entry>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ExerciseMotion, Entry>?): Boolean = size > MAX
    }

    fun peek(motion: ExerciseMotion): SkeletonScene? = synchronized(cache) { cache[motion]?.scene }

    fun getOrBuild(motion: ExerciseMotion, build: () -> SkeletonScene): SkeletonScene {
        peek(motion)?.let { return it }
        val scene = build()
        synchronized(cache) { cache.getOrPut(motion) { Entry(scene) } }
        return scene
    }

    fun controller(scene: SkeletonScene, create: () -> DemoController): DemoController = synchronized(cache) {
        val entry = cache.values.firstOrNull { it.scene === scene } ?: return@synchronized create()
        entry.controller ?: create().also { entry.controller = it }
    }
}

@Composable
private fun DemoContent(scene: SkeletonScene, modifier: Modifier, height: Dp) {
    val reduceMotion = LocalReduceMotion.current
    val ctrl = remember(scene) { DemoScenes.controller(scene) { DemoController(scene, autoPlay = !reduceMotion) } }
    val colors = rememberSkeletonColors()
    val haptics = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(ctrl, lifecycleOwner) {
        // Only animate while the screen is visible; the loop idles when nothing moves.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = -1L
            while (true) {
                if (!ctrl.needsFrames) {
                    last = -1L
                    ctrl.awaitWork()
                }
                withFrameNanos { now ->
                    val dt = if (last < 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                    last = now
                    ctrl.step(dt)
                }
            }
        }
    }

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(32.dp))
                .drawWithCache {
                    // Built once per size: a soft vertical wash behind the figure.
                    val brush = Brush.verticalGradient(listOf(colors.backgroundTop, colors.background))
                    onDrawBehind { drawRect(brush) }
                }
                .semantics { contentDescription = "3D demonstration. Drag to rotate, pinch to zoom, double-tap to reset the view." }
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { }
                    .pointerInput(ctrl) { orbitGestures(ctrl) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick) } }
            ) {
                // Subscribing to both states: time moves the body, tick moves the camera.
                ctrl.tick.longValue
                val t = ctrl.time.floatValue
                scene.pose(t)
                scene.draw(this, ctrl.camera, colors, scene.solver.clip.effortAt(t), detail = true)
            }

            PhaseBadge(scene, ctrl, Modifier.align(Alignment.TopStart).padding(14.dp))

            androidx.compose.animation.AnimatedVisibility(
                visible = !ctrl.interacted,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    "Drag to orbit · pinch to zoom · double-tap to reset",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    ctrl.togglePlay()
                },
                shapes = IconButtonDefaults.shapes()
            ) {
                Icon(
                    if (ctrl.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (ctrl.playing) "Pause" else "Play"
                )
            }
            Spacer(Modifier.width(10.dp))
            ScrubBar(scene, ctrl, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Pill(
                text = if (ctrl.speed == 1f) "1×" else "0.5×",
                icon = Icons.Rounded.Speed,
                filled = ctrl.speed != 1f,
                onClick = ctrl::toggleSpeed
            )
        }
        PhaseLegend(scene.motion, Modifier.padding(start = 4.dp, top = 6.dp))
        Spacer(Modifier.height(10.dp))
        ConnectedChoiceGroup<ViewPreset?>(
            options = ViewPreset.entries.toList(),
            selected = ctrl.view,
            onSelect = { p -> if (p != null) ctrl.select(p) },
            label = { it?.label.orEmpty() }
        )
    }
}

/**
 * Orbit, pinch and double-tap in one detector, so they never fight. A drag that starts
 * mostly vertical with one finger is left alone, so the page around the viewer still scrolls.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.orbitGestures(
    ctrl: DemoController,
    onReset: () -> Unit
) {
    val slop = viewConfiguration.touchSlop
    val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
    var lastTapUp = 0L
    var lastTapPos = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        ctrl.touchDown()
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var mode = 0 // 0 undecided, 1 orbiting, -1 handed to the parent scroll
        var travelX = 0f
        var travelY = 0f
        var pointers = 1
        var upTime = down.uptimeMillis
        while (true) {
            val event = awaitPointerEvent()
            var pressed = 0
            for (ch in event.changes) if (ch.pressed) pressed++
            if (pressed == 0) {
                upTime = event.changes.firstOrNull()?.uptimeMillis ?: upTime
                break
            }
            val pan = event.calculatePan()
            val zoom = event.calculateZoom()
            if (mode == 0) {
                travelX += pan.x
                travelY += pan.y
                when {
                    pressed >= 2 -> mode = 1
                    abs(travelX) > slop && abs(travelX) >= abs(travelY) -> mode = 1
                    abs(travelY) > slop -> mode = -1
                }
            }
            if (mode == 1) {
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                ctrl.orbit(pan.x, pan.y, w, h)
                if (zoom != 1f) ctrl.zoom(zoom)
                for (ch in event.changes) if (ch.positionChanged()) ch.consume()
                if (pressed != pointers) {
                    // A finger joined or left: the centroid jumps, so restart velocity.
                    tracker.resetTracking()
                    pointers = pressed
                }
                val first = event.changes.firstOrNull { it.pressed }
                if (first != null) tracker.addPosition(first.uptimeMillis, first.position)
            }
        }
        when (mode) {
            1 -> {
                val v = tracker.calculateVelocity()
                ctrl.fling(v.x, v.y, size.width.toFloat(), size.height.toFloat())
            }
            0 -> {
                val near = (down.position - lastTapPos).getDistance() < slop * 4f
                if (lastTapUp != 0L && upTime - lastTapUp < doubleTapMs && near) {
                    lastTapUp = 0L
                    ctrl.select(ViewPreset.BEST)
                    onReset()
                } else {
                    lastTapUp = upTime
                    lastTapPos = down.position
                }
            }
        }
    }
}

@Composable
private fun PhaseBadge(scene: SkeletonScene, ctrl: DemoController, modifier: Modifier) {
    val motion = scene.motion
    if (motion.phases.isEmpty()) return
    // Recomposes only when the phase changes, not every frame.
    val index by remember(ctrl) { derivedStateOf { scene.solver.clip.phaseIndexAt(ctrl.time.floatValue) } }
    val phase = motion.phases.getOrNull(index) ?: return
    val color = phaseColor(phase.kind)
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.85f)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                phaseLabel(motion, phase),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Rep timeline: phases as coloured segments, the played part brighter, a thumb at "now". */
@Composable
private fun ScrubBar(scene: SkeletonScene, ctrl: DemoController, modifier: Modifier) {
    val phases = scene.motion.phases
    val kinds = remember(phases) { phases.map { it.kind } }
    val colorsByKind = PhaseKind.entries.associateWith { phaseColor(it) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val thumb = MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .height(40.dp)
            .semantics { contentDescription = "Rep timeline. Drag to scrub." }
            .pointerInput(ctrl) {
                detectTapGestures(onTap = { o -> ctrl.seek(o.x / size.width.coerceAtLeast(1)) })
            }
            .pointerInput(ctrl) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        ctrl.beginScrub()
                        ctrl.seek(o.x / size.width.coerceAtLeast(1))
                    },
                    onDragEnd = { ctrl.endScrub() },
                    onDragCancel = { ctrl.endScrub() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        ctrl.seek(change.position.x / size.width.coerceAtLeast(1))
                    }
                )
            }
            .drawBehind {
                val barH = 8.dp.toPx()
                val top = (size.height - barH) / 2f
                val r = CornerRadius(barH / 2f)
                val t = ctrl.time.floatValue
                val now = t - floor(t)
                drawRoundRect(track, topLeft = Offset(0f, top), size = Size(size.width, barH), cornerRadius = r)
                val gap = 2.dp.toPx()
                for (i in phases.indices) {
                    val p = phases[i]
                    val x0 = p.start * size.width + if (i > 0) gap / 2f else 0f
                    val x1 = p.end * size.width - if (i < phases.lastIndex) gap / 2f else 0f
                    if (x1 <= x0) continue
                    val col = colorsByKind[kinds[i]] ?: thumb
                    drawRoundRect(col.copy(alpha = 0.3f), Offset(x0, top), Size(x1 - x0, barH), r)
                    val played = (now * size.width).coerceIn(x0, x1)
                    if (played > x0) drawRoundRect(col, Offset(x0, top), Size(played - x0, barH), r)
                }
                val tx = now * size.width
                drawCircle(thumb, radius = 7.dp.toPx(), center = Offset(tx, size.height / 2f))
            }
    )
}

@Composable
private fun PhaseLegend(motion: ExerciseMotion, modifier: Modifier) {
    if (motion.phases.isEmpty() && motion.tempoNote.isBlank()) return
    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            motion.phases.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(phaseColor(p.kind))
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        phaseLabel(motion, p) + secondsLabel(p.seconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (motion.tempoNote.isNotBlank()) {
            Text(
                motion.tempoNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

private fun phaseLabel(m: ExerciseMotion, p: MotionPhase): String = when (p.kind) {
    PhaseKind.CONCENTRIC -> "Concentric"
    PhaseKind.ECCENTRIC -> "Eccentric"
    PhaseKind.HOLD -> if (m.tempoKind == TempoKind.ISOMETRIC) "Hold" else "Pause"
    PhaseKind.MOVE -> if (m.tempoKind == TempoKind.GAIT) "Step" else "Move"
}

private fun secondsLabel(s: Double): String {
    if (s <= 0.0) return ""
    val tenths = (s * 10).roundToInt()
    return if (tenths % 10 == 0) " ${tenths / 10} s" else " ${tenths / 10}.${tenths % 10} s"
}

@Composable
private fun phaseColor(kind: PhaseKind): Color = when (kind) {
    PhaseKind.CONCENTRIC -> MaterialTheme.colorScheme.primary
    PhaseKind.ECCENTRIC -> MaterialTheme.colorScheme.secondary
    PhaseKind.HOLD -> MaterialTheme.colorScheme.tertiary
    PhaseKind.MOVE -> MaterialTheme.colorScheme.outline
}

// ---------------------------------------------------------------------- thumbnails

/**
 * Scenes for list thumbnails, kept so scrolling back up does not rebuild them. Access-ordered
 * map: the least recently shown preview is dropped first.
 */
private object ThumbnailScenes {
    // Larger than the catalog (62 motions): scenes are small, and evicting them made every row
    // re-solve its pose when scrolled back into view.
    private const val MAX = 96
    private val cache = object : LinkedHashMap<String, SkeletonScene>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SkeletonScene>?): Boolean = size > MAX
    }

    fun peek(id: String): SkeletonScene? = synchronized(cache) { cache[id] }

    fun get(id: String, primary: List<String>): SkeletonScene? {
        synchronized(cache) { cache[id]?.let { return it } }
        val motion = ExerciseCatalog.motion(id) ?: return null
        val scene = runCatching { SkeletonScene(motion, primary, emptyList()) }.getOrNull() ?: return null
        synchronized(cache) { cache[id] = scene }
        return scene
    }
}

/** Margin around the fitted figure, as a fraction of the tile's shorter side. */
private const val ThumbnailPadding = 0.08f

/**
 * A still of the exercise's key pose, for lists. Drawn once: nothing it reads changes, so
 * Compose replays the recorded drawing while the list scrolls.
 */
@Composable
fun SkeletonThumbnail(
    exerciseId: String,
    primaryMuscles: List<String>,
    modifier: Modifier = Modifier
) {
    val colors = rememberSkeletonColors()
    // A cache hit draws on the first frame; only a miss goes to Default.
    val scene by produceState(ThumbnailScenes.peek(exerciseId), exerciseId) {
        if (value == null) value = withContext(Dispatchers.Default) { ThumbnailScenes.get(exerciseId, primaryMuscles) }
    }
    Box(
        modifier.drawWithCache {
            val brush = Brush.verticalGradient(listOf(colors.backgroundTop, colors.background))
            onDrawBehind { drawRect(brush) }
        }
    ) {
        val s = scene
        if (s != null) {
            val cam = remember(s) { OrbitCamera().apply { setView(s.motion.viewYaw, s.motion.viewPitch) } }
            val box = remember { FloatArray(4) }
            Canvas(Modifier.fillMaxSize()) {
                s.pose(s.motion.keyPoseT)
                if (!s.projectedBounds(cam, size.width, size.height, box)) {
                    s.draw(this, cam, colors, effort = 0.8f, detail = false)
                    return@Canvas
                }
                // Fit the key pose and its equipment, centred with a margin: the orbit framing
                // is built for every yaw over the whole rep, not for one still in a small tile.
                val pad = size.minDimension * ThumbnailPadding
                val bw = box[2] - box[0]
                val bh = box[3] - box[1]
                val fit = min((size.width - 2f * pad) / bw, (size.height - 2f * pad) / bh)
                    .coerceIn(0.2f, 4f)
                val dx = size.width * 0.5f - (box[0] + box[2]) * 0.5f
                val dy = size.height * 0.5f - (box[1] + box[3]) * 0.5f
                withTransform({
                    scale(fit, fit, pivot = center)
                    translate(dx, dy)
                }) {
                    s.draw(this, cam, colors, effort = 0.8f, detail = false)
                }
            }
        }
    }
}
