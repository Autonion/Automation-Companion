package com.autonion.automationcompanion.features.system_context_automation.shared.executor

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAutomationController
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationTriggerEvaluator
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.SendHelper
import com.autonion.automationcompanion.features.system_context_automation.shared.models.TriggerConfig
import com.autonion.automationcompanion.features.system_context_automation.timeofday.engine.TimeOfDayReceiver
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** Claims against current rows before actions. Deleted/paused/edited snapshots cannot start a new execution. */
object SlotExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        Log.e("SlotExecutor", "System trigger failed", error)
    })

    fun execute(context: Context, slotId: Long, expected: Slot? = null) {
        scope.launch { executeNow(context.applicationContext, slotId, expected) }
    }

    suspend fun executeNow(context: Context, slotId: Long, expected: Slot? = null, batteryLevel: Int? = null): Boolean {
        if (AppDatabase.get(context).slotDao().getById(slotId)?.triggerType == "LOCATION") {
            LocationTriggerEvaluator.evaluate(context, slotId)
            return false
        }
        val claimed = claim(context, slotId, expected, batteryLevel) ?: return false
        DebugLogger.success(context, LogCategory.SYSTEM_CONTEXT, "Slot #$slotId executed",
            "Trigger type: ${claimed.triggerType}, actions: ${claimed.actions.size}", "SlotExecutor")
        // Already claimed actions may finish. Deletion/Stop prevents future claims without waiting for side effects.
        SendHelper.executeClaimedSystemActions(context, claimed)
        return true
    }

    internal suspend fun claim(context: Context, slotId: Long, expected: Slot? = null, batteryLevel: Int? = null): Slot? =
        LocationAutomationController.mutex.withLock {
            val dao = AppDatabase.get(context).slotDao()
            val slot = dao.getById(slotId) ?: return@withLock null
            if (!slot.enabled || slot.triggerType == "LOCATION") return@withLock null
            if (expected != null && (slot.triggerType != expected.triggerType ||
                    slot.triggerConfigJson != expected.triggerConfigJson || slot.actions != expected.actions)) return@withLock null
            when (slot.triggerType) {
                "BATTERY" -> {
                    if (batteryLevel == null || batteryLevel !in 0..100) return@withLock null
                    val config = json.decodeFromString<TriggerConfig.Battery>(slot.triggerConfigJson ?: return@withLock null)
                    val met = when (config.thresholdType) {
                        TriggerConfig.Battery.ThresholdType.REACHES_OR_BELOW -> batteryLevel <= config.batteryPercentage
                        TriggerConfig.Battery.ThresholdType.REACHES_OR_ABOVE -> batteryLevel >= config.batteryPercentage
                    }
                    dao.updateLastTriggerState(slotId, met)
                    if (!met || slot.lastTriggerState == true) return@withLock null
                }
                "TIME_OF_DAY" -> {
                    val config = json.decodeFromString<TriggerConfig.TimeOfDay>(slot.triggerConfigJson ?: return@withLock null)
                    val today = LocalDate.now()
                    if (!TimeOfDayReceiver.isActiveDay(config.activeDays, today.dayOfWeek) ||
                        slot.lastExecutedDay == today.toString() || (!config.repeatDaily && slot.lastExecutedDay != null)) return@withLock null
                    dao.update(slot.copy(lastExecutedDay = today.toString(), enabled = config.repeatDaily))
                }
                "APP", "WIFI" -> Unit
                else -> return@withLock null
            }
            slot
        }
}
