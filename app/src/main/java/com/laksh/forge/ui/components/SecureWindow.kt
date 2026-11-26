package com.laksh.forge.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Sets FLAG_SECURE on the window while this is in composition, so body photos on screen are
 * left out of Recents snapshots, screenshots, screen recording and casting. Reference-counted
 * per window, so nested users (the onboarding photo step opening the pose camera) don't clear
 * the flag while an outer one is still showing.
 */
@Composable
fun SecureWindow() {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            val count = (holders[window] ?: 0) + 1
            holders[window] = count
            if (count == 1) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (window != null) {
                val left = (holders[window] ?: 1) - 1
                if (left <= 0) {
                    holders.remove(window)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    holders[window] = left
                }
            }
        }
    }
}

private val holders = java.util.WeakHashMap<android.view.Window, Int>()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
