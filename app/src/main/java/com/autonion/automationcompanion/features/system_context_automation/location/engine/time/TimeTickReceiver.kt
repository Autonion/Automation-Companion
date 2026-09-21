package com.autonion.automationcompanion.features.system_context_automation.location.engine.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAutomationController
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationTriggerEvaluator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class TimeTickReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INTERNAL_TIME_TICK = "com.autonion.automationcompanion.action.INTERNAL_TIME_TICK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INTERNAL_TIME_TICK && intent.action != Intent.ACTION_TIME_TICK) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    LocationAutomationController.ensureMonitoring(context)
                    val slots = AppDatabase.get(context).slotDao().getEnabledSlotsByType("LOCATION")
                    for (slot in slots) {
                        // Rechecks the current row and occurrence under the controller mutex.
                        LocationTriggerEvaluator.evaluate(context, slot.id)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("TimeTickReceiver", "Unable to evaluate location slots", error)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
