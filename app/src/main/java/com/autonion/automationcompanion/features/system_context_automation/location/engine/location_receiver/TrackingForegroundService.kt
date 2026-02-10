package com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import android.app.NotificationManager
import android.app.NotificationChannel
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.autonion.automationcompanion.R
import com.autonion.automationcompanion.features.automation.actions.models.RingerMode
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.FallbackFlow
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.SendHelper
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.max

class TrackingForegroundService : Service() {
    companion object {
        const val ACTION_START_FOR_SLOT = "com.autonion.automationcompanion.ACTION_START_SLOT"
        const val ACTION_STOP_FOR_SLOT = "com.autonion.automationcompanion.ACTION_STOP_SLOT"
        const val ACTION_START_ALL = "com.autonion.automationcompanion.ACTION_START_ALL"
        const val ACTION_PERFORM_VOLUME = "com.autonion.automationcompanion.ACTION_PERFORM_VOLUME"

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

        fun startAll(context: Context) {
            val i = Intent(context, TrackingForegroundService::class.java).apply {
                action = ACTION_START_ALL
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

        fun startVolumeChange(context: Context, ring: Int, media: Int, alarm: Int, ringerMode: RingerMode) {
            val i = Intent(context, TrackingForegroundService::class.java).apply {
                action = ACTION_PERFORM_VOLUME
                putExtra("ring", ring)
                putExtra("media", media)
                putExtra("alarm", alarm)
                putExtra("ringerMode", ringerMode.ordinal) // Store as ordinal
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
        fun refreshAll(context: Context) {
            stop(context)
            start(context)
        }

        /**
         * Acquire partial wake lock to keep screen awake.
         * Used by Keep Screen Awake Display action.
         * Graceful: If WAKE_LOCK permission not granted, logs warning but does not crash.
         */
        fun acquirePartialWakeLock(context: Context) {
            try {
                val i = Intent(context, TrackingForegroundService::class.java).apply {
                    action = "ACTION_ACQUIRE_WAKE_LOCK"
                }
                ContextCompat.startForegroundService(context, i)
                Log.i("TrackingService", "Partial wake lock acquire requested")
            } catch (e: Exception) {
                Log.w("TrackingService", "Failed to acquire wake lock", e)
            }
        }

        /**
         * Release partial wake lock to allow normal sleep.
         * Used by Keep Screen Awake Display action.
         */
        fun releasePartialWakeLock(context: Context) {
            try {
                val i = Intent(context, TrackingForegroundService::class.java).apply {
                    action = "ACTION_RELEASE_WAKE_LOCK"
                }
                context.startService(i)
                Log.i("TrackingService", "Partial wake lock release requested")
            } catch (e: Exception) {
                Log.w("TrackingService", "Failed to release wake lock", e)
            }
        }
    }

    private lateinit var geofencingClient: GeofencingClient
    private var partialWakeLock: PowerManager.WakeLock? = null
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


        // 🔴 MUST BE FIRST – NO CONDITIONS
        startForeground(1, buildNotification())

        val action = intent?.action
        val slotId = intent?.getLongExtra("slotId", -1L) ?: -1L
        Log.i("TrackingService", "onStartCommand action=${intent?.action} slotId=$slotId")

        when (action) {

            ACTION_START_FOR_SLOT -> {
                if (slotId != -1L) {
                    registerGeofenceForSlot(slotId)
                }
            }

            ACTION_START_ALL -> {
                registerAllEnabledSlots()
            }

            ACTION_PERFORM_VOLUME -> {
                val ring = intent?.getIntExtra("ring", -1) ?: -1
                val media = intent?.getIntExtra("media", -1) ?: -1
                val alarm = intent?.getIntExtra("alarm", 8) ?: 8
                val ringerModeOrdinal = intent?.getIntExtra("ringerMode", RingerMode.NORMAL.ordinal) ?: RingerMode.NORMAL.ordinal
                val ringerMode = RingerMode.values().getOrElse(ringerModeOrdinal) { RingerMode.NORMAL }
                if (ring != -1 && media != -1) {
                    performVolumeChange(ring, media,alarm, ringerMode)
                } else {
                    Log.w("TrackingService", "Invalid volume extras: ring=$ring media=$media")
                }
            }

            "ACTION_ACQUIRE_WAKE_LOCK" -> {
                acquireWakeLock()
            }

            "ACTION_RELEASE_WAKE_LOCK" -> {
                releaseWakeLock()
            }

            ACTION_STOP_FOR_SLOT -> {
                if (slotId != -1L) {
                    unregisterGeofenceForSlot(slotId)
                }
                stopForeground(true)
                stopSelf()
            }
        }


        return START_STICKY
    }

    private fun registerAllEnabledSlots() {
        CoroutineScope(Dispatchers.IO).launch {

            val dao = AppDatabase.get(applicationContext).slotDao()
            val slots = dao.getAllEnabled() // we’ll add this query

            Log.i("LocDebug", "Registering ${slots.size} enabled slots")

            slots.forEach { slot ->
                registerGeofenceForSlot(slot.id)
            }
        }
    }


    private fun shouldActivateToday(slot: Slot): Boolean {
        if (slot.activeDays == "ALL") return true

        val today = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "MON"
            Calendar.TUESDAY -> "TUE"
            Calendar.WEDNESDAY -> "WED"
            Calendar.THURSDAY -> "THU"
            Calendar.FRIDAY -> "FRI"
            Calendar.SATURDAY -> "SAT"
            Calendar.SUNDAY -> "SUN"
            else -> return false
        }

        return slot.activeDays.split(",").contains(today)
    }

    private fun registerGeofenceForSlot(slotId: Long) {
        CoroutineScope(Dispatchers.IO).launch {

            val db = AppDatabase.get(applicationContext)
            val slot = db.slotDao().getById(slotId)

            // 1️⃣ Slot must exist
            if (slot == null) {
                Log.w("LocDebug", "registerGeofenceForSlot: slot null for id=$slotId")
                return@launch
            }

            // 🔁 IMPORTANT: clear any previous geofence for this slot
            geofencingClient.removeGeofences(listOf(slot.id.toString()))
            Log.i("LocDebug", "Cleared existing geofence for slot ${slot.id}")

            // 2️⃣ Day-of-week filter (CRITICAL)
            if (!shouldActivateToday(slot)) {
                Log.i(
                    "LocDebug",
                    "Skipping geofence for slot ${slot.id} (today not allowed: ${slot.activeDays})"
                )
                return@launch
            }

            // 3️⃣ Time window filter (CRITICAL)
            val now = System.currentTimeMillis()
            val startMillis = slot.startMillis ?: return@launch
            val endMillis = slot.endMillis ?: return@launch
            if (now < startMillis || now > endMillis) {
                Log.i(
                    "LocDebug",
                    "Skipping geofence for slot ${slot.id} (outside time window: now=$now, start=$startMillis, end=$endMillis)"
                )
                return@launch
            }

            // 4️⃣ Location permission check
            if (
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w("LocDebug", "registerGeofenceForSlot: missing ACCESS_FINE_LOCATION")
                FallbackFlow.showEnableLocationNotification(applicationContext)
                return@launch
            }

            // 5️⃣ Build geofence
            val lat = slot.lat ?: return@launch
            val lng = slot.lng ?: return@launch
            val radiusMeters = slot.radiusMeters ?: return@launch
            
            val geofence = Geofence.Builder()
                .setRequestId(slot.id.toString())
                .setCircularRegion(
                    lat,
                    lng,
                    radiusMeters
                )
                .setExpirationDuration(
                    max(0L, endMillis - System.currentTimeMillis())
                )
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()

            val geofenceRequest = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofence(geofence)
                .build()

            // IMPORTANT: component-only intent
            val intent = Intent(applicationContext, GeofenceBroadcastReceiver::class.java)
            val requestCode = slot.id.toInt()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pendingIntent =
                PendingIntent.getBroadcast(applicationContext, requestCode, intent, flags)

            try {
                Log.i(
                    "LocDebug",
                    "registerGeofenceForSlot called for slotId=${slot.id} " +
                            "(lat=${slot.lat}, lng=${slot.lng}, radius=${slot.radiusMeters})"
                )

                geofencingClient.addGeofences(geofenceRequest, pendingIntent)
                    .addOnSuccessListener {

                        Log.i("LocDebug", "Geofence added for slot ${slot.id}")

                        // 6️⃣ Immediate proximity check (WITH day + time guard)
                        val now2 = System.currentTimeMillis()
                        if (
                            shouldActivateToday(slot) &&
                            isNowWithinSlotTime(slot)

                        ) {
                            try {
                                val fused =
                                    LocationServices.getFusedLocationProviderClient(applicationContext)

                                fused.lastLocation
                                    .addOnSuccessListener { loc ->
                                        if (loc == null) {
                                            Log.i("LocDebug", "Immediate proximity check: lastLocation null")
                                            return@addOnSuccessListener
                                        }

                                        val results = FloatArray(1)
                                        Location.distanceBetween(
                                            slot.lat,
                                            slot.lng,
                                            loc.latitude,
                                            loc.longitude,
                                            results
                                        )

                                        val dist = results[0]
                                        Log.i(
                                            "LocDebug",
                                            "Immediate proximity check: dist=$dist radius=${slot.radiusMeters}"
                                        )

                                        if (dist <= slot.radiusMeters) {
                                            SendHelper.startSendIfNeeded(
                                                applicationContext,
                                                slot.id
                                            )
                                        }
                                    }

                            } catch (_: SecurityException) {
                                Log.w(
                                    "LocDebug",
                                    "Immediate proximity check skipped: missing permission"
                                )
                            }
                        }
                    }
                    .addOnFailureListener { ex ->
                        Log.e(
                            "LocDebug",
                            "Failed to add geofence for slot ${slot.id}: ${ex.message}",
                            ex
                        )
                        FallbackFlow.showEnableLocationNotification(applicationContext)
                    }

            } catch (e: SecurityException) {
                Log.e(
                    "LocDebug",
                    "SecurityException when adding geofence for slot ${slot.id}",
                    e
                )
                FallbackFlow.showEnableLocationNotification(applicationContext)
            }
        }
    }

    private fun isNowWithinSlotTime(slot: Slot): Boolean {
        val now = Calendar.getInstance()

        val startMillis = slot.startMillis ?: return false
        val endMillis = slot.endMillis ?: return false

        val start = Calendar.getInstance().apply {
            timeInMillis = startMillis
            set(Calendar.YEAR, now.get(Calendar.YEAR))
            set(Calendar.MONTH, now.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH))
        }

        val end = Calendar.getInstance().apply {
            timeInMillis = endMillis
            set(Calendar.YEAR, now.get(Calendar.YEAR))
            set(Calendar.MONTH, now.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH))
        }

        // Overnight window support
        if (end.before(start)) {
            end.add(Calendar.DATE, 1)
        }

        return now.timeInMillis in start.timeInMillis..end.timeInMillis
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

    // Perform volume change from the foreground service context to satisfy stricter OEM policies
    // Realme ROM workaround: pre-occupy media focus with silent audio before setStreamVolume
    private fun performVolumeChange(ring: Int, media: Int, alarm: Int, ringerMode: RingerMode) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

            val ringMax = am.getStreamMaxVolume(android.media.AudioManager.STREAM_RING)
            val mediaMax = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val alarmMax = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)

