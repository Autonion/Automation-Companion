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
    private var lastTriggerTime: Long = 0L

    companion object {
        private const val TAG = "AppAutomationEngine"
        private const val DEBOUNCE_WINDOW_MS = 1500L

        // System overlay and transient packages that should NEVER be treated as foreground app switches
        private val SYSTEM_OVERLAY_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.android.settings.intelligence"
        )
    }

    override fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return

            // 1. Ignore system overlays, notification shade, power menu, and our own app
            if (isIgnoredOverlayPackage(packageName)) {
                Log.d(TAG, "Ignoring transient system/overlay package: $packageName")
                return
            }

            // 2. Debounce: if the package hasn't changed, ignore intra-app window transitions
            if (packageName == lastPackageName) return

            val now = System.currentTimeMillis()
            val previousPackage = lastPackageName
            lastPackageName = packageName
            lastTriggerTime = now

            Log.i(TAG, "Foreground app switched: ${previousPackage ?: "none"} -> $packageName")

            // 3. Evaluate CLOSE triggers for the previous app
            if (previousPackage != null) {
                evaluateAppSlots(previousPackage, isAppOpen = false)
            }

            // 4. Evaluate OPEN triggers for the new app
            evaluateAppSlots(packageName, isAppOpen = true)
        }
    }

    private fun isIgnoredOverlayPackage(packageName: String): Boolean {
        // Our own app (notifications/toasts/dialogs shouldn't reset debounce)
        if (packageName == context.packageName) return true

        // Known system UI overlays (Notification shade, Quick Settings, Volume panel)
        if (SYSTEM_OVERLAY_PACKAGES.contains(packageName)) return true

        // Input Method / Keyboard packages
        if (packageName.contains(".inputmethod.") || 
            packageName.contains(".honeyboard") || 
            packageName.contains(".swiftkey") ||
            packageName.endsWith(".keyboard")) {
            return true
        }

        return false
    }

    private fun evaluateAppSlots(currentPackageName: String, isAppOpen: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.get(context).slotDao()
                val allSlots = dao.getAllEnabled()

                for (slot in allSlots) {
                    if (slot.triggerType != "APP") continue

                    val config = try {
                        slot.triggerConfigJson?.let {
                            json.decodeFromString<TriggerConfig>(it) as? TriggerConfig.App
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing config for slot ${slot.id}", e)
                        null
                    } ?: continue

                    val isPackageMatch = config.packageName == currentPackageName
                    val targetTrigger = if (isAppOpen) TriggerConfig.App.TriggerOn.OPEN else TriggerConfig.App.TriggerOn.CLOSE
                    val isTriggerMatch = config.triggerOn == targetTrigger

                    if (isPackageMatch && isTriggerMatch) {
                        val actionLabel = if (isAppOpen) "OPEN" else "CLOSE"
                        Log.i(TAG, "Triggering App Automation ($actionLabel) for $currentPackageName")
                        DebugLogger.success(
                            context, LogCategory.SYSTEM_CONTEXT,
                            "App $actionLabel executed",
                            "App: $currentPackageName",
                            TAG
                        )
                        SlotExecutor.execute(context, slot.id)
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
}
