package com.autonion.automationcompanion.features.flow_automation.engine.executors

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.AccessibilityRouter
import com.autonion.automationcompanion.features.flow_automation.engine.NodeExecutor
import com.autonion.automationcompanion.features.flow_automation.engine.NodeResult
import com.autonion.automationcompanion.features.flow_automation.model.ClipboardNode
import com.autonion.automationcompanion.features.flow_automation.model.ClipboardOperation
import com.autonion.automationcompanion.features.flow_automation.model.FlowContext
import com.autonion.automationcompanion.features.flow_automation.model.FlowNode
import com.autonion.automationcompanion.features.flow_automation.model.InputSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ClipboardExecutor"

class ClipboardExecutor(private val appContext: Context?) : NodeExecutor {

    override suspend fun execute(node: FlowNode, context: FlowContext): NodeResult {
        val clipboardNode = node as? ClipboardNode
            ?: return NodeResult.Failure("Expected ClipboardNode but got ${node::class.simpleName}")

        val ctx = AccessibilityRouter.getService() ?: appContext
            ?: return NodeResult.Failure("App context not available")

        return withContext(Dispatchers.Main) {
            try {
                val clipboardManager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return@withContext NodeResult.Failure("ClipboardManager not available")

                when (clipboardNode.operation) {
                    ClipboardOperation.READ -> {
                        val clipData = clipboardManager.primaryClip
                        if (clipData != null && clipData.itemCount > 0) {
                            val text = clipData.getItemAt(0).coerceToText(ctx)?.toString() ?: ""
                            Log.d(TAG, "Read from clipboard: '$text'")
                            context.put(clipboardNode.contextKey, text)
                            NodeResult.Success
                        } else if (context.contains(clipboardNode.contextKey)) {
                            Log.w(TAG, "Clipboard unavailable or empty; using existing context value for '${clipboardNode.contextKey}'")
                            NodeResult.Success
                        } else {
                            Log.w(TAG, "Clipboard is empty")
                            context.put(clipboardNode.contextKey, "")
                            NodeResult.Success
                        }
                    }
                    ClipboardOperation.WRITE -> {
                        val textToWrite = resolveTextToWrite(clipboardNode, context)
                        val clip = ClipData.newPlainText("automation", textToWrite)
                        clipboardManager.setPrimaryClip(clip)
                        Log.d(TAG, "Wrote to clipboard: '$textToWrite'")
                        NodeResult.Success
                    }
                }
            } catch (e: Exception) {
                if (clipboardNode.operation == ClipboardOperation.READ && context.contains(clipboardNode.contextKey)) {
                    Log.w(TAG, "Clipboard read failed; using existing context value for '${clipboardNode.contextKey}'", e)
                    return@withContext NodeResult.Success
                }
                Log.e(TAG, "Clipboard operation failed", e)
                NodeResult.Failure("Clipboard error: ${e.message}")
            }
        }
    }

    private fun resolveTextToWrite(node: ClipboardNode, context: FlowContext): String {
        return when (val source = node.inputSource) {
            is InputSource.Static -> {
                if (source.text.isEmpty() && context.contains(node.contextKey)) {
                    context.get<Any>(node.contextKey)?.toString() ?: ""
                } else {
                    source.text
                }
            }
            is InputSource.FromContext -> {
                val key = source.key.ifBlank { node.contextKey }
                context.get<Any>(key)?.toString() ?: ""
            }
            is InputSource.Clipboard -> context.get<Any>(node.contextKey)?.toString() ?: ""
        }
    }
}
