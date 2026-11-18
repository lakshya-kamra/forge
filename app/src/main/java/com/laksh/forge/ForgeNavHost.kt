package com.laksh.forge

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.laksh.forge.data.reminders.DeepLinks
import com.laksh.forge.feature.coach.CoachScreen
import com.laksh.forge.feature.exercise.ExerciseBrowserScreen
import com.laksh.forge.feature.exercise.ExerciseDetailScreen
import com.laksh.forge.feature.library.LibraryScreen
import com.laksh.forge.feature.library.ProgramDetailScreen
import com.laksh.forge.feature.log.LogScreen
import com.laksh.forge.feature.onboarding.OnboardingFlow
import com.laksh.forge.feature.plan.CheckinScreen
import com.laksh.forge.feature.plan.PlanScreen
import com.laksh.forge.feature.progress.ProgressScreen
import com.laksh.forge.feature.settings.AiSettingsScreen
import com.laksh.forge.feature.settings.AppearanceSettingsScreen
import com.laksh.forge.feature.settings.DataSettingsScreen
import com.laksh.forge.feature.settings.ProfileSettingsScreen
import com.laksh.forge.feature.settings.SettingsScreen
import com.laksh.forge.feature.today.TodayScreen
import com.laksh.forge.feature.tools.ToolsScreen
import com.laksh.forge.feature.train.SessionScreen
import com.laksh.forge.feature.train.TrainScreen
import com.laksh.forge.feature.trends.TrendsScreen
import com.laksh.forge.feature.wearables.WearablesScreen
import com.laksh.forge.ui.theme.ForgeNavTransitions
import com.laksh.forge.ui.theme.LocalReduceMotion

/** Every route in the app. Feature code navigates only through these builders. */
object Routes {
    const val TODAY = "today"
    const val TRAIN = "train"
    const val TRENDS = "trends"
    const val COACH = "coach"
    const val SETTINGS = "settings"

    const val LOG = "log"
    const val SESSION = "session/{sessionId}"
    const val ONBOARDING = "onboarding"
    const val LIBRARY = "library"
    const val PROGRAM = "library/{programId}"
    const val EXERCISES = "exercises"
    const val EXERCISE = "exercise/{exerciseKey}"
    const val WEARABLES = "wearables"
    const val PROGRESS = "progress"
    const val PLAN = "plan"
    const val CHECKIN = "checkin"
    const val TOOLS = "tools"
    const val SETTINGS_AI = "settings/ai"
    const val SETTINGS_PROFILE = "settings/profile"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_DATA = "settings/data"

    val TOP_LEVEL = listOf(TODAY, TRAIN, TRENDS, COACH, SETTINGS)

    fun session(id: Long) = "session/$id"
    fun program(id: String) = "library/${Uri.encode(id)}"
    /** [key] is a canonical exercise id or a free-text exercise name. */
    fun exercise(key: String) = "exercise/${Uri.encode(key)}"

    fun isTopLevel(route: String?) = route in TOP_LEVEL
}

/**
 * The navigation graph. The visual shell (bottom bar, transitions) lives in MainActivity;
 * this file only wires routes to screens so the two can change independently.
 */
@Composable
fun ForgeNavHost(nav: NavHostController, modifier: Modifier = Modifier) {
    val back: () -> Unit = { nav.popBackStack() }
    val openExercise: (String) -> Unit = { nav.navigate(Routes.exercise(it)) }

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
        composable(Routes.TODAY, deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.TODAY })) {
            TodayScreen(
                onAddFood = { nav.navigate(Routes.LOG) },
                onOpenEntry = { },
                onOpenPlan = { nav.navigate(Routes.PLAN) },
                onOpenProgress = { nav.navigate(Routes.PROGRESS) },
                onOpenWearables = { nav.navigate(Routes.WEARABLES) }
            )
        }
        composable(Routes.TRAIN, deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.TRAIN })) {
            TrainScreen(
                onOpenSession = { nav.navigate(Routes.session(it)) },
                onOpenLibrary = { nav.navigate(Routes.LIBRARY) },
                onOpenExercise = openExercise
            )
        }
        composable(Routes.TRENDS) { TrendsScreen() }
        composable(Routes.COACH) { CoachScreen() }
        composable(Routes.SETTINGS) { SettingsScreen(onNavigate = { nav.navigate(it) }) }

        composable(Routes.LOG, deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.LOG })) { LogScreen(onDone = back) }
        composable(
            Routes.SESSION,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
            deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.SESSION_PATTERN })
        ) { e ->
            SessionScreen(
                sessionId = e.arguments?.getLong("sessionId") ?: 0L,
                onExit = back,
                onOpenExercise = openExercise
            )
        }
        composable(Routes.ONBOARDING) {
            OnboardingFlow(onDone = back, onOpenProgram = { nav.navigate(Routes.program(it)) })
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(onBack = back, onOpenProgram = { nav.navigate(Routes.program(it)) })
        }
        composable(Routes.PROGRAM) { e ->
            ProgramDetailScreen(
                programId = Uri.decode(e.arguments?.getString("programId").orEmpty()),
                onBack = back,
                onAdopted = { nav.popBackStack(Routes.TRAIN, inclusive = false) },
                onOpenExercise = openExercise
            )
        }
        composable(Routes.EXERCISES) { ExerciseBrowserScreen(onBack = back, onOpen = openExercise) }
        composable(Routes.EXERCISE) { e ->
            ExerciseDetailScreen(
                exerciseKey = Uri.decode(e.arguments?.getString("exerciseKey").orEmpty()),
                onBack = back
            )
        }
        composable(Routes.WEARABLES) { WearablesScreen(onBack = back) }
        composable(Routes.PROGRESS) { ProgressScreen(onBack = back) }
        composable(Routes.PLAN) {
            PlanScreen(
                onBack = back,
                onCheckin = { nav.navigate(Routes.CHECKIN) },
                onReplan = { nav.navigate(Routes.ONBOARDING) },
                onOpenProgram = { nav.navigate(Routes.program(it)) }
            )
        }
        composable(Routes.CHECKIN) { CheckinScreen(onDone = back) }
        composable(Routes.TOOLS, deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.TOOLS })) { ToolsScreen(onBack = back) }
        composable(Routes.SETTINGS_AI) { AiSettingsScreen(onBack = back) }
        composable(Routes.SETTINGS_PROFILE) {
            ProfileSettingsScreen(onBack = back, onRedoOnboarding = { nav.navigate(Routes.ONBOARDING) })
        }
        composable(Routes.SETTINGS_APPEARANCE) { AppearanceSettingsScreen(onBack = back) }
        composable(Routes.SETTINGS_DATA, deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.SETTINGS_DATA })) { DataSettingsScreen(onBack = back) }
    }
}
