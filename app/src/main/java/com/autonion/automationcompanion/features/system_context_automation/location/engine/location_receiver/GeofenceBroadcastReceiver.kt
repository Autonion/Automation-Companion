package com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.GeofencingEvent
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.SendHelper
import com.google.android.gms.location.Geofence


class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        //private const val TAG = "GeofenceReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            Log.i("LocDebug", "GeofenceBroadcastReceiver.ONRECV RAW: action=${intent.action} comp=${intent.component} data=${intent.data} flags=0x${intent.flags.toString(16)} extras=${intent.extras}")

            val extras = intent.extras
            if (extras == null) {
                Log.i("LocDebug", "GeofenceBroadcastReceiver: intent.extras is NULL")
            } else {
                Log.i("LocDebug", "GeofenceBroadcastReceiver: extras.size=${extras.size()}")
                for (k in extras.keySet()) {
                    try {
                        val v = extras.get(k)
                        Log.i("LocDebug", "  extra[${k}] -> (${v?.javaClass?.name}) : $v")
                    } catch (t: Throwable) {
                        Log.w("LocDebug", "  extra[$k] -> (toString failed): ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("LocDebug", "Exception while dumping incoming Intent: ${t.message}", t)
        }

        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null) {
            Log.w("GeofenceReceiver", "Received geofence broadcast but could not parse GeofencingEvent (null)")
            return
        }

        if (geofencingEvent.hasError()) {
            val errorCode = geofencingEvent.errorCode
            Log.w("GeofenceReceiver", "GeofencingEvent has error code: $errorCode")
            return
        }

        val transition = geofencingEvent.geofenceTransition
        if (transition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            for (g in geofencingEvent.triggeringGeofences ?: emptyList()) {
                val requestId = g.requestId
                val slotId = requestId.toLongOrNull()
                if (slotId == null) {
                    Log.w("GeofenceReceiver", "Invalid geofence requestId: $requestId")
                    continue
                }
                // NEW: shared helper to send if needed (handles sent flag + time window)
                SendHelper.startSendIfNeeded(context, slotId)
            }
        } else {
            Log.i("GeofenceReceiver", "Geofence transition not ENTER: $transition")
        }
    }

}
