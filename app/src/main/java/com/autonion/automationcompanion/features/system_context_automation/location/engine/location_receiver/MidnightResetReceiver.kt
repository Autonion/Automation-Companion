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

class MidnightResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    // Persistent geofences keep current presence across midnight.
                    // Reconciliation also retires the obsolete global midnight alarm.
                    LocationAutomationController.reconcile(context.applicationContext)
                }
            } catch (e: Exception) {
                Log.e("MidnightReset", "Could not refresh location schedules", e)
            } finally {
                result.finish()
            }
        }
    }
}
