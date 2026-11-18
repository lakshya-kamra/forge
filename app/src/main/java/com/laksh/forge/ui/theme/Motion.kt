package com.laksh.forge.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import com.laksh.forge.Routes

/**
 * Easing tokens. Component motion uses `MaterialTheme.motionScheme` springs; these curves are
 * only for screen transitions, which are tweens on purpose (see [ForgeNavTransitions]).
 * Values from docs/research/design.md section 2.2 and 3.3.
 */
object ForgeEasing {
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** PixelPlayer's push curve: fast out of the gate, long settle. */
    val Push: Easing = CubicBezierEasing(0.2f, 0.85f, 0.7f, 1f)
}

/**
 * NavHost transitions, PixelPlayer style (design.md 3.3):
 * - push: new page slides in from half-width while scaling up from 0.92, the old page drifts
 *   a quarter-width left (parallax);
 * - pop: the reverse, so the gesture direction always matches the motion;
 * - between top-level tabs: a directional slide by tab order.
 *
 * Tweens, not springs: predictive back scrubs a transition by seeking it, and a tween's
 * timeline maps 1:1 onto the back gesture's progress where a spring's does not.
 *
 * With reduce motion every transition collapses to a short cross-fade.
 */
object ForgeNavTransitions {
    private const val PUSH_MS = 450
    private const val FADE_MS = 225
    private const val TAB_MS = 380
    private const val TAB_FADE_MS = 190
    private const val REDUCED_MS = 150

    private fun tabIndex(entry: NavBackStackEntry): Int =
        Routes.TOP_LEVEL.indexOf(entry.destination.route)

    /** +1 when moving to a tab further right, -1 left, 0 when either side is not a tab. */
    private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabDirection(): Int {
        val from = tabIndex(initialState)
        val to = tabIndex(targetState)
        return if (from < 0 || to < 0 || from == to) 0 else if (to > from) 1 else -1
    }

    private fun tabEnter(dir: Int): EnterTransition =
        slideInHorizontally(tween(TAB_MS, easing = ForgeEasing.Emphasized)) { (it / 2) * dir } +
            fadeIn(tween(TAB_FADE_MS))

    private fun tabExit(dir: Int): ExitTransition =
        slideOutHorizontally(tween(TAB_MS, easing = ForgeEasing.Emphasized)) { -(it / 2) * dir } +
            fadeOut(tween(TAB_FADE_MS))

    fun enter(reduceMotion: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        val dir = tabDirection()
        when {
            reduceMotion -> fadeIn(tween(REDUCED_MS))
            dir != 0 -> tabEnter(dir)
            else -> slideInHorizontally(tween(PUSH_MS, easing = ForgeEasing.Push)) { (it * 0.5f).toInt() } +
                scaleIn(tween(PUSH_MS, easing = ForgeEasing.Push), initialScale = 0.92f) +
                // The accelerate curve keeps alpha low until the slide has mostly landed, so
                // the page never appears before it arrives.
                fadeIn(tween(PUSH_MS, easing = ForgeEasing.EmphasizedAccelerate))
        }
    }

    fun exit(reduceMotion: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        val dir = tabDirection()
        when {
            reduceMotion -> fadeOut(tween(REDUCED_MS))
            dir != 0 -> tabExit(dir)
            else -> slideOutHorizontally(tween(PUSH_MS, easing = ForgeEasing.Push)) { -(it * 0.25f).toInt() } +
                fadeOut(tween(FADE_MS))
        }
    }

    fun popEnter(reduceMotion: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        val dir = tabDirection()
        when {
            reduceMotion -> fadeIn(tween(REDUCED_MS))
            dir != 0 -> tabEnter(dir)
            else -> slideInHorizontally(tween(PUSH_MS, easing = ForgeEasing.Push)) { -(it * 0.25f).toInt() } +
                scaleIn(tween(PUSH_MS, easing = ForgeEasing.Push), initialScale = 0.95f) +
                fadeIn(tween(FADE_MS))
        }
    }

    fun popExit(reduceMotion: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        val dir = tabDirection()
        when {
            reduceMotion -> fadeOut(tween(REDUCED_MS))
            dir != 0 -> tabExit(dir)
            else -> slideOutHorizontally(tween(PUSH_MS, easing = ForgeEasing.Push)) { (it * 0.5f).toInt() } +
                scaleOut(tween(PUSH_MS, easing = ForgeEasing.Push), targetScale = 0.92f) +
                fadeOut(tween(FADE_MS))
        }
    }
}
