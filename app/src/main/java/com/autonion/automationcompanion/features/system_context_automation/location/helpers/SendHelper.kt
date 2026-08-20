package com.autonion.automationcompanion.features.system_context_automation.location.helpers

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.autonion.automationcompanion.automation.actions.models.AppActionType
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.automation.actions.models.AutomationAction
import com.autonion.automationcompanion.automation.actions.models.NotificationType
import com.autonion.automationcompanion.automation.actions.receivers.DelayedNotificationReceiver
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.TrackingForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SendHelper {

    private const val TAG = "SendHelper"
    private const val SUCCESS_CHANNEL_ID = "automation_success"

    /**
     * ENTRY POINT — called ONLY after all checks pass
     * (TimeTickReceiver / GeofenceReceiver)
     */
    fun startSendIfNeeded(context: Context, slotId: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            executeSlotActions(context, slotId)
        }
    }

    private suspend fun executeSlotActions(context: Context, slotId: Long) {
        val dao = AppDatabase.get(context).slotDao()
        val slot = dao.getById(slotId) ?: return

        if (!slot.enabled) return

        DebugLogger.info(
            context, LogCategory.SYSTEM_CONTEXT,
            "Executing slot $slotId",
            "Running ${slot.actions.size} configured actions",
            TAG
        )

        ensureChannel(context)

        val canSendSms =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.SEND_SMS
            ) == PackageManager.PERMISSION_GRANTED

        val canWriteSettings = Settings.System.canWrite(context)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val canAccessDnd = nm.isNotificationPolicyAccessGranted

        slot.actions.forEach { action ->
            when (action) {

                is AutomationAction.SendSms -> {
                    if (!canSendSms) {
                        Log.w(TAG, "SEND_SMS not granted, skipping SMS")
                        DebugLogger.warning(
                            context, LogCategory.SYSTEM_CONTEXT,
                            "SMS skipped",
                            "SEND_SMS permission not granted",
                            TAG
                        )
                        return@forEach
                    }

                    action.contactsCsv
                        .split(";")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { number ->
                            SmsManager.getDefault()
                                .sendTextMessage(
                                    number,
                                    null,
                                    action.message,
                                    null,
                                    null
                                )

                            Log.i(TAG, "SMS sent to $number")
                            notifySuccess(context, "Message sent to $number")
                        }
                }

                is AutomationAction.SetVolume -> {
                    executeSetVolume(context, action)
                }

                is AutomationAction.SetBrightness -> {
                    if (!canWriteSettings) {
                        Log.w(TAG, "WRITE_SETTINGS not granted, skipping brightness")
                        DebugLogger.warning(
                            context, LogCategory.SYSTEM_CONTEXT,
                            "Brightness skipped",
                            "WRITE_SETTINGS permission not granted",
                            TAG
                        )
                        return@forEach
                    }
                    executeSetBrightness(context, action)
                }

                is AutomationAction.SetDnd -> {
                    if (!canAccessDnd) {
                        Log.w(TAG, "ACCESS_NOTIFICATION_POLICY not granted, skipping DND")
                        DebugLogger.warning(
                            context, LogCategory.SYSTEM_CONTEXT,
                            "DND skipped",
                            "ACCESS_NOTIFICATION_POLICY (DND) permission not granted",
                            TAG
                        )
                        return@forEach
                    }
                    executeSetDnd(context, action)
                }

                // ───────────── Display Actions ─────────────

                is AutomationAction.SetAutoRotate -> {
                    if (!canWriteSettings) {
                        Log.w(TAG, "WRITE_SETTINGS not granted, skipping auto-rotate")
                        DebugLogger.warning(
                            context, LogCategory.SYSTEM_CONTEXT,
                            "Auto-rotate skipped",
                            "WRITE_SETTINGS permission not granted",
                            TAG
                        )
                        return@forEach
                    }
                    executeSetAutoRotate(context, action)
                }

                is AutomationAction.SetScreenTimeout -> {
                    if (!canWriteSettings) {
                        Log.w(TAG, "WRITE_SETTINGS not granted, skipping screen timeout")
                        DebugLogger.warning(
                            context, LogCategory.SYSTEM_CONTEXT,
                            "Screen timeout skipped",
                            "WRITE_SETTINGS permission not granted",
                            TAG
                        )
                        return@forEach
                    }
                    executeSetScreenTimeout(context, action)
                }

                is AutomationAction.SetKeepScreenAwake -> {
                    executeSetKeepScreenAwake(context, action)
                }

                is AutomationAction.AppAction -> {
                    executeAppAction(context, action)
                }

                is AutomationAction.NotificationAction -> {
                    executeNotificationAction(context, action)
                }

                is AutomationAction.SetBatterySaver -> {
                    if (!canWriteSettings) {
                        Log.w(TAG, "WRITE_SETTINGS not granted, skipping battery saver")
                        DebugLogger.warning(
                            context, LogCategory.SYSTEM_CONTEXT,
                            "Battery saver skipped",
                            "WRITE_SETTINGS permission not granted",
                            TAG
                        )
                        return@forEach
                    }
                    executeSetBatterySaver(context, action)
                }
            }
        }
    }

    // ───────────────── Volume ─────────────────

    private fun executeSetVolume(context: Context, action: AutomationAction.SetVolume) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

            val ringMax = am.getStreamMaxVolume(android.media.AudioManager.STREAM_RING)
            val mediaMax = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val alarmMax = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)

            val ringVolume = action.ring.coerceIn(0, ringMax)
            val mediaVolume = action.media.coerceIn(0, mediaMax)
            val alarmVolume = action.alarm.coerceIn(0, alarmMax)

            // Set ringer mode if DND access allows it
            try {
                when (action.ringerMode) {
                    com.autonion.automationcompanion.automation.actions.models.RingerMode.NORMAL -> {
                        am.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                    }
                    com.autonion.automationcompanion.automation.actions.models.RingerMode.VIBRATE -> {
                        am.ringerMode = android.media.AudioManager.RINGER_MODE_VIBRATE
                    }
                    com.autonion.automationcompanion.automation.actions.models.RingerMode.SILENT -> {
                        am.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                    }
                }
            } catch (se: SecurityException) {
                Log.w(TAG, "Could not set ringer mode (ACCESS_NOTIFICATION_POLICY may be needed): ${se.message}")
            }

            // Realme / ColorOS / Android 10+ workaround: brief audio track to ensure media focus
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
                Thread.sleep(50)
                silentAudio.stop()
                silentAudio.release()
            } catch (e: Exception) {
                Log.w(TAG, "Silent audio focus trick failed (non-fatal): ${e.message}")
            }

            // Request Audio Focus before adjusting background media volume
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
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

                am.requestAudioFocus(focusRequest)

                am.setStreamVolume(android.media.AudioManager.STREAM_RING, ringVolume, android.media.AudioManager.FLAG_SHOW_UI)
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, mediaVolume, android.media.AudioManager.FLAG_SHOW_UI)
                am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, alarmVolume, android.media.AudioManager.FLAG_SHOW_UI)

                if (mediaVolume == 0) {
                    try {
                        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_MUTE, 0)
                    } catch (_: Exception) {}
                } else {
                    try {
                        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0)
                    } catch (_: Exception) {}
                }

                am.abandonAudioFocusRequest(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                val afListener = android.media.AudioManager.OnAudioFocusChangeListener { }
                @Suppress("DEPRECATION")
                am.requestAudioFocus(afListener, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)

                am.setStreamVolume(android.media.AudioManager.STREAM_RING, ringVolume, android.media.AudioManager.FLAG_SHOW_UI)
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, mediaVolume, android.media.AudioManager.FLAG_SHOW_UI)
                am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, alarmVolume, android.media.AudioManager.FLAG_SHOW_UI)

                if (mediaVolume == 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    try {
                        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_MUTE, 0)
                    } catch (_: Exception) {}
                } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    try {
                        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0)
                    } catch (_: Exception) {}
                }

                @Suppress("DEPRECATION")
                am.abandonAudioFocus(afListener)
            }

            Log.i(TAG, "Volume set: Ring=$ringVolume, Media=$mediaVolume, Alarm=$alarmVolume, Ringer=${action.ringerMode}")
            DebugLogger.success(
                context, LogCategory.SYSTEM_CONTEXT,
                "Volume set",
                "Ring=$ringVolume, Media=$mediaVolume, Alarm=$alarmVolume, Ringer=${action.ringerMode}",
                TAG
            )
            notifySuccess(context, "Volume updated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
            DebugLogger.error(
                context, LogCategory.SYSTEM_CONTEXT,
                "Volume change failed",
                "${e.message}",
                TAG
            )
        }
    }

    // ───────────────── Brightness ─────────────────

    private fun executeSetBrightness(context: Context, action: AutomationAction.SetBrightness) {
        try {
            val brightness = action.level.coerceIn(0, 255)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness
            )
            Log.i(TAG, "Brightness set to $brightness")
            DebugLogger.success(
                context, LogCategory.SYSTEM_CONTEXT,
                "Brightness set to $brightness",
                "Screen brightness adjusted",
                TAG
            )
            notifySuccess(context, "Brightness set to $brightness")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set brightness", e)
            DebugLogger.error(
                context, LogCategory.SYSTEM_CONTEXT,
                "Brightness change failed",
                "${e.message}",
                TAG
            )
        }
    }

    // ───────────────── DND ─────────────────

    private fun executeSetDnd(context: Context, action: AutomationAction.SetDnd) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.setInterruptionFilter(
                if (action.enabled)
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else
                    NotificationManager.INTERRUPTION_FILTER_ALL
            )
            val status = if (action.enabled) "enabled (Priority)" else "disabled"
            Log.i(TAG, "DND $status")
            DebugLogger.success(
                context, LogCategory.SYSTEM_CONTEXT,
                "DND $status",
                "Do Not Disturb toggled",
                TAG
            )
            notifySuccess(context, "Do Not Disturb $status")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set DND", e)
            DebugLogger.error(
                context, LogCategory.SYSTEM_CONTEXT,
                "DND change failed",
                "${e.message}",
                TAG
            )
        }
    }

    // ───────────────── Display Actions ─────────────────

    private fun executeSetAutoRotate(context: Context, action: AutomationAction.SetAutoRotate) {
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                if (action.enabled) 1 else 0
            )
            val status = if (action.enabled) "enabled" else "disabled"
            Log.i(TAG, "Auto-rotate $status")
            notifySuccess(context, "Auto-rotate $status")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set auto-rotate", e)
        }
    }

    private fun executeSetScreenTimeout(context: Context, action: AutomationAction.SetScreenTimeout) {
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                action.durationMs
            )

            val applied = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT
            )
            Log.e("DEBUG_TIMEOUT", "Requested=${action.durationMs}, Applied=$applied")
            val durationLabel = when (action.durationMs) {
                15_000 -> "15 seconds"
                30_000 -> "30 seconds"
                60_000 -> "1 minute"
                300_000 -> "5 minutes"
                else -> "${action.durationMs}ms"
            }
            Log.i(TAG, "Screen timeout set to $durationLabel")
            notifySuccess(context, "Screen timeout set to $durationLabel")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set screen timeout", e)
        }
    }

    private fun executeSetKeepScreenAwake(context: Context, action: AutomationAction.SetKeepScreenAwake) {
        try {
            if (action.enabled) {
                WakeLockHelper.acquire(context)
                Log.i(TAG, "Keep screen awake activated")
                notifySuccess(context, "Keep screen awake activated")
            } else {
                WakeLockHelper.release()
                Log.i(TAG, "Keep screen awake deactivated")
                notifySuccess(context, "Keep screen awake deactivated")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to manage keep screen awake", e)
        }
    }

    // Add these new execution functions:

    private fun executeAppAction(context: Context, action: AutomationAction.AppAction) {
        try {
            val intent = when (action.actionType) {
                AppActionType.LAUNCH -> {
                    context.packageManager.getLaunchIntentForPackage(action.packageName)
                }
                AppActionType.INFO -> {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${action.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            }

            if (intent != null) {
                context.startActivity(intent)
                val actionType = if (action.actionType == AppActionType.LAUNCH) "launched" else "opened info for"
                Log.i(TAG, "App ${action.packageName} $actionType")
                notifySuccess(context, "App ${action.packageName} $actionType")
            } else {
                Log.w(TAG, "Could not create intent for package: ${action.packageName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute app action", e)
        }
    }

    private fun executeNotificationAction(context: Context, action: AutomationAction.NotificationAction) {
        try {
            if (action.delayMinutes > 0 && action.notificationType == NotificationType.REMINDER) {
                // Schedule delayed notification
                scheduleDelayedNotification(context, action)
                Log.i(TAG, "Scheduled delayed notification: ${action.title}")
                notifySuccess(context, "Reminder scheduled for ${action.delayMinutes} minutes")
            } else {
                // Show immediate notification
                showNotification(context, action)
                Log.i(TAG, "Notification shown: ${action.title}")
                notifySuccess(context, "Notification shown")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification", e)
        }
    }

    private fun showNotification(context: Context, action: AutomationAction.NotificationAction) {
        val channelId = when (action.notificationType) {
            NotificationType.SILENT -> "silent_channel"
            NotificationType.ONGOING -> "ongoing_channel"
            NotificationType.REMINDER -> "reminder_channel"
            else -> "default_channel"
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(action.title)
            .setContentText(action.text)
            .setAutoCancel(action.notificationType != NotificationType.ONGOING)
            .setOngoing(action.notificationType == NotificationType.ONGOING)
            .setPriority(
                when (action.notificationType) {
                    NotificationType.SILENT -> NotificationCompat.PRIORITY_LOW
                    NotificationType.ONGOING -> NotificationCompat.PRIORITY_DEFAULT
                    else -> NotificationCompat.PRIORITY_HIGH
                }
            )
            .build()

        notifyIfAllowed(context, action.title.hashCode(), notification)
    }

    // Update the scheduleDelayedNotification function to handle alarm permission:
    private fun scheduleDelayedNotification(context: Context, action: AutomationAction.NotificationAction) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager

        // Check if we can schedule exact alarms (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                // Cannot schedule exact alarms, show notification immediately
                showNotification(context, action)
                Log.w(TAG, "Cannot schedule exact alarms, showing notification immediately")
                return
            }
        }

        val triggerTime = System.currentTimeMillis() + (action.delayMinutes * 60 * 1000L)

        val intent = Intent(context, DelayedNotificationReceiver::class.java).apply {
            putExtra("title", action.title)
            putExtra("text", action.text)
            putExtra("notificationType", action.notificationType.name)
        }

        val pendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            action.title.hashCode(),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            Log.i(TAG, "Scheduled delayed notification for ${action.delayMinutes} minutes")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException when scheduling alarm: ${e.message}")
            // Fallback to immediate notification
            showNotification(context, action)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule delayed notification", e)
        }
    }
    private fun executeSetBatterySaver(context: Context, action: AutomationAction.SetBatterySaver) {
        try {
            // Check if device supports battery saver API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                // For Android 5.1+ (API 22+), we can use PowerManager
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Android 6.0+ - Try to use Settings.Global
                    try {
                        // Note: This requires WRITE_SETTINGS permission
                        android.provider.Settings.Global.putInt(
                            context.contentResolver,
                            "low_power_mode", // Use string constant for compatibility
                            if (action.enabled) 1 else 0
                        )

                        // Verify change
                        val isEnabled = android.provider.Settings.Global.getInt(
                            context.contentResolver,
                            "low_power_mode",
                            0
                        ) == 1

                        val status = if (isEnabled) "enabled" else "disabled"
                        Log.i(TAG, "Battery saver $status (attempted: ${if (action.enabled) "enable" else "disable"})")
                        notifySuccess(context, "Battery saver $status")
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Permission denied for battery saver", e)
                        notifySuccess(context, "Permission denied for battery saver")
                    }
                } else {
                    // Android 5.1 - 5.1.1
                    // Some devices might not support battery saver control
                    Log.w(TAG, "Battery saver control may not be fully supported on this device")
                    notifySuccess(context, "Battery saver control may be limited on this device")
                }
            } else {
                // Pre-Lollipop: Show toast that it's not supported
                Log.w(TAG, "Battery saver API not available on this device")
                notifySuccess(context, "Battery saver control not supported on this device")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set battery saver", e)
        }
    }

    // ---------- Notifications ----------

    private fun notifySuccess(context: Context, message: String) {
        val notification = NotificationCompat.Builder(context, SUCCESS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Automation executed")
            .setContentText(message)
            .setAutoCancel(true)
            .build()

        notifyIfAllowed(context, message.hashCode(), notification)
    }

    private fun notifyIfAllowed(
        context: Context,
        id: Int,
        notification: Notification
    ) {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context)
                .notify(id, notification)
        }
    }

    // Also update the ensureChannel function to create notification channels for new notification types:
    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)

            // Create success channel
            if (nm.getNotificationChannel(SUCCESS_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        SUCCESS_CHANNEL_ID,
                        "Automation Success",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }

            // Create notification type channels
            listOf(
                "default_channel" to "Default Notifications",
                "silent_channel" to "Silent Notifications",
                "ongoing_channel" to "Ongoing Notifications",
                "reminder_channel" to "Reminder Notifications"
            ).forEach { (channelId, channelName) ->
                if (nm.getNotificationChannel(channelId) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            channelId,
                            channelName,
                            when (channelId) {
                                "silent_channel" -> NotificationManager.IMPORTANCE_LOW
                                else -> NotificationManager.IMPORTANCE_HIGH
                            }
                        )
                    )
                }
            }
        }
    }
}
