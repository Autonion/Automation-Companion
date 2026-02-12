package com.autonion.automationcompanion.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.autonion.automationcompanion.features.PlaceholderScreen
import com.autonion.automationcompanion.features.gesture_recording_playback.GestureRecordingScreen
import com.autonion.automationcompanion.features.system_context_automation.SystemContextMainScreen


private const val ROUTE_HOME = "home"

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
    const val CROSS_DEVICE = "feature/cross_device_automation"
    const val PROFILE_LEARNING = "feature/automation_profile_learning"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ROUTE_HOME) {
        composable(ROUTE_HOME) {
            HomeScreen(onOpen = { route -> navController.navigate(route) })
        }

        composable(AutomationRoutes.GESTURE) {
            GestureRecordingScreen()
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
//            PlaceholderScreen(
//                title = "System Context Automation",
//                todos = listOf(
//                    "Location/time/battery/WIFI triggers",
//                    "Settings Panel & permission flows",
//                    "Fallbacks when services are disabled"
//                ),
//                onBack = {navController.popBackStack()}
//            )

            SystemContextMainScreen(
                onBack = {navController.popBackStack()},
//                onLocationAutomationClick = {
//                    navController.navigate(Routes.LOCATION_AUTOMATION)
//                }
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
            PlaceholderScreen(
                title = "Automation Debugger",
                todos = listOf(
                    "Step-through automation runs",
                    "Inspect runtime variables & logs",
                    "Save reproducable failing macros"
                ),
                onBack = {navController.popBackStack()}
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