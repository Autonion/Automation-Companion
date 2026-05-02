package com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.wifi.engine.WiFiMonitorManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Device boot completed - scheduling midnight reset and re-registering all slots")
            
            // Schedule daily midnight reset (for resetting lastExecutedDay)
            MidnightResetScheduler.schedule(context)
            
            // IMMEDIATELY re-register all enabled slots on boot (critical fix)
            // This ensures location tracking resumes after device restart
            TrackingForegroundService.startAll(context)
            
            // Initialize WiFi monitoring for Android 7+
            WiFiMonitorManager.initialize(context)
        }
    }
}
