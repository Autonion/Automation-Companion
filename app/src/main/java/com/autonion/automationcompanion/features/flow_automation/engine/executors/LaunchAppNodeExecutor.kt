package com.autonion.automationcompanion.features.flow_automation.engine.executors

import android.content.Context
import android.content.Intent
import android.util.Log
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.flow_automation.engine.NodeExecutor
import com.autonion.automationcompanion.features.flow_automation.engine.NodeResult
import com.autonion.automationcompanion.features.flow_automation.model.FlowContext
import com.autonion.automationcompanion.features.flow_automation.model.FlowNode
import com.autonion.automationcompanion.features.flow_automation.model.LaunchAppNode
import kotlinx.coroutines.delay

private const val TAG = "LaunchAppNodeExecutor"

/**
 * Executor for [LaunchAppNode]. Launches a specified app mid-flow.
 *
 * This works identically to StartNodeExecutor's app-launch logic but is
 * designed to appear anywhere in the graph, not just at the beginning.
 */
class LaunchAppNodeExecutor(private val appContext: Context) : NodeExecutor {

    override suspend fun execute(node: FlowNode, context: FlowContext): NodeResult {
        val launchNode = node as? LaunchAppNode
            ?: return NodeResult.Failure("Expected LaunchAppNode but got ${node::class.simpleName}")

        val packageName = launchNode.appPackageName
        if (packageName.isBlank()) {
            Log.e(TAG, "No target app configured")
            DebugLogger.error(appContext, LogCategory.FLOW_BUILDER, "Launch App Failed", "No target app selected — configure this node first", TAG)
            return NodeResult.Failure("No target app selected — configure this node first")
        }

        return try {
            val pm = appContext.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
                ?: return NodeResult.Failure("Cannot find launch intent for $packageName")

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(launchIntent)
            Log.d(TAG, "Launched app: $packageName")
            DebugLogger.success(appContext, LogCategory.FLOW_BUILDER, "App Launched", "Launched app: $packageName", TAG)

            // Wait for the app to come to foreground
            delay(launchNode.launchDelayMs)
            NodeResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app: ${e.message}")
            DebugLogger.error(appContext, LogCategory.FLOW_BUILDER, "App Launch Failed", "Failed to launch $packageName: ${e.message}", TAG)
            NodeResult.Failure("Failed to launch $packageName: ${e.message}")
        }
    }
}
