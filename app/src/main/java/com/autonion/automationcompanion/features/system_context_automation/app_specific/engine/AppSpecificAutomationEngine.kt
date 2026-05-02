package com.autonion.automationcompanion.features.system_context_automation.app_specific.engine

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.autonion.automationcompanion.AccessibilityFeature
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.shared.executor.SlotExecutor
import com.autonion.automationcompanion.features.system_context_automation.shared.models.TriggerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class AppSpecificAutomationEngine(private val context: Context) : AccessibilityFeature {

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
    private var lastPackageName: String? = null

    override fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            
            // Debounce/Avoid duplicate triggers for same package session
            if (packageName == lastPackageName) return
            lastPackageName = packageName

            Log.d(TAG, "App opened: $packageName")
            evaluateAppSlots(packageName)
        }
    }

    private fun evaluateAppSlots(currentPackageName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.get(context).slotDao()
                val allSlots = dao.getAllEnabled()
                
                // Debug log to see if we satisfy the query
                Log.d(TAG, "Evaluating ${allSlots.size} enabled slots for package: $currentPackageName")

                for (slot in allSlots) {
                    if (slot.triggerType != "APP") continue

                    val config = try {
                        slot.triggerConfigJson?.let {
                            // Decode as polymorphic TriggerConfig first, then check type
                            json.decodeFromString<TriggerConfig>(it) as? TriggerConfig.App
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing config for slot ${slot.id}", e)
                        DebugLogger.error(
                            context, LogCategory.SYSTEM_CONTEXT,
                            "App slot parsing error",
                            "Slot ${slot.id}: ${e.message}",
                            TAG
                        )
                        null
                    } ?: continue

                    // Debug log for matching logic
                    val isPackageMatch = config.packageName == currentPackageName
                    val isTriggerMatch = config.triggerOn == TriggerConfig.App.TriggerOn.OPEN
                    
                    if (isPackageMatch && isTriggerMatch) {
                        Log.i(TAG, "Triggering App Automation for $currentPackageName")
                        DebugLogger.success(
                            context, LogCategory.SYSTEM_CONTEXT,
                            "App trigger executed",
                            "App: $currentPackageName",
                            TAG
                        )
                        SlotExecutor.execute(context, slot.id)
                    } else if (isPackageMatch) {
                        // Log mismatch for debugging
                         Log.d(TAG, "Package matched but trigger condition failed. Config: ${config.triggerOn}, Event: OPEN")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error evaluating app slots", e)
                DebugLogger.error(
                    context, LogCategory.SYSTEM_CONTEXT,
                    "App slot evaluation error",
                    "${e.message}",
                    TAG
                )
            }
        }
    }

    companion object {
        private const val TAG = "AppAutomationEngine"
    }
}
