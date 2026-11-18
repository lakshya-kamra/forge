package com.laksh.forge.ui.skeleton

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Perspective camera orbiting the lifter.
 *
 * Yaw is the camera's azimuth from the lifter's front (0 = facing them, -90 = their right
 * side, 180 = behind), pitch its elevation above the horizon, matching the per-exercise
 * default views in the catalog. Plain mutable floats rather than Compose state: the frame
 * loop and the gesture handler update it and then bump a single redraw tick, so nothing
 * recomposes while it moves.
 */
class OrbitCamera {

    var yaw = -60f
        private set
    var pitch = 12f
        private set
    var zoom = 1f
        private set

    private var yawVel = 0f
    private var pitchVel = 0f
    private var targetYaw = Float.NaN
    private var targetPitch = 0f
    private var targetZoom = 1f

    // Framing: the sphere that holds the lifter and equipment over the whole rep.
    private var cx = 0f
    private var cy = 0f
    private var cz = 0f
    private var radius = 1f

    // Derived each [update].
    var ex = 0f; private set
    var ey = 0f; private set
    var ez = 0f; private set
    var rx = 1f; private set
    var ry = 0f; private set
    var rz = 0f; private set
    var ux = 0f; private set
    var uy = 1f; private set
    var uz = 0f; private set
    var fx = 0f; private set
    var fy = 0f; private set
    var fz = -1f; private set
    var focal = 1f; private set
    var distance = 3f; private set
    private var scx = 0f
    private var scy = 0f
    private var maxZoom = 2.5f

    /** Key light fixed to the camera (up-left, toward the viewer), in world space after [update]. */
    val light = FloatArray(3)

    fun frame(centerX: Float, centerY: Float, centerZ: Float, sphereRadius: Float) {
        cx = centerX; cy = centerY; cz = centerZ
        radius = sphereRadius.coerceAtLeast(0.3f)
    }

    fun setView(yawDeg: Float, pitchDeg: Float, zoomLevel: Float = 1f) {
        yaw = wrap(yawDeg); pitch = pitchDeg.coerceIn(MIN_PITCH, MAX_PITCH); zoom = zoomLevel
        stop()
    }

    /** Eases to a view over a few hundred milliseconds (driven by [step]). */
    fun animateTo(yawDeg: Float, pitchDeg: Float, zoomLevel: Float = 1f) {
        yawVel = 0f; pitchVel = 0f
        targetYaw = wrap(yawDeg)
        targetPitch = pitchDeg.coerceIn(MIN_PITCH, MAX_PITCH)
        targetZoom = zoomLevel
    }

    fun stop() {
        yawVel = 0f; pitchVel = 0f; targetYaw = Float.NaN
    }

    /** Orbit by a finger movement of (dx, dy) px on a view of the given size. */
    fun orbitBy(dx: Float, dy: Float, width: Float, height: Float) {
        targetYaw = Float.NaN
        // Half a turn per screen width feels like turning the model with the finger.
        yaw = wrap(yaw - dx * 180f / width.coerceAtLeast(1f))
        pitch = (pitch + dy * 110f / height.coerceAtLeast(1f)).coerceIn(MIN_PITCH, MAX_PITCH)
    }

    fun zoomBy(factor: Float) {
        targetYaw = Float.NaN
        zoom = (zoom * factor).coerceIn(MIN_ZOOM, maxZoom)
    }

    /** Hand-off velocity in px/s when the finger lifts; [step] lets it decay. */
    fun fling(vx: Float, vy: Float, width: Float, height: Float) {
        targetYaw = Float.NaN
        yawVel = (-vx * 180f / width.coerceAtLeast(1f)).coerceIn(-MAX_SPIN, MAX_SPIN)
        pitchVel = (vy * 110f / height.coerceAtLeast(1f)).coerceIn(-MAX_SPIN, MAX_SPIN)
    }

    val isMoving: Boolean get() = !targetYaw.isNaN() || yawVel != 0f || pitchVel != 0f

