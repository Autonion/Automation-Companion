package com.autonion.automationcompanion.features.cross_device_automation.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.autonion.automationcompanion.MainActivity
import com.autonion.automationcompanion.R

/**
 * Foreground service that keeps the Hardware Remote alive during screen-off / Doze mode.
 *
 * Three mechanisms keep the process alive on aggressive OEM ROMs:
 *
 * 1. **Foreground notification** — Exempts from battery optimisation.
 * 2. **WakeLock + WifiLock** — Prevents CPU & WiFi radio suspension.
 * 3. **Silent AudioTrack** — Keeps the audio subsystem active, which prevents
 *    the OS from suspending audio/volume key dispatch and ContentObserver
 *    delivery during deep Doze. This is the same technique music players
 *    (Spotify, YouTube Music) use to maintain background playback.
 */
class HardwareRemoteForegroundService : Service() {

    companion object {
        private const val TAG = "HardwareRemoteService"
        private const val CHANNEL_ID = "hardware_remote_channel"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, HardwareRemoteForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, HardwareRemoteForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var silentAudioTrack: AudioTrack? = null
    private var silentAudioThread: Thread? = null
    @Volatile private var isPlayingSilence = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle stop action from notification
        if (intent?.action == "ACTION_STOP") {
            Log.d(TAG, "Stop action received from notification")
            HardwareButtonMapper.deactivate() // This will call stop(context) on us
            return START_NOT_STICKY
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                // Pre-Android 14: don't specify a type — specialUse doesn't exist
                // and passing a mismatched type crashes the app.
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        acquireLocks()
        startSilentAudio()
        Log.d(TAG, "Foreground service started — WiFi, CPU locks, and silent audio active")
        return START_STICKY
    }

    // ── Silent audio playback ───────────────────────────────────────────

    /**
     * Plays an inaudible audio stream to keep the audio subsystem alive.
     *
     * When the screen is off and the device enters deep Doze, the OS suspends
     * audio-related callbacks (ContentObserver, VolumeProvider) and even the
     * AccessibilityService key event dispatch pipeline. An active AudioTrack
     * prevents this suspension by making the OS treat this process as an
     * active media playback session.
     *
     * The buffer is all zeros (silence), so no sound is produced.
     */
    private fun startSilentAudio() {
        if (isPlayingSilence) return

        isPlayingSilence = true
        silentAudioThread = Thread({
            try {
                val sampleRate = 8000
                val bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                silentAudioTrack = audioTrack
                audioTrack.play()
                Log.d(TAG, "Silent audio started (keeps audio subsystem alive)")

                val silence = ByteArray(bufferSize)
                while (isPlayingSilence) {
                    audioTrack.write(silence, 0, silence.size)
                    // Small sleep to avoid burning CPU
                    Thread.sleep(500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Silent audio error: ${e.message}")
            }
        }, "SilentAudioThread").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopSilentAudio() {
        isPlayingSilence = false
        try {
            silentAudioTrack?.stop()
            silentAudioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping silent audio: ${e.message}")
        }
        silentAudioTrack = null
        silentAudioThread = null
        Log.d(TAG, "Silent audio stopped")
    }

    // ── Lock management ─────────────────────────────────────────────────

    private fun acquireLocks() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AutomationCompanion:HardwareRemoteFgWakeLock"
            )
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire()
            Log.d(TAG, "WakeLock acquired")
        }

        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "AutomationCompanion:HardwareRemoteFgWifiLock"
            )
        }
        if (wifiLock?.isHeld == false) {
            wifiLock?.acquire()
            Log.d(TAG, "WifiLock acquired")
        }
    }

    private fun releaseLocks() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "WakeLock released")
        }
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
            Log.d(TAG, "WifiLock released")
        }
    }

    // ── Notification ────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, HardwareRemoteForegroundService::class.java).apply {
            action = "ACTION_STOP"
        }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hardware Remote Active")
            .setContentText("Volume keys are controlling your desktop")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPi)
            .addAction(R.drawable.ic_close, "Stop", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hardware Remote",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the hardware remote connected while screen is off"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopSilentAudio()
        releaseLocks()
        Log.d(TAG, "Foreground service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
