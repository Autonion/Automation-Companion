package com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.AppInitManager

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AppInitManager.init(context)
        }

        Log.i("BootRestore", "BOOT_COMPLETED received — restoring automations")

        // 🔁 Restore all enabled slots + geofences
        TrackingForegroundService.startAll(context)
    }
}
