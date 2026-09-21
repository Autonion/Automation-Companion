package com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAutomationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Safely handles alarms left by older versions. New alarms use SlotStartAlarmReceiver. */
class SlotAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != "com.autonion.automationcompanion.START_SLOT" &&
            action != "com.autonion.automationcompanion.STOP_SLOT") return
        val result = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    if (action == "com.autonion.automationcompanion.START_SLOT") {
                        val id = intent.getLongExtra("slotId", -1L)
                        if (id >= 0) LocationAutomationController.onAlarm(app, id)
                        else LocationAutomationController.reconcile(app)
                    } else {
                        // An old window-end alarm must not disable a recurring slot or stop
                        // other slots. Eligibility is now evaluated by LocationSchedule.
                        LocationAutomationController.reconcile(app)
                    }
                }
            } catch (e: Exception) {
                Log.w("SlotAlarmReceiver", "Could not reconcile legacy alarm", e)
            } finally {
                result.finish()
            }
        }
    }
}
