package com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAlarmScheduler
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAutomationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class SlotStartAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LocationAlarmScheduler.ACTION_SLOT_START) return
        val slotId = intent.getLongExtra(LocationReminderReceiver.EXTRA_SLOT_ID, -1L)
        if (slotId < 0) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    // Validate the current row, rearm its next occurrence, and execute
                    // through the same eligibility guard as geofence/tick deliveries.
                    LocationAutomationController.onAlarm(context.applicationContext, slotId)
                }
            } catch (e: Exception) {
                Log.e("SlotStartAlarm", "Could not process slot start for $slotId", e)
            } finally {
                result.finish()
            }
        }
    }
}
