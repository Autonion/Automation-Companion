package com.autonion.automationcompanion.features.flow_automation.engine.executors

import android.util.Log
import com.autonion.automationcompanion.features.flow_automation.engine.NodeExecutor
import com.autonion.automationcompanion.features.flow_automation.engine.NodeResult
import com.autonion.automationcompanion.features.flow_automation.model.FlowContext
import com.autonion.automationcompanion.features.flow_automation.model.FlowNode
import com.autonion.automationcompanion.features.flow_automation.model.InputNode
import com.autonion.automationcompanion.features.flow_automation.model.InputSource
import com.autonion.automationcompanion.features.semantic_automation.core.AccessibilityTreeReader
import kotlinx.coroutines.delay

private const val TAG = "InputExecutor"

class InputExecutor : NodeExecutor {

    override suspend fun execute(node: FlowNode, context: FlowContext): NodeResult {
        val inputNode = node as? InputNode
            ?: return NodeResult.Failure("Expected InputNode but got ${node::class.simpleName}")

        if (!AccessibilityTreeReader.isAvailable()) {
            return NodeResult.Failure("AccessibilityService is not connected")
        }

        val textToInput = when (val source = inputNode.inputSource) {
            is InputSource.Static -> source.text
            is InputSource.FromContext -> context.get<Any>(source.key)?.toString() ?: ""
        }

        Log.d(TAG, "Attempting to input text: '\$textToInput'")

        // By default, try setting text on the currently focused field.
        val success = AccessibilityTreeReader.performSetTextOnFocused(textToInput)

        if (!success) {
            return NodeResult.Failure("Could not find a focused editable element or failed to set text")
        }

        if (inputNode.submitAfterInput) {
            delay(300) // Small delay before submission
            val submitSuccess = AccessibilityTreeReader.performImeAction()
            if (!submitSuccess) {
                Log.w(TAG, "Failed to submit IME action after text input")
            }
        }

        return NodeResult.Success
    }
}
