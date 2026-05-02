// Update your BatteryBroadcastReceiver.kt
package com.autonion.automationcompanion.features.system_context_automation.battery.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.shared.models.TriggerConfig
import com.autonion.automationcompanion.features.system_context_automation.shared.executor.SlotExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class BatteryBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BatteryReceiver"

        // Helper to get battery percentage
        fun getBatteryPercentage(context: Context): Int {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                context.registerReceiver(null, filter)
            }
            return batteryStatus?.let { intent ->
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                (level * 100 / scale.toFloat()).toInt()
            } ?: -1
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level == -1 || scale == 0) return

            val batteryPercentage = (level * 100 / scale.toFloat()).toInt()
            Log.i(TAG, "Battery level changed to $batteryPercentage%")
            DebugLogger.info(
                context, LogCategory.SYSTEM_CONTEXT,
                "Battery: $batteryPercentage%",
                "Battery level changed",
                TAG
            )

            CoroutineScope(Dispatchers.IO).launch {
                // 1. Maintain existing Slot logic (optional, keep for backward compatibility)
                evaluateBatterySlots(context, batteryPercentage)
                
                // 2. Publish to Cross-Device Event Bus
                val event = com.autonion.automationcompanion.features.cross_device_automation.domain.RawEvent(
                    id = java.util.UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    type = "system.battery.changed",
                    sourceDeviceId = "local",
                    payload = mapOf(
                        "level" to batteryPercentage.toString(),
                        "is_charging" to (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING).toString()
                    )
                )
                com.autonion.automationcompanion.features.cross_device_automation.event_pipeline.EventBus.publish(event)
            }
        }
    }

    private suspend fun evaluateBatterySlots(context: Context, currentLevel: Int) {
        val dao = AppDatabase.get(context).slotDao()
        val allSlots = dao.getAllEnabled()

        val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }

        for (slot in allSlots) {
            if (slot.triggerType != "BATTERY") continue

            val triggerConfig = try {
                slot.triggerConfigJson?.let { json.decodeFromString<TriggerConfig.Battery>(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deserialize battery config for slot ${slot.id}", e)
                DebugLogger.error(
                    context, LogCategory.SYSTEM_CONTEXT,
                    "Bad battery config",
                    "Slot ${slot.id}: ${e.message}",
                    TAG
                )
                null
            } ?: continue

            // Check if battery level matches threshold
            val shouldTrigger = when (triggerConfig.thresholdType) {
                TriggerConfig.Battery.ThresholdType.REACHES_OR_BELOW -> currentLevel <= triggerConfig.batteryPercentage
                TriggerConfig.Battery.ThresholdType.REACHES_OR_ABOVE -> currentLevel >= triggerConfig.batteryPercentage
            }

            if (shouldTrigger) {
                Log.i(TAG, "Battery slot ${slot.id} triggered (level=$currentLevel, threshold=${triggerConfig.batteryPercentage})")
                DebugLogger.success(
                    context, LogCategory.SYSTEM_CONTEXT,
                    "Battery slot ${slot.id} triggered",
                    "Level=$currentLevel%, threshold=${triggerConfig.batteryPercentage}% (${triggerConfig.thresholdType})",
                    TAG
                )
                SlotExecutor.execute(context, slot.id)
            }
        }
    }
}