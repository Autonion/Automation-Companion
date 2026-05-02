package com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        val event = GeofencingEvent.fromIntent(intent)
        if (event == null) {
            Log.w("LocDebug", "Geofence event is null")
            DebugLogger.warning(
                context, LogCategory.SYSTEM_CONTEXT,
                "Geofence event null",
                "Received null geofence event from intent",
                "GeofenceBroadcastReceiver"
            )
            return
        }

        if (event.hasError()) {
            Log.w("LocDebug", "Geofence error: ${event.errorCode}")
            DebugLogger.error(
                context, LogCategory.SYSTEM_CONTEXT,
                "Geofence error",
                "Error code: ${event.errorCode}",
                "GeofenceBroadcastReceiver"
            )
            return
        }

        val transition = event.geofenceTransition
        val triggering = event.triggeringGeofences ?: return

        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.get(context).slotDao()

            for (geofence in triggering) {
                val slotId = geofence.requestId.toLongOrNull()
                if (slotId == null) {
                    Log.w("LocDebug", "Invalid geofence requestId=${geofence.requestId}")
                    continue
                }

                when (transition) {
                    Geofence.GEOFENCE_TRANSITION_ENTER -> {
                        dao.updateInsideGeofence(slotId, true)
                        Log.i("LocDebug", "Slot $slotId → insideGeofence = true")
                        DebugLogger.success(
                            context, LogCategory.SYSTEM_CONTEXT,
                            "Geofence entered",
                            "Slot $slotId entered geofence zone",
                            "GeofenceBroadcastReceiver"
                        )
                    }

                    Geofence.GEOFENCE_TRANSITION_EXIT -> {
                        dao.updateInsideGeofence(slotId, false)
                        Log.i("LocDebug", "Slot $slotId → insideGeofence = false")
                        DebugLogger.info(
                            context, LogCategory.SYSTEM_CONTEXT,
                            "Geofence exited",
                            "Slot $slotId left geofence zone",
                            "GeofenceBroadcastReceiver"
                        )
                    }
                }
            }
        }
    }
}
