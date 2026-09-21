package com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.autonion.automationcompanion.R
import com.autonion.automationcompanion.automation.actions.models.RingerMode
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAutomationController
import com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Shows monitoring status; the controller owns registrations and scheduled work. */
class TrackingForegroundService : Service() {
    companion object {
        const val ACTION_START_FOR_SLOT = "com.autonion.automationcompanion.ACTION_START_SLOT"
        const val ACTION_STOP_FOR_SLOT = "com.autonion.automationcompanion.ACTION_STOP_SLOT"
        const val ACTION_START_ALL = "com.autonion.automationcompanion.ACTION_START_ALL"
        const val ACTION_PERFORM_VOLUME = "com.autonion.automationcompanion.ACTION_PERFORM_VOLUME"
        private const val CHANNEL_ID = "automationcompanion_tracking"
        private const val NOTIFICATION_ID = 1
        @Volatile private var requested = false

        fun start(context: Context) = LocationAutomationController.requestReconcile(context)
        fun startAll(context: Context) = LocationAutomationController.requestReconcile(context)
        fun refreshAll(context: Context) = LocationAutomationController.requestReconcile(context, resetPresence = true)
        fun startForSlot(context: Context, slotId: Long) = LocationAutomationController.requestAlarm(context, slotId)
        fun stopIfNoSlotsRemain(context: Context) = LocationAutomationController.requestReconcile(context)
        fun stopForSlot(context: Context, slotId: Long) = LocationAutomationController.requestDisable(context, slotId)

        /** Called only after the controller has successfully reconciled enabled geofences. */
        internal fun showMonitoring(context: Context) {
            if (requested || !PermissionUtils.isLocationPermissionGranted(context)) return
            requested = true
            try {
                ContextCompat.startForegroundService(context,
                    Intent(context, TrackingForegroundService::class.java).setAction(ACTION_START_ALL))
            } catch (e: Exception) {
                requested = false
                // Play Services geofences remain functional if Android defers an FGS start.
                Log.w("TrackingService", "Monitoring notification could not start", e)
            }
        }

        /** Stopping must never create a new service (or a new foreground notification). */
        fun stop(context: Context) {
            requested = false
            context.stopService(Intent(context, TrackingForegroundService::class.java))
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }

        fun startVolumeChange(context: Context, ring: Int, media: Int, alarm: Int, ringerMode: RingerMode) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingForegroundService::class.java).apply {
                action = ACTION_PERFORM_VOLUME
                putExtra("ring", ring)
                putExtra("media", media)
                putExtra("alarm", alarm)
                putExtra("ringerMode", ringerMode.ordinal)
            })
        }

        fun acquirePartialWakeLock(context: Context) {
            ContextCompat.startForegroundService(context,
                Intent(context, TrackingForegroundService::class.java).setAction("ACTION_ACQUIRE_WAKE_LOCK"))
        }

        fun releasePartialWakeLock(context: Context) {
            if (requested) context.startService(
                Intent(context, TrackingForegroundService::class.java).setAction("ACTION_RELEASE_WAKE_LOCK"))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var partialWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        requested = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Tracking", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_FOR_SLOT) {
            val slotId = intent.getLongExtra("slotId", -1L)
            if (slotId >= 0) LocationAutomationController.requestDisable(this, slotId)
            else LocationAutomationController.requestStopAll(this)
            return START_NOT_STICKY
        }
        if (action == null || !PermissionUtils.isLocationPermissionGranted(this)) {
            stopForeground(true)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        try {
            // Meet the FGS deadline without claiming that an unchecked slot is being tracked.
            val notification = buildNotification("Checking location automations")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w("TrackingService", "Foreground service unavailable", e)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        serviceScope.launch {
            try {
                when (action) {
                    ACTION_PERFORM_VOLUME -> performVolumeChange(
                        intent.getIntExtra("ring", -1), intent.getIntExtra("media", -1),
                        intent.getIntExtra("alarm", 8),
                        RingerMode.entries.getOrElse(intent.getIntExtra("ringerMode", 0)) { RingerMode.NORMAL })
                    "ACTION_ACQUIRE_WAKE_LOCK" -> acquireWakeLock()
                    "ACTION_RELEASE_WAKE_LOCK" -> releaseWakeLock()
                }
                val count = LocationAutomationController.monitoringCount(applicationContext)
                if (count == 0 && partialWakeLock?.isHeld != true) {
                    // Validate every start, including old alarms already dispatched before deletion.
                    if (stopSelfResult(startId)) stopForeground(true)
                } else {
                    val title = if (count > 0) "Tracking active" else "Keeping screen awake"
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(title))
                }
            } catch (e: Exception) {
                Log.e("TrackingService", "Could not verify monitoring state", e)
                if (stopSelfResult(startId)) stopForeground(true)
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String): Notification {
        val stopIntent = Intent(this, StopTrackingReceiver::class.java).apply {
            action = StopTrackingReceiver.ACTION_STOP_TRACKING
        }
        val stop = PendingIntent.getBroadcast(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Location automations")
            .setSmallIcon(R.drawable.ic_location)
            .addAction(R.drawable.ic_stop, "Stop", stop)
            .setOngoing(true)
            .build()
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
        requested = false
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