            val ringVolume = ring.coerceIn(0, ringMax)
            val mediaVolume = media.coerceIn(0, mediaMax)
            val alarmVolume = alarm.coerceIn(0, alarmMax)

            // Set ringer mode
            when (ringerMode) {
                RingerMode.NORMAL -> {
                    am.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                }
                RingerMode.VIBRATE -> {
                    am.ringerMode = android.media.AudioManager.RINGER_MODE_VIBRATE
                }
                RingerMode.SILENT -> {
                    am.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                }
            }

            // ============ REALME FIX: Pre-occupy media focus with silent audio ============
            try {
                val silentAudio = android.media.AudioTrack(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build(),
                    android.media.AudioFormat.Builder()
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(44100)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    android.media.AudioTrack.getMinBufferSize(44100, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT),
                    android.media.AudioTrack.MODE_STREAM,
                    android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                silentAudio.play()
                Log.i("TrackingService", "Realme workaround: playing silent audio to pre-occupy media focus")
                Thread.sleep(100)
                silentAudio.stop()
                silentAudio.release()
            } catch (e: Exception) {
                Log.w("TrackingService", "Realme workaround failed (non-fatal): ${e.message}")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val afAttr = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

                val focusRequest = android.media.AudioFocusRequest.Builder(
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                ).setAudioAttributes(afAttr)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* no-op */ }
                    .build()

                val focusResult = am.requestAudioFocus(focusRequest)

                if (focusResult == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    am.setStreamVolume(android.media.AudioManager.STREAM_RING, ringVolume, android.media.AudioManager.FLAG_SHOW_UI)
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, mediaVolume, android.media.AudioManager.FLAG_SHOW_UI)
                    am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, alarmVolume, android.media.AudioManager.FLAG_SHOW_UI)
                    am.abandonAudioFocusRequest(focusRequest)
                    Log.i("TrackingService", "Foreground volume set - Ring: $ringVolume/$ringMax, Media: $mediaVolume/$mediaMax, Alarm: $alarmVolume/$alarmMax, RingerMode: $ringerMode")
                } else {
                    am.setStreamVolume(android.media.AudioManager.STREAM_RING, ringVolume, android.media.AudioManager.FLAG_SHOW_UI)
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, mediaVolume, android.media.AudioManager.FLAG_SHOW_UI)
                    am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, alarmVolume, android.media.AudioManager.FLAG_SHOW_UI)
                    Log.w("TrackingService", "Foreground audio focus not granted; attempted setStreamVolume")
                }
            } else {
                val afListener = android.media.AudioManager.OnAudioFocusChangeListener { }
                val focusResult = am.requestAudioFocus(afListener, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                if (focusResult == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    am.setStreamVolume(android.media.AudioManager.STREAM_RING, ringVolume, android.media.AudioManager.FLAG_SHOW_UI)
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, mediaVolume, android.media.AudioManager.FLAG_SHOW_UI)
                    am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, alarmVolume, android.media.AudioManager.FLAG_SHOW_UI)
                    am.abandonAudioFocus(afListener)
                    Log.i("TrackingService", "Foreground volume set - Ring: $ringVolume/$ringMax, Media: $mediaVolume/$mediaMax, Alarm: $alarmVolume/$alarmMax, RingerMode: $ringerMode")
                } else {
                    am.setStreamVolume(android.media.AudioManager.STREAM_RING, ringVolume, android.media.AudioManager.FLAG_SHOW_UI)
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, mediaVolume, android.media.AudioManager.FLAG_SHOW_UI)
                    am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, alarmVolume, android.media.AudioManager.FLAG_SHOW_UI)
                    Log.w("TrackingService", "Foreground audio focus not granted; attempted setStreamVolume")
                }
            }
        } catch (t: Throwable) {
            Log.e("TrackingService", "Failed to perform foreground volume change", t)
        }
    }

    /**
     * Acquire wake lock to keep screen awake.
     * Used by Keep Screen Awake Display action.
     * Using SCREEN_BRIGHT_WAKE_LOCK to actually keep the screen on (not just CPU).
     */
    @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK is deprecated but still necessary for this use case
    private fun acquireWakeLock() {
        try {
            if (partialWakeLock?.isHeld == true) {
                Log.w("TrackingService", "Wake lock already held")
                return
            }

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            // Use SCREEN_BRIGHT_WAKE_LOCK to keep screen on, or SCREEN_DIM_WAKE_LOCK for dimmed screen
            partialWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "automationcompanion:keep_screen_awake"
            ).apply {
                acquire()
                Log.i("TrackingService", "Screen wake lock acquired")
            }
        } catch (e: Exception) {
            Log.w("TrackingService", "Failed to acquire wake lock", e)
        }
    }

    /**
     * Release wake lock to allow normal sleep.
     * Used by Keep Screen Awake Display action.
     */
    private fun releaseWakeLock() {
        try {
            if (partialWakeLock?.isHeld == true) {
                partialWakeLock?.release()
                Log.i("TrackingService", "Screen wake lock released")
            }
        } catch (e: Exception) {
            Log.w("TrackingService", "Failed to release wake lock", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up wake lock on service destruction
        releaseWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
