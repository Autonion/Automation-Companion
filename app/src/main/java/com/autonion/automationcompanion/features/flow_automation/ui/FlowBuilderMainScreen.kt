package com.autonion.automationcompanion.features.flow_automation.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.autonion.automationcompanion.features.flow_automation.engine.FlowExecutionService
import com.autonion.automationcompanion.features.flow_automation.ui.editor.FlowEditorScreen
import com.autonion.automationcompanion.features.flow_automation.ui.editor.FlowEditorViewModel
import com.autonion.automationcompanion.features.flow_automation.ui.list.FlowListScreen
import com.autonion.automationcompanion.features.flow_automation.ui.list.FlowListViewModel

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
            remember(flowId) {
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
    val runIntent = Intent(context, com.autonion.automationcompanion.features.flow_automation.ui.FlowMediaProjectionActivity::class.java).apply {
        action = com.autonion.automationcompanion.features.flow_automation.ui.FlowMediaProjectionActivity.ACTION_RUN_FLOW
        putExtra(com.autonion.automationcompanion.features.flow_automation.ui.FlowMediaProjectionActivity.EXTRA_FLOW_ID, flowId)
    }
    context.startActivity(runIntent)
}
