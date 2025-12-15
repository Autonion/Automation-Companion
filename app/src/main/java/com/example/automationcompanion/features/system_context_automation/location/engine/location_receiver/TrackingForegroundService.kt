package com.example.automationcompanion.features.system_context_automation.location.engine.location_receiver

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.app.NotificationManager
import android.app.NotificationChannel
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.automationcompanion.R
import com.example.automationcompanion.features.system_context_automation.location.helpers.FallbackFlow
import com.example.automationcompanion.features.system_context_automation.location.helpers.SendHelper
import com.example.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.max

class TrackingForegroundService : Service() {
    companion object {
        const val ACTION_START_FOR_SLOT = "com.example.automationcompanion.ACTION_START_SLOT"
        const val ACTION_STOP_FOR_SLOT = "com.example.automationcompanion.ACTION_STOP_SLOT"
        private const val CHANNEL_ID = "automationcompanion_tracking"

        /**
         * Generic start without slotId. Starts service in foreground (useful for manual starts).
         */
        fun start(context: Context) {
            val i = Intent(context, TrackingForegroundService::class.java).apply {
                action = ACTION_START_FOR_SLOT // reuse action for generic start
            }
            ContextCompat.startForegroundService(context, i)
        }

        /**
         * Generic stop. Sends a stop request to the service.
         */
        fun stop(context: Context) {
            val i = Intent(context, TrackingForegroundService::class.java).apply {
                action = ACTION_STOP_FOR_SLOT
            }
            context.startService(i)
        }

        /**
         * Start the service for a specific slot (slotId must be provided so the service can register a geofence).
         */
        fun startForSlot(context: Context, slotId: Long) {
            val i = Intent(context, TrackingForegroundService::class.java).apply {
                action = ACTION_START_FOR_SLOT
                putExtra("slotId", slotId)
            }
            ContextCompat.startForegroundService(context, i)
        }

        /**
         * Stop the service for a specific slot.
         */
        fun stopForSlot(context: Context, slotId: Long) {
            val i = Intent(context, TrackingForegroundService::class.java).apply {
                action = ACTION_STOP_FOR_SLOT
                putExtra("slotId", slotId)
            }
            context.startService(i)
        }
    }

    private lateinit var geofencingClient: GeofencingClient
//    private val geofencePendingIntent: PendingIntent by lazy {
//        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
//        PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
//    }


    override fun onCreate() {
        super.onCreate()
        createChannel()
        geofencingClient = LocationServices.getGeofencingClient(this)
//        val notification = buildNotification()
//        startForeground(1, notification)
        // TODO: start FusedLocationProvider or Geofence registration here
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("LocDebug", "TrackingForegroundService onStartCommand action=${intent?.action} extras=${intent?.extras}")

        val action = intent?.action
        val slotId = intent?.getLongExtra("slotId", -1L) ?: -1L

        if (action == ACTION_START_FOR_SLOT && slotId != -1L) {
            startForeground(1, buildNotification())
            registerGeofenceForSlot(slotId)
        } else if ((action == ACTION_STOP_FOR_SLOT && slotId != -1L) || action == null) {
            // stop tracking: remove geofence for that slot and stop foreground
            unregisterGeofenceForSlot(slotId)
            stopForeground(true)
            stopSelf()
        }
        return START_STICKY
    }

    private fun registerGeofenceForSlot(slotId: Long) {
        // Read slot from DB on background thread
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.get(applicationContext)
            val slot = db.slotDao().getById(slotId)
            if (slot == null) {
                Log.w("LocDebug", "registerGeofenceForSlot: slot null for id=$slotId")
                return@launch
            }

            // Permission check
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                FallbackFlow.showEnableLocationNotification(applicationContext)
                Log.w("LocDebug", "registerGeofenceForSlot: missing ACCESS_FINE_LOCATION")
                return@launch
            }

