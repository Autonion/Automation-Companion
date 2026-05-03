package com.autonion.automationcompanion.features.flow_automation.engine.executors

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.flow_automation.engine.NodeExecutor
import com.autonion.automationcompanion.features.flow_automation.engine.NodeResult
import com.autonion.automationcompanion.features.flow_automation.model.ClipboardNode
import com.autonion.automationcompanion.features.flow_automation.model.ClipboardOperation
import com.autonion.automationcompanion.features.flow_automation.model.FlowContext
import com.autonion.automationcompanion.features.flow_automation.model.FlowNode
import com.autonion.automationcompanion.features.flow_automation.model.InputSource

private const val TAG = "ClipboardExecutor"

class ClipboardExecutor(private val appContext: Context?) : NodeExecutor {

    override suspend fun execute(node: FlowNode, context: FlowContext): NodeResult {
        val clipboardNode = node as? ClipboardNode
            ?: return NodeResult.Failure("Expected ClipboardNode but got ${node::class.simpleName}")

        val ctx = appContext ?: return NodeResult.Failure("App context not available")
        val clipboardManager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return NodeResult.Failure("ClipboardManager not available")

        return try {
            when (clipboardNode.operation) {
                ClipboardOperation.READ -> {
                    val clipData = clipboardManager.primaryClip
                    if (clipData != null && clipData.itemCount > 0) {
                        val text = clipData.getItemAt(0).text?.toString() ?: ""
                        Log.d(TAG, "Read from clipboard: '$text'")
                        context.put(clipboardNode.contextKey, text)
                        NodeResult.Success
                    } else {
                        Log.w(TAG, "Clipboard is empty")
                        context.put(clipboardNode.contextKey, "")
                        NodeResult.Success
                    }
                }
                ClipboardOperation.WRITE -> {
                    val textToWrite = when (val source = clipboardNode.inputSource) {
                        is InputSource.Static -> source.text
                        is InputSource.FromContext -> {
                            context.get<Any>(source.key)?.toString() ?: ""
                        }
                    }
                    val clip = ClipData.newPlainText("automation", textToWrite)
                    clipboardManager.setPrimaryClip(clip)
                    Log.d(TAG, "Wrote to clipboard: '$textToWrite'")
                    NodeResult.Success
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard operation failed", e)
            NodeResult.Failure("Clipboard error: ${e.message}")
        }
    }
}
