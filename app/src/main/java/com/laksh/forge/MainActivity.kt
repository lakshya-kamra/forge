package com.laksh.forge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.laksh.forge.feature.coach.CoachScreen
import com.laksh.forge.feature.log.LogScreen
import com.laksh.forge.feature.settings.SettingsScreen
import com.laksh.forge.feature.today.TodayScreen
import com.laksh.forge.feature.train.SessionScreen
import com.laksh.forge.feature.train.TrainScreen
import com.laksh.forge.feature.trends.TrendsScreen
import com.laksh.forge.ui.OnboardingScreen
import com.laksh.forge.ui.theme.ForgeTheme

private enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    TODAY("today", "Today", Icons.Outlined.Today),
    TRAIN("train", "Train", Icons.Outlined.FitnessCenter),
    TRENDS("trends", "Trends", Icons.Outlined.Insights),
    COACH("coach", "Coach", Icons.Outlined.Psychology),
    SETTINGS("settings", "Settings", Icons.Outlined.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ForgeTheme {
                val settings by ForgeApp.instance.repository.settingsStore.settings
                    .collectAsStateWithLifecycle(initialValue = null)

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    when {
                        settings == null -> Unit                       // first frame, avoid a flash
                        !settings!!.onboarded -> OnboardingScreen()
                        else -> ForgeRoot()
                    }
                }
            }
        }
    }
}

@Composable
private fun ForgeRoot() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val isFullScreen = currentRoute == "log" || currentRoute?.startsWith("session/") == true

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AnimatedVisibility(
                visible = !isFullScreen,
                enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
                exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180))
            ) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp
                ) {
                    Dest.entries.forEach { dest ->
                        val selected = backStack?.destination?.hierarchy?.any {
                            it.route == dest.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, dest.label) },
                            label = { Text(dest.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = currentRoute == Dest.TODAY.route,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(150))
            ) {
                FloatingActionButton(
                    onClick = { navController.navigate("log") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Filled.Add, "Log food")
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.TODAY.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            composable(Dest.TODAY.route) {
                TodayScreen(
                    onAddFood = { navController.navigate("log") },
                    onOpenEntry = { }
                )
            }
            composable(Dest.TRAIN.route) {
                TrainScreen(onOpenSession = { id -> navController.navigate("session/$id") })
            }
            composable(Dest.TRENDS.route) { TrendsScreen() }
            composable(Dest.COACH.route) { CoachScreen() }
            composable(Dest.SETTINGS.route) { SettingsScreen() }

            composable("log") {
                LogScreen(onDone = { navController.popBackStack() })
            }
            composable(
                route = "session/{sessionId}",
                arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("sessionId") ?: 0L
                SessionScreen(sessionId = id, onExit = { navController.popBackStack() })
            }
        }
    }
}
