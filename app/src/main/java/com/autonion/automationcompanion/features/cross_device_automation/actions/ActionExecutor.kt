package com.autonion.automationcompanion.features.cross_device_automation.actions

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceRole
import com.autonion.automationcompanion.features.cross_device_automation.domain.RuleAction
import com.autonion.automationcompanion.features.cross_device_automation.networking.NetworkingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ActionExecutor(
    private val context: Context,
    private val networkingManager: NetworkingManager
) {

    fun execute(action: RuleAction) {
        if (action.targetDeviceId == null) {
            // Local action or broadcast?
            // For now, treat null as local if suitable, or ignore.
             executeLocal(action)
        } else {
             // Remote action
             executeRemote(action)
        }
    }

    private fun executeLocal(action: RuleAction) {
        Log.d("ActionExecutor", "Executing local action: ${action.type}")
        when (action.type) {
            "enable_dnd" -> {
                // Toggle DND (requires permission, just log for now)
                Log.i("ActionExecutor", "Toggling DND")
            }
            "open_url" -> {
                // Open URL locally
                val url = action.parameters["url"]
                Log.i("ActionExecutor", "Opening URL locally: $url")
                 // Intent logic here
            }
        }
    }

    private fun executeRemote(action: RuleAction) {
         Log.d("ActionExecutor", "Executing remote action on ${action.targetDeviceId}")
         // Send command to remote device
         // Wrap action in a Command object or just send the action map?
         // Spec says: { action: "open_url", url: "..." }
         
         val command = mapOf(
             "action" to action.type
         ) + action.parameters
         
         if (action.targetDeviceId != null) {
             networkingManager.sendCommand(action.targetDeviceId, command)
         }
    }
}
