package com.autonion.automationcompanion.ui

import android.app.Activity
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.autonion.automationcompanion.features.PlaceholderScreen
import com.autonion.automationcompanion.features.automation_debugger.DebuggerViewModel
import com.autonion.automationcompanion.features.automation_debugger.ui.DebuggerScreen
import com.autonion.automationcompanion.features.automation_debugger.ui.LogDetailScreen
import com.autonion.automationcompanion.features.gesture_recording_playback.GestureRecordingScreen
import com.autonion.automationcompanion.features.system_context_automation.SystemContextMainScreen

private const val ROUTE_HOME = "home"
private const val NAV_ANIM_DURATION = 300

object AutomationRoutes {
    const val GESTURE = "feature/gesture_recording_playback"
    const val DYN_UI = "feature/dynamic_ui_path_recording"
    const val SCREEN_UNDERSTAND = "feature/screen_understanding_using_on_device_ml"
    const val SEMANTIC = "feature/semantic_automation"
    const val CONDITIONAL = "feature/conditional_macros"
    const val MULTI_APP = "feature/multi_app_workflow_pipeline"
    const val APP_SPECIFIC = "feature/app_specific_automation"
    const val SYSTEM_CONTEXT = "feature/system_context_automation"
    const val LOCATION_AUTOMATION = "feature/system_context/location"
    const val EMERGENCY = "feature/emergency_trigger"
    const val DEBUGGER = "feature/automation_debugger"
    const val DEBUGGER_DETAIL = "feature/automation_debugger/{category}"
    const val CROSS_DEVICE = "feature/cross_device_automation"
    const val PROFILE_LEARNING = "feature/automation_profile_learning"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntry?.destination?.route
    val view = LocalView.current
    val activity = view.context as? Activity
    val colorScheme = MaterialTheme.colorScheme
    val isDark = isSystemInDarkTheme()

    SideEffect {
        activity?.let { act ->
            val window = act.window
            if (currentRoute == ROUTE_HOME) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.statusBarColor = Color.Transparent.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
            } else {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                window.statusBarColor = colorScheme.primary.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = isDark
            }
        }
    }

    // Shared ViewModel for Debugger screens
    val debuggerViewModel: DebuggerViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = ROUTE_HOME,
        enterTransition = {
            slideInHorizontally(animationSpec = tween(NAV_ANIM_DURATION)) { fullWidth -> fullWidth } + fadeIn(animationSpec = tween(NAV_ANIM_DURATION))
        },
        exitTransition = {
            slideOutHorizontally(animationSpec = tween(NAV_ANIM_DURATION)) { fullWidth -> -fullWidth } + fadeOut(animationSpec = tween(NAV_ANIM_DURATION))
        },
        popEnterTransition = {
            slideInHorizontally(animationSpec = tween(NAV_ANIM_DURATION)) { fullWidth -> -fullWidth } + fadeIn(animationSpec = tween(NAV_ANIM_DURATION))
        },
        popExitTransition = {
            slideOutHorizontally(animationSpec = tween(NAV_ANIM_DURATION)) { fullWidth -> fullWidth } + fadeOut(animationSpec = tween(NAV_ANIM_DURATION))
        }
    ) {
        composable(ROUTE_HOME) {
            HomeScreen(onOpen = { route -> navController.navigate(route) })
        }

        composable(AutomationRoutes.GESTURE) {
            GestureRecordingScreen(onBack = { navController.popBackStack() })
        }

        composable(AutomationRoutes.DYN_UI) {
            PlaceholderScreen(
                title = "Dynamic UI Path Recording",
                todos = listOf(
                    "Record UI paths instead of absolute coordinates",
                    "Integrate with screen-understanding models",
                    "Create path-resilient replay logic"
                ),
                onBack = {navController.popBackStack()}
            )
        }

        composable(AutomationRoutes.SCREEN_UNDERSTAND) {
            val context = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.runtime.LaunchedEffect(Unit) {
               context.startActivity(android.content.Intent(context, com.autonion.automationcompanion.features.screen_understanding_ml.ui.PresetDashboardActivity::class.java))
               navController.popBackStack()
            }
        }

        composable(AutomationRoutes.SEMANTIC) {
            PlaceholderScreen(
                title = "Semantic Automation",
                todos = listOf(
                    "NLP intent parser (on-device or template-based)",
                    "Map natural language -> automation graph",
                    "Explain suggestion to user"
                ),
                onBack = {navController.popBackStack()}
            )
        }

        composable(AutomationRoutes.CONDITIONAL) {
            PlaceholderScreen(
                title = "Conditional Macros",
                todos = listOf(
                    "Condition DSL (time/location/state)",
                    "Evaluator & test harness",
                    "UI for editing conditions"
                ),
                onBack = {navController.popBackStack()}
            )
        }

        composable(AutomationRoutes.MULTI_APP) {
            PlaceholderScreen(
                title = "Multi-App Workflow Pipeline",
                todos = listOf(
                    "Cross-app sequencing & app-switching handling",
                    "State persistence between steps",
                    "Transactionality and rollback"
                ),
                onBack = {navController.popBackStack()}
            )
        }

        composable(AutomationRoutes.APP_SPECIFIC) {
            PlaceholderScreen(
                title = "System Context Automation",
                todos = listOf(
                    "Per-app actions & selectors",
                    "Test suites for popular apps",
                    "App capability registry"
                ),
                onBack = {navController.popBackStack()}
            )
        }

        composable(AutomationRoutes.SYSTEM_CONTEXT) {
            SystemContextMainScreen(
                onBack = {navController.popBackStack()},
            )
        }

        composable(AutomationRoutes.EMERGENCY) {
            PlaceholderScreen(
                title = "Emergency Trigger",
                todos = listOf(
                    "Define panic gestues/phrases",
                    "Emergency actions (logging/alerts)",
                    "Privacy & opt-in flow"
                ),
                onBack = {navController.popBackStack()}
            )
        }

        composable(AutomationRoutes.DEBUGGER) {
            DebuggerScreen(
                viewModel = debuggerViewModel,
                onBack = { navController.popBackStack() },
                onCategoryClick = { category ->
                    navController.navigate("feature/automation_debugger/$category")
                }
            )
        }

        composable(
            route = AutomationRoutes.DEBUGGER_DETAIL,
            arguments = listOf(navArgument("category") { type = NavType.StringType })
        ) { backStackEntry ->
            val category = backStackEntry.arguments?.getString("category") ?: return@composable
            LogDetailScreen(
                viewModel = debuggerViewModel,
                category = category,
                onBack = { navController.popBackStack() }
            )
        }

        composable(AutomationRoutes.CROSS_DEVICE) {
            com.autonion.automationcompanion.features.cross_device_automation.presentation.CrossDeviceAutomationScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(AutomationRoutes.PROFILE_LEARNING) {
            PlaceholderScreen(
                title = "Automation Profile Learning",
                todos = listOf(
                    "Collect anonymized local signals",
                    "Train on-device personalization model",
                    "Suggest automations based on history"
                ),
                onBack = {navController.popBackStack()}
            )
        }

    }

}

