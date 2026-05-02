package com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.util.Log
import android.widget.Toast
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class StopTrackingReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "StopTrackingReceiver"
        const val ACTION_STOP_TRACKING = "com.autonion.automationcompanion.ACTION_STOP_TRACKING"
        const val TRACKING_NOTIFICATION_ID = 1
        const val BATTERY_NOTIFICATION_ID = 1001

        /**
         * Helper to create the PendingIntent used in the notification action.
         * Use this when building the notification action in TrackingForegroundService.
         */
//        fun buildStopPendingIntent(context: Context): android.app.PendingIntent {
//            val stopIntent = Intent(context, StopTrackingReceiver::class.java).apply {
//                action = ACTION_STOP_TRACKING
//            }
//            return android.app.PendingIntent.getBroadcast(
//                context,
//                0,
//                stopIntent,
//                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
//            )
//        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "Stop action received — stopping tracking (action=${intent?.action})")

        // Stop the foreground tracking service (stops location updates / geofences)
        try {
            TrackingForegroundService.stop(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TrackingForegroundService", e)
        }

        // Cancel all known persistent notifications (tracking + battery)
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(TRACKING_NOTIFICATION_ID)       // Location tracking notification (ID = 1)
            nm.cancel(BATTERY_NOTIFICATION_ID)         // Battery monitoring notification (ID = 1001)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel notification: ${e.message}")
        }

        // Also stop BatteryMonitoringService if running
        try {
            val batteryIntent = Intent(context,
                com.autonion.automationcompanion.features.system_context_automation.battery.engine.BatteryMonitoringService::class.java)
            context.stopService(batteryIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop BatteryMonitoringService: ${e.message}")
        }

        // Unregister any geofences / listeners asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            try {
                LocationHelper.unregisterAllGeofences(context)
                Log.i(TAG, "Geofences unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister geofences: ${e.message}")
            }
        }

        // Give an immediate UX cue
        Toast.makeText(context, "Tracking stopped", Toast.LENGTH_SHORT).show()
    }
}
