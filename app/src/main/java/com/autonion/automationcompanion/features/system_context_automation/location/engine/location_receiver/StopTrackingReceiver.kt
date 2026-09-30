package com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAutomationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class StopTrackingReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STOP_TRACKING = "com.autonion.automationcompanion.ACTION_STOP_TRACKING"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_STOP_TRACKING) return
        val result = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withTimeout(8_000) { LocationAutomationController.stopAll(app) }
                Toast.makeText(app, "Location automations paused", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                // Controller operations survive the receiver's timeout to finish cleanup.
                Log.w("StopTrackingReceiver", "Location cleanup continuing or failed", e)
            } finally {
                result.finish()
            }
        }
    }
}
