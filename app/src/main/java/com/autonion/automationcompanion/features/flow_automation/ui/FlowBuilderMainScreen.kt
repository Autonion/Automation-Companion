package com.autonion.automationcompanion.features.flow_automation.ui

import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.autonion.automationcompanion.core.onboarding.OnboardingPreferences
import com.autonion.automationcompanion.features.flow_automation.engine.FlowExecutionService
import com.autonion.automationcompanion.features.flow_automation.ui.editor.FlowEditorScreen
import com.autonion.automationcompanion.features.flow_automation.ui.editor.FlowEditorViewModel
import com.autonion.automationcompanion.features.flow_automation.ui.list.FlowListScreen
import com.autonion.automationcompanion.features.flow_automation.ui.list.FlowListViewModel
import com.autonion.automationcompanion.ui.components.FeatureTipSheet

/**
 * Main entry composable for the Flow Builder feature.
 * Manages internal navigation between the list and editor screens.
 */
@Composable
fun FlowBuilderMainScreen(
    onBack: () -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // ── First-visit Feature Tip ──
    val onboardingPrefs = remember { OnboardingPreferences.getInstance(context) }
    var showTip by remember { mutableStateOf(!onboardingPrefs.hasTipBeenSeen("flow_builder")) }

    if (showTip) {
        FeatureTipSheet(
            title = "Flow Builder",
            tips = listOf(
                "Tap **+** to add action nodes to your automation flow",
                "Connect nodes by tapping an **output port** then the target node",
                "**Long-press** a node for options like duplicate, delete, or configure"
            ),
            icon = Icons.Default.AccountTree,
            iconColor = Color(0xFF448AFF),
            youtubeLink = null,
            onDismiss = { onboardingPrefs.markTipSeen("flow_builder"); showTip = false }
        )
    }

    NavHost(
        navController = navController,
        startDestination = "flow_list"
    ) {
        composable("flow_list") {
            val listViewModel: FlowListViewModel = viewModel()
            FlowListScreen(
                viewModel = listViewModel,
                onCreateNew = { navController.navigate("flow_editor/new") },
                onEditFlow = { flowId -> navController.navigate("flow_editor/$flowId") },
                onRunFlow = { flowId -> startFlowService(context, flowId) },
                onBack = onBack
            )
        }

        composable("flow_editor/{flowId}") { backStackEntry ->
            val flowId = backStackEntry.arguments?.getString("flowId") ?: "new"
            val editorViewModel: FlowEditorViewModel = viewModel()

            // Load or create
            LaunchedEffect(flowId) {
                if (flowId == "new") {
                    editorViewModel.createNewFlow("Untitled Flow")
                } else {
                    editorViewModel.loadFlow(flowId)
                }
            }

            FlowEditorScreen(
                viewModel = editorViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

private fun startFlowService(context: Context, flowId: String) {
    // Load the flow to check if it needs MediaProjection
    val repository = com.autonion.automationcompanion.features.flow_automation.data.FlowRepository(context)
    val graph = repository.load(flowId)
    
    val needsMediaProjection = graph?.nodes?.any {
        it is com.autonion.automationcompanion.features.flow_automation.model.VisualTriggerNode ||
        it is com.autonion.automationcompanion.features.flow_automation.model.ScreenMLNode
    } ?: false

    if (needsMediaProjection) {
        // Needs screen capture → go through FlowMediaProjectionActivity for permission
        val runIntent = Intent(context, FlowMediaProjectionActivity::class.java).apply {
            action = FlowMediaProjectionActivity.ACTION_RUN_FLOW
            putExtra(FlowMediaProjectionActivity.EXTRA_FLOW_ID, flowId)
        }
        context.startActivity(runIntent)
    } else {
        // No screen capture needed → start service directly
        val serviceIntent = FlowExecutionService.createIntent(context = context, flowId = flowId)
        androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
    }
}
