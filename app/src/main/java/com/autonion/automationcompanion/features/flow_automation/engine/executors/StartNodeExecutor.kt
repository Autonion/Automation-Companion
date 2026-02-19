package com.autonion.automationcompanion.features.flow_automation.engine.executors

import android.content.Context
import android.content.Intent
import android.util.Log
import com.autonion.automationcompanion.features.flow_automation.engine.NodeExecutor
import com.autonion.automationcompanion.features.flow_automation.engine.NodeResult
import com.autonion.automationcompanion.features.flow_automation.model.FlowContext
import com.autonion.automationcompanion.features.flow_automation.model.FlowNode
import com.autonion.automationcompanion.features.flow_automation.model.StartNode
import kotlinx.coroutines.delay

private const val TAG = "StartNodeExecutor"

/**
 * Executor for [StartNode]. Launches the target app if configured.
 */
class StartNodeExecutor(private val appContext: Context) : NodeExecutor {

    override suspend fun execute(node: FlowNode, context: FlowContext): NodeResult {
        val startNode = node as? StartNode
            ?: return NodeResult.Failure("Expected StartNode but got ${node::class.simpleName}")

        val packageName = startNode.appPackageName
        if (packageName.isNullOrBlank()) {
            Log.d(TAG, "No target app configured — starting flow without app launch")
            return NodeResult.Success
        }

        return try {
            val pm = appContext.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
                ?: return NodeResult.Failure("Cannot find launch intent for $packageName")

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (startNode.launchFlags != 0) {
                launchIntent.addFlags(startNode.launchFlags)
            }

            appContext.startActivity(launchIntent)
            Log.d(TAG, "Launched app: $packageName")

            // Small delay to let the app come to foreground
            delay(1500)
            NodeResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app: ${e.message}")
            NodeResult.Failure("Failed to launch $packageName: ${e.message}")
        }
    }
}