    /** Advances easing and fling decay by [dt] seconds. */
    fun step(dt: Float) {
        if (dt <= 0f) return
        if (!targetYaw.isNaN()) {
            // Critically damped approach: fast start, no overshoot, frame-rate independent.
            val k = 1f - exp(-dt * 9f)
            var dYaw = targetYaw - yaw
            if (dYaw > 180f) dYaw -= 360f
            if (dYaw < -180f) dYaw += 360f
            yaw = wrap(yaw + dYaw * k)
            pitch += (targetPitch - pitch) * k
            zoom += (targetZoom - zoom) * k
            if (abs(dYaw) < 0.05f && abs(targetPitch - pitch) < 0.05f && abs(targetZoom - zoom) < 0.002f) {
                yaw = targetYaw; pitch = targetPitch; zoom = targetZoom
                targetYaw = Float.NaN
            }
            return
        }
        if (yawVel != 0f || pitchVel != 0f) {
            yaw = wrap(yaw + yawVel * dt)
            pitch = (pitch + pitchVel * dt).coerceIn(MIN_PITCH, MAX_PITCH)
            val decay = exp(-dt * FRICTION)
            yawVel *= decay
            pitchVel *= decay
            if (abs(yawVel) < 2f) yawVel = 0f
            if (abs(pitchVel) < 2f) pitchVel = 0f
        }
    }

    /** Recomputes the eye and basis for a viewport of [width] x [height] px. */
    fun update(width: Float, height: Float) {
        val tanY = tan(FOV_Y * 0.5f)
        val aspect = width / height.coerceAtLeast(1f)
        val tanFit = min(tanY, tanY * aspect)
        val sinFit = tanFit / sqrt(1f + tanFit * tanFit)
        val base = radius / sinFit
        // Never let the camera into the scene: zooming stops while everything stays in front.
        maxZoom = (base / (radius * 1.35f)).coerceAtLeast(MIN_ZOOM)
        if (zoom > maxZoom) zoom = maxZoom
        distance = base / zoom

        val p = pitch * DEG
        val y = yaw * DEG
        val ox = cos(p) * sin(y)
        val oy = sin(p)
        val oz = cos(p) * cos(y)
        ex = cx + ox * distance; ey = cy + oy * distance; ez = cz + oz * distance
        fx = -ox; fy = -oy; fz = -oz
        // right = forward x worldUp, up = right x forward
        var rxx = -fz
        var rzz = fx
        val rl = sqrt(rxx * rxx + rzz * rzz).coerceAtLeast(1e-6f)
        rxx /= rl; rzz /= rl
        rx = rxx; ry = 0f; rz = rzz
        ux = ry * fz - rz * fy
        uy = rz * fx - rx * fz
        uz = rx * fy - ry * fx

        focal = height * 0.5f / tanY
        scx = width * 0.5f
        scy = height * 0.5f

        // Light direction in camera space (right, up, toward-viewer), turned into world space.
        val lx = -0.45f; val ly = 0.75f; val lz = 0.48f
        light[0] = rx * lx + ux * ly - fx * lz
        light[1] = ry * lx + uy * ly - fy * lz
        light[2] = rz * lx + uz * ly - fz * lz
        val ll = sqrt(light[0] * light[0] + light[1] * light[1] + light[2] * light[2])
        light[0] /= ll; light[1] /= ll; light[2] /= ll
    }

    /** Camera-space depth of a world point (distance along the view axis). */
    fun depth(x: Float, y: Float, z: Float): Float = (x - ex) * fx + (y - ey) * fy + (z - ez) * fz

    /** Screen x of a world point whose depth is [zc] (from [depth]). */
    fun screenX(x: Float, y: Float, z: Float, zc: Float): Float =
        scx + focal * ((x - ex) * rx + (y - ey) * ry + (z - ez) * rz) / zc

    fun screenY(x: Float, y: Float, z: Float, zc: Float): Float =
        scy - focal * ((x - ex) * ux + (y - ey) * uy + (z - ez) * uz) / zc

    /** Projects into out[o] = screen x, out[o+1] = screen y, out[o+2] = depth. */
    fun project(x: Float, y: Float, z: Float, out: FloatArray, o: Int) {
        val zc = depth(x, y, z)
        out[o + 2] = zc
        if (zc < NEAR) {
            out[o] = scx; out[o + 1] = scy
            return
        }
        out[o] = screenX(x, y, z, zc)
        out[o + 1] = screenY(x, y, z, zc)
    }

    /** Depth of the scene centre, for fog. */
    fun centerDepth(): Float = depth(cx, cy, cz)

    fun sceneRadius(): Float = radius

    companion object {
        const val NEAR = 0.05f
        const val MIN_PITCH = -2f
        const val MAX_PITCH = 80f
        const val MIN_ZOOM = 0.6f
        private const val FOV_Y = (30.0 * Math.PI / 180.0).toFloat()
        private const val DEG = (Math.PI / 180.0).toFloat()
        private const val FRICTION = 3.2f
        private const val MAX_SPIN = 900f

        fun wrap(deg: Float): Float {
            var d = deg % 360f
            if (d > 180f) d -= 360f
            if (d <= -180f) d += 360f
            return d
        }
    }
}
