package com.autonion.automationcompanion.features.system_context_automation.shared.executor

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.SendHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SlotExecutor is the unified entry point for executing actions from ANY trigger type.
 * It handles slot state transitions and delegates to SendHelper for action execution.
 *
 * Flow:
 * 1. Trigger fires (Location geofence, Battery broadcast, Time alarm, WiFi change)
 * 2. Trigger calls SlotExecutor.execute(context, slotId)
 * 3. SlotExecutor checks execution lock (lastExecutedDay)
 * 4. SlotExecutor calls SendHelper.executeSlotActions() which reuses existing action executor logic
 *
 * Benefits:
 * - Single entry point for all triggers
 * - Centralized execution lock logic (one per day)
 * - Reuses tested SendHelper action execution
 * - Trigger-agnostic (doesn't care about trigger type)
 */
object SlotExecutor {

    private const val TAG = "SlotExecutor"

    fun execute(context: Context, slotId: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            executeSlot(context, slotId)
        }
    }

    private suspend fun executeSlot(context: Context, slotId: Long) {
        val dao = AppDatabase.get(context).slotDao()
        val slot = dao.getById(slotId) ?: return

        if (!slot.enabled) {
            Log.i(TAG, "Slot $slotId disabled, skipping")
            return
        }

        // Check execution lock (once per day per slot) - SKIP for App/WiFi
        // App and WiFi triggers should run every time event occurs.
        // Location/Time triggers might need daily limit (though Location currently bypasses this anyway).
        if (slot.triggerType != "APP" && slot.triggerType != "WIFI") {
            val today = getTodayKey()
            if (slot.lastExecutedDay == today) {
                Log.i(TAG, "Slot $slotId already executed today, skipping")
                return
            }
             // Update execution lock
            dao.updateLastExecutedDay(slotId, today)
        }

        Log.i(TAG, "Executing slot $slotId (type=${slot.triggerType})")

        // Delegate to SendHelper which reuses the existing action executor logic
        SendHelper.startSendIfNeeded(context, slotId)
    }

    private fun getTodayKey(): String {
        val now = java.util.Calendar.getInstance()
        val year = now.get(java.util.Calendar.YEAR)
        val month = now.get(java.util.Calendar.MONTH) + 1
        val day = now.get(java.util.Calendar.DAY_OF_MONTH)
        return String.format("%04d-%02d-%02d", year, month, day)
    }
}
