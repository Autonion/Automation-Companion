package com.autonion.automationcompanion.features.system_context_automation.location.helpers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.GeofenceBroadcastReceiver
import com.google.android.gms.location.LocationServices

object LocationHelper {

    fun unregisterAllGeofences(context: Context) {
        val geofencingClient = LocationServices.getGeofencingClient(context)
        geofencingClient.removeGeofences(geofencePendingIntent(context))
            .addOnSuccessListener {
                Log.i("LocationHelper", "All geofences removed")
            }
            .addOnFailureListener { e ->
                Log.w("LocationHelper", "Failed removing geofences: ${e.message}")
            }
    }

//    fun unregisterGeofenceById(context: Context, slotId: Long) {
//        val geofencingClient = LocationServices.getGeofencingClient(context)
//        geofencingClient.removeGeofences(listOf(slotId.toString()))
//            .addOnSuccessListener {
//                Log.i("LocationHelper", "Geofence removed for slot $slotId")
//            }
//            .addOnFailureListener { e ->
//                Log.w("LocationHelper", "Failed removing geofence for $slotId: ${e.message}")
//            }
//    }

    private fun geofencePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