            val g = Geofence.Builder()
                .setRequestId(slot.id.toString())
                .setCircularRegion(slot.lat, slot.lng, slot.radiusMeters)
                .setExpirationDuration(max(0L, slot.endMillis - System.currentTimeMillis()))
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()

            val geofenceRequest = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofence(g)
                .build()

            // IMPORTANT: use a **component-only** intent here (no custom action / extras).
            // Play Services will populate the delivered Intent with its own extras that GeofencingEvent.fromIntent expects.
            val piIntent = Intent(applicationContext, GeofenceBroadcastReceiver::class.java)
            val requestCode = slot.id.toInt() // unique per slot avoids collisions
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pi = PendingIntent.getBroadcast(applicationContext, requestCode, piIntent, piFlags)

            try {
                Log.i("LocDebug", "registerGeofenceForSlot called for slotId=$slotId (lat=${slot.lat}, lng=${slot.lng}, radius=${slot.radiusMeters})")
                geofencingClient.addGeofences(geofenceRequest, pi)
                    .addOnSuccessListener {
                        Log.i("LocDebug", "Geofence added for slot ${slot.id}")

                        // Immediate proximity check: if device is inside and slot active, trigger send
                        val now = System.currentTimeMillis()
                        if (now >= slot.startMillis && now <= slot.endMillis) {
                            try {
                                val fused = LocationServices.getFusedLocationProviderClient(applicationContext)
                                fused.lastLocation.addOnSuccessListener { loc ->
                                    if (loc != null) {
                                        val results = FloatArray(1)
                                        Location.distanceBetween(slot.lat, slot.lng, loc.latitude, loc.longitude, results)
                                        val dist = results[0]
                                        Log.i("LocDebug", "Immediate proximity check: dist=$dist radius=${slot.radiusMeters}")
                                        if (dist <= slot.radiusMeters) {
                                            // if inside, trigger the same send flow (helper handles sent flag)
                                            SendHelper.startSendIfNeeded(applicationContext, slot.id)
                                        }
                                    } else {
                                        Log.i("LocDebug", "Immediate proximity check: lastLocation null")
                                    }
                                }.addOnFailureListener { ex ->
                                    if (ex is ApiException) {
                                        val statusCode = ex.statusCode
                                        Log.e("LocDebug", "Failed to add geofence for slot $slotId: statusCode=$statusCode message=${ex.message}", ex)
                                    } else {
                                        Log.e("LocDebug", "Failed to add geofence for slot $slotId: ${ex.message}", ex)
                                    }
                                }
                            } catch (_: SecurityException) {
                                Log.w("LocDebug", "Immediate proximity check skipped: missing location permission")
                            }
                        }
                    }
                    .addOnFailureListener { ex ->
                        Log.e("LocDebug", "Failed to add geofence for slot ${slot.id}: ${ex.message}", ex)
                        FallbackFlow.showEnableLocationNotification(applicationContext)
                    }
            } catch (ise: SecurityException) {
                Log.e("LocDebug", "SecurityException when adding geofence for slot $slotId", ise)
                FallbackFlow.showEnableLocationNotification(applicationContext)
            }
        }
    }



    private fun unregisterGeofenceForSlot(slotId: Long) {
        val idList = listOf(slotId.toString())
        geofencingClient.removeGeofences(idList).addOnSuccessListener {
            Log.i("TrackingService", "Geofence removed for $slotId")
        }.addOnFailureListener { ex -> Log.w("TrackingService", "Failed to remove geofence: ${ex.message}") }
    }


    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, StopTrackingReceiver::class.java)
        val pi = PendingIntent.getBroadcast(this, 0, stopIntent, PendingIntent.FLAG_MUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tracking active")
            .setContentText("Monitoring location for your slot")
            .setSmallIcon(R.drawable.ic_location)
            .addAction(R.drawable.ic_stop, "Stop", pi)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val c = NotificationChannel(CHANNEL_ID, "Tracking", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(c)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
