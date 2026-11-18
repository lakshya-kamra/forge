package com.laksh.forge

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.laksh.forge.feature.onboarding.OnboardingFlow
import com.laksh.forge.ui.components.ExpressiveFab
import com.laksh.forge.ui.components.FloatingNavBar
import com.laksh.forge.ui.components.FloatingNavBarHeight
import com.laksh.forge.ui.components.FloatingNavItem
import com.laksh.forge.ui.components.LocalFloatingBarClearance
import com.laksh.forge.ui.components.LocalForgeSnackbar
import com.laksh.forge.ui.theme.ForgeTheme
import com.laksh.forge.ui.theme.LocalForgeSettings
import com.laksh.forge.ui.theme.appBackground

class MainActivity : ComponentActivity() {

    /** Holds the splash until settings are read, so the first frame is already themed. */
    @Volatile
    private var contentReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // The splash must be installed before super.onCreate swaps in the post-splash theme.
        val splash = installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        super.onCreate(savedInstanceState)
        // The floating bar draws its own backdrop; a system scrim behind 3-button navigation
        // would just be a grey band under it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        splash.setKeepOnScreenCondition { !contentReady }
        splash.setOnExitAnimationListener { provider -> playSplashExit(provider) }

        setContent {
            ForgeTheme {
                val settings = LocalForgeSettings.current
                if (settings != null) SideEffect { contentReady = true }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.appBackground)
                ) {
                    when {
                        settings == null -> Unit                       // splash still showing
                        !settings.onboarded -> OnboardingFlow(onDone = {})
                        else -> ForgeShell()
                    }
                }
            }
        }
    }

    /**
     * The icon dips, then swells and fades while the splash backdrop dissolves into the first
     * frame - the same anticipation-then-release shape as the Expressive springs in the app.
     * The system animator duration scale applies, so "remove animations" skips it.
     */
    private fun playSplashExit(provider: SplashScreenViewProvider) {
        val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)
        val backdrop = ObjectAnimator.ofFloat(provider.view, View.ALPHA, 1f, 0f).apply {
            startDelay = 140
            duration = 280
            interpolator = emphasized
        }
        val animators = mutableListOf<Animator>(backdrop)
        // Launches that did not come from the launcher can have no icon view; the platform
        // throws instead of returning null in that case.
        val icon = runCatching { provider.iconView }.getOrNull()
        if (icon != null) {
            animators += ObjectAnimator.ofPropertyValuesHolder(
                icon,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.86f, 1.45f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.86f, 1.45f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 1f, 0f)
            ).apply {
                duration = 420
                interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
            }
        }
        AnimatorSet().apply {
            playTogether(animators)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    provider.remove()
                }
            })
            start()
        }
    }
}

/** Top-level routes whose screens draw under the floating bar and pad their own lists. */
private val DrawsUnderBar = setOf(Routes.TODAY, Routes.TRENDS, Routes.SETTINGS)

private val BarBottomMargin = 12.dp

private fun navItemFor(route: String): FloatingNavItem = when (route) {
    Routes.TODAY -> FloatingNavItem("Today", Icons.Outlined.Today, Icons.Filled.Today)
    Routes.TRAIN -> FloatingNavItem("Train", Icons.Outlined.FitnessCenter, Icons.Filled.FitnessCenter)
    Routes.TRENDS -> FloatingNavItem("Trends", Icons.Outlined.Insights, Icons.Filled.Insights)
    Routes.COACH -> FloatingNavItem("Coach", Icons.Outlined.Psychology, Icons.Filled.Psychology)
    Routes.SETTINGS -> FloatingNavItem("Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
    // A tab added to Routes.TOP_LEVEL without an icon here still shows up, just plainly.
    else -> FloatingNavItem(route.replaceFirstChar { it.uppercase() }, Icons.Outlined.Circle, Icons.Outlined.Circle)
}

/**
 * The app shell: the nav graph, a floating pill bar with the log-food action for top-level
 * routes, and one snackbar host for the whole app. Detail routes get the full screen; the bar
 * slides away (a graphics-layer translation, never a height change, so nothing re-lays out).
 */
@Composable
private fun ForgeShell() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tabIndex = Routes.TOP_LEVEL.indexOf(route)
    val imeVisible = WindowInsets.isImeVisible
    val showBar = (route == null || tabIndex >= 0) && !imeVisible

    val navItems = remember { Routes.TOP_LEVEL.map(::navItemFor) }
    val snackbar = remember { SnackbarHostState() }

    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val clearance = FloatingNavBarHeight + BarBottomMargin + navInset
    val underBar = route in DrawsUnderBar

    // Screens that do not know about the floating bar get real padding so nothing hides
    // behind it; the padding switches once per navigation, never per frame.
    val bottomPadding: Dp = when {
        showBar && underBar -> 0.dp
        showBar -> clearance
        else -> navInset
    }
    val contentPadding = PaddingValues(top = statusInset, bottom = bottomPadding)

    CompositionLocalProvider(
        LocalForgeSnackbar provides snackbar,
        LocalFloatingBarClearance provides if (showBar && underBar) clearance else 0.dp
    ) {
        Box(Modifier.fillMaxSize()) {
            ForgeNavHost(
                nav = nav,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .consumeWindowInsets(contentPadding)
            )

            BottomChrome(
                visible = showBar,
                navInset = navInset,
                clearance = clearance,
                items = navItems,
                selectedIndex = tabIndex,
                onSelect = { index -> navigateToTab(nav, Routes.TOP_LEVEL[index]) },
                onLogFood = { nav.navigate(Routes.LOG) { launchSingleTop = true } }
            )

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 8.dp)
                    .padding(bottom = (if (showBar) clearance else navInset) + 8.dp)
            )
        }
    }
}

/** Tab switches keep one copy of each tab and restore its scroll state; Back returns to Today. */
private fun navigateToTab(nav: NavHostController, route: String) {
    if (nav.currentDestination?.route == route) return
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun BoxScope.BottomChrome(
    visible: Boolean,
    navInset: Dp,
    clearance: Dp,
    items: List<FloatingNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onLogFood: () -> Unit
) {
    val motion = MaterialTheme.motionScheme
    // Showing uses the default spatial spring (a small, friendly overshoot); hiding uses the
    // fast one so the bar is out of the way before the next screen settles.
    val hidden = animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = if (visible) motion.defaultSpatialSpec<Float>() else motion.fastSpatialSpec<Float>(),
        label = "barHide"
    )
    val backdrop = MaterialTheme.colorScheme.appBackground

    // Content scrolling under the bar fades out instead of meeting a hard edge.
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(clearance + 28.dp)
            .graphicsLayer { alpha = (1f - hidden.value).coerceIn(0f, 1f) }
            .background(
                Brush.verticalGradient(
                    listOf(backdrop.copy(alpha = 0f), backdrop.copy(alpha = 0.92f))
                )
            )
    )

    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = navInset + BarBottomMargin)
            .graphicsLayer {
                translationY = hidden.value * (size.height + (navInset + BarBottomMargin).toPx())
            }
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics { }),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FloatingNavBar(
            items = items,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            enabled = visible,
            modifier = Modifier.weight(1f)
        )
        ExpressiveFab(
            onClick = { if (visible) onLogFood() },
            icon = Icons.Rounded.Add,
            contentDescription = "Log food"
        )
    }
}
