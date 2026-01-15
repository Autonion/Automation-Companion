//package com.autonion.automationcompanion.features.system_context_automation.location.executor
//
//import android.Manifest
//import android.app.Notification
//import android.content.Context
//import android.app.NotificationManager
//import android.content.Intent
//import android.content.pm.PackageManager
//import android.os.Build
//import android.provider.Settings
//import android.telephony.SmsManager
//import android.util.Log
//import androidx.core.app.NotificationCompat
//import androidx.core.app.NotificationManagerCompat
//import androidx.core.content.ContextCompat
//import com.autonion.automationcompanion.features.automation.actions.models.AppActionType
//import com.autonion.automationcompanion.features.automation.actions.models.AutomationAction
//import com.autonion.automationcompanion.features.automation.actions.models.NotificationType
//import com.autonion.automationcompanion.features.automation.actions.receivers.DelayedNotificationReceiver
//import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.TrackingForegroundService
//
//class ActionExecutor(
//    private val context: Context
//) {
//
//    companion object {
//        private const val TAG = "ActionExecutor"
//        private const val SUCCESS_CHANNEL_ID = "automation_success"
//
//    }
//
//
//    fun execute(actions: List<AutomationAction>) {
//        val canWriteSettings = Settings.System.canWrite(context)
//        actions.forEach { action ->
//            try {
//                when (action) {
//                    is AutomationAction.SendSms -> executeSendSms(action)
//                    is AutomationAction.SetVolume -> executeSetVolume(action)
//                    is AutomationAction.SetBrightness -> executeSetBrightness(action)
//                    is AutomationAction.SetDnd -> executeSetDnd(action)
//                    // ───────────── Display Actions ─────────────
//                    is AutomationAction.SetAutoRotate -> executeSetAutoRotate(action)
//                    is AutomationAction.SetScreenTimeout -> executeSetScreenTimeout(action)
//                    is AutomationAction.SetKeepScreenAwake -> executeSetKeepScreenAwake(action)
//
//                    is AutomationAction.AppAction -> {
//                        executeAppAction(context, action)
//                    }
//
//                    is AutomationAction.NotificationAction -> {
//                        executeNotificationAction(context, action)
//                    }
//
//                    is AutomationAction.SetBatterySaver -> {
//                        if (!canWriteSettings) {
//                            Log.w(TAG, "WRITE_SETTINGS not granted, skipping battery saver")
//                            return@forEach
//                        }
//                        executeSetBatterySaver(context, action)
//                    }
//                }
//            } catch (t: Throwable) {
//                Log.e("ActionExecutor", "Failed to execute $action", t)
//            }
//        }
//    }
//
//    // ───────────────── SMS ─────────────────
//
//    private fun executeSendSms(action: AutomationAction.SendSms) {
//        val smsManager = SmsManager.getDefault()
//        val numbers = action.contactsCsv
//            .split(",")
//            .map { it.trim() }
//            .filter { it.isNotEmpty() }
//
//        numbers.forEach { number ->
//            smsManager.sendTextMessage(
//                number,
//                null,
//                action.message,
//                null,
//                null
//            )
//        }
//    }
//
//    // ───────────────── Volume ─────────────────
//
//    private fun executeSetVolume(context: Context, action: AutomationAction.SetVolume) {
//        try {
//            // Delegate to foreground service
//            TrackingForegroundService.startVolumeChange(
//                context,
//                action.ring,
//                action.media,
//                action.alarm,
//                action.ringerMode
//            )
//            Log.i(TAG, "Delegated volume change with ringer mode: ${action.ringerMode}")
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to delegate volume change", e)
//        }
//    }
//
//    // ───────────────── Brightness ─────────────────
//
//    private fun executeSetBrightness(action: AutomationAction.SetBrightness) {
//        if (!Settings.System.canWrite(context)) {
//            Log.w("ActionExecutor", "WRITE_SETTINGS not granted")
//            return
//        }
//
//        Settings.System.putInt(
//            context.contentResolver,
//            Settings.System.SCREEN_BRIGHTNESS,
//            action.level.coerceIn(10, 255)
//        )
//    }
//
//    // ───────────────── DND ─────────────────
//
//    private fun executeSetDnd(action: AutomationAction.SetDnd) {
//        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//
//        if (!nm.isNotificationPolicyAccessGranted) {
//            Log.w("ActionExecutor", "DND access not granted")
//            return
//        }
//
//        nm.setInterruptionFilter(
//            if (action.enabled)
//                NotificationManager.INTERRUPTION_FILTER_NONE
//            else
//                NotificationManager.INTERRUPTION_FILTER_ALL
//        )
//    }
//
//    // ───────────────── Display Actions ─────────────────
//
//
//    /**
//     * Set Auto-rotate on/off.
//     * Non-root implementation: Uses Settings.System ACCELEROMETER_ROTATION (global setting).
//     * Graceful: If permission denied, logs warning but does not crash.
//     */
//    private fun executeSetAutoRotate(action: AutomationAction.SetAutoRotate) {
//        if (!Settings.System.canWrite(context)) {
//            Log.w("ActionExecutor", "WRITE_SETTINGS not granted for auto-rotate")
//            return
//        }
//
//        try {
//            Settings.System.putInt(
//                context.contentResolver,
//                Settings.System.ACCELEROMETER_ROTATION,
//                if (action.enabled) 1 else 0
//            )
//            Log.i("ActionExecutor", "Set auto-rotate: ${action.enabled}")
//        } catch (e: Exception) {
//            Log.w("ActionExecutor", "Failed to set auto-rotate", e)
//        }
//    }
//
//    /**
//     * Set screen timeout duration.
//     * Non-root implementation: Uses Settings.System SCREEN_OFF_TIMEOUT (global setting, in milliseconds).
//     * Graceful: If permission denied, logs warning but does not crash.
//     */
//    private fun executeSetScreenTimeout(action: AutomationAction.SetScreenTimeout) {
//        if (!Settings.System.canWrite(context)) {
//            Log.w("ActionExecutor", "WRITE_SETTINGS not granted for screen timeout")
//            return
//        }
//
//        try {
//            Settings.System.putInt(
//                context.contentResolver,
//                Settings.System.SCREEN_OFF_TIMEOUT,
//                action.durationMs
//            )
//            Log.i("ActionExecutor", "Set screen timeout: ${action.durationMs}ms")
//        } catch (e: Exception) {
//            Log.w("ActionExecutor", "Failed to set screen timeout", e)
//        }
//    }
//
//
//    /**
//     * Set Keep Screen Awake during active trigger.
//     * Non-root implementation: Acquires/releases partial wake lock via foreground service.
//     * Duration: Active while trigger is active; released when trigger deactivates.
//     * Graceful: If permission denied, logs warning; wake lock simply won't be acquired.
//     */
//    private fun executeSetKeepScreenAwake(action: AutomationAction.SetKeepScreenAwake) {
//        try {
//            if (action.enabled) {
//                // Delegate to foreground service to acquire partial wake lock
//                TrackingForegroundService.acquirePartialWakeLock(context)
//                Log.i("ActionExecutor", "Keep screen awake: ACTIVATED")
//            } else {
//                // Delegate to foreground service to release partial wake lock
//                TrackingForegroundService.releasePartialWakeLock(context)
//                Log.i("ActionExecutor", "Keep screen awake: DEACTIVATED")
//            }
//        } catch (e: Exception) {
//            Log.w("ActionExecutor", "Failed to manage keep screen awake", e)
//        }
//    }
//
//
//    // Add these new execution functions:
//
//    private fun executeAppAction(context: Context, action: AutomationAction.AppAction) {
//        try {
//            val intent = when (action.actionType) {
//                AppActionType.LAUNCH -> {
//                    context.packageManager.getLaunchIntentForPackage(action.packageName)
//                }
//                AppActionType.INFO -> {
//                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
//                        data = android.net.Uri.parse("package:${action.packageName}")
//                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//                    }
//                }
//            }
//
//            if (intent != null) {
//                context.startActivity(intent)
//                val actionType = if (action.actionType == AppActionType.LAUNCH) "launched" else "opened info for"
//                Log.i(TAG, "App ${action.packageName} $actionType")
//                notifySuccess(context, "App ${action.packageName} $actionType")
//            } else {
//                Log.w(TAG, "Could not create intent for package: ${action.packageName}")
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to execute app action", e)
//        }
//    }
//
//    private fun executeNotificationAction(context: Context, action: AutomationAction.NotificationAction) {
//        try {
//            if (action.delayMinutes > 0 && action.notificationType == NotificationType.REMINDER) {
//                // Schedule delayed notification
//                scheduleDelayedNotification(context, action)
//                Log.i(TAG, "Scheduled delayed notification: ${action.title}")
//                notifySuccess(context, "Reminder scheduled for ${action.delayMinutes} minutes")
//            } else {
//                // Show immediate notification
//                showNotification(context, action)
//                Log.i(TAG, "Notification shown: ${action.title}")
//                notifySuccess(context, "Notification shown")
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to show notification", e)
//        }
//    }
//
//    private fun showNotification(context: Context, action: AutomationAction.NotificationAction) {
//        val channelId = when (action.notificationType) {
//            NotificationType.SILENT -> "silent_channel"
//            NotificationType.ONGOING -> "ongoing_channel"
//            NotificationType.REMINDER -> "reminder_channel"
//            else -> "default_channel"
//        }
//
//        val notification = NotificationCompat.Builder(context, channelId)
//            .setSmallIcon(android.R.drawable.ic_dialog_info)
//            .setContentTitle(action.title)
//            .setContentText(action.text)
//            .setAutoCancel(action.notificationType != NotificationType.ONGOING)
//            .setOngoing(action.notificationType == NotificationType.ONGOING)
//            .setPriority(
//                when (action.notificationType) {
//                    NotificationType.SILENT -> NotificationCompat.PRIORITY_LOW
//                    NotificationType.ONGOING -> NotificationCompat.PRIORITY_DEFAULT
//                    else -> NotificationCompat.PRIORITY_HIGH
//                }
//            )
//            .build()
//
//        notifyIfAllowed(context, action.title.hashCode(), notification)
//    }
//
//    // Update the scheduleDelayedNotification function to handle alarm permission:
//    private fun scheduleDelayedNotification(context: Context, action: AutomationAction.NotificationAction) {
//        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
//
//        // Check if we can schedule exact alarms (Android 12+)
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//            if (!alarmManager.canScheduleExactAlarms()) {
//                // Cannot schedule exact alarms, show notification immediately
//                showNotification(context, action)
//                Log.w(TAG, "Cannot schedule exact alarms, showing notification immediately")
//                return
//            }
//        }
//
//        val triggerTime = System.currentTimeMillis() + (action.delayMinutes * 60 * 1000L)
//
//        val intent = Intent(context, DelayedNotificationReceiver::class.java).apply {
//            putExtra("title", action.title)
//            putExtra("text", action.text)
//            putExtra("notificationType", action.notificationType.name)
//        }
//
//        val pendingIntent = android.app.PendingIntent.getBroadcast(
//            context,
//            action.title.hashCode(),
//            intent,
//            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
//        )
//
//        try {
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//                alarmManager.setExactAndAllowWhileIdle(
//                    android.app.AlarmManager.RTC_WAKEUP,
//                    triggerTime,
//                    pendingIntent
//                )
//            } else {
//                alarmManager.setExact(
//                    android.app.AlarmManager.RTC_WAKEUP,
//                    triggerTime,
//                    pendingIntent
//                )
//            }
//            Log.i(TAG, "Scheduled delayed notification for ${action.delayMinutes} minutes")
//        } catch (e: SecurityException) {
//            Log.e(TAG, "SecurityException when scheduling alarm: ${e.message}")
//            // Fallback to immediate notification
//            showNotification(context, action)
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to schedule delayed notification", e)
//        }
//    }
//    private fun executeSetBatterySaver(context: Context, action: AutomationAction.SetBatterySaver) {
//        try {
//            // Check if device supports battery saver API
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
//                // For Android 5.1+ (API 22+), we can use PowerManager
//                val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
//
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//                    // Android 6.0+ - Try to use Settings.Global
//                    try {
//                        // Note: This requires WRITE_SETTINGS permission
//                        android.provider.Settings.Global.putInt(
//                            context.contentResolver,
//                            "low_power_mode", // Use string constant for compatibility
//                            if (action.enabled) 1 else 0
//                        )
//
//                        // Verify change
//                        val isEnabled = android.provider.Settings.Global.getInt(
//                            context.contentResolver,
//                            "low_power_mode",
//                            0
//                        ) == 1
//
//                        val status = if (isEnabled) "enabled" else "disabled"
//                        Log.i(TAG, "Battery saver $status (attempted: ${if (action.enabled) "enable" else "disable"})")
//                        notifySuccess(context, "Battery saver $status")
//                    } catch (e: SecurityException) {
//                        Log.e(TAG, "Permission denied for battery saver", e)
//                        notifySuccess(context, "Permission denied for battery saver")
//                    }
//                } else {
//                    // Android 5.1 - 5.1.1
//                    // Some devices might not support battery saver control
//                    Log.w(TAG, "Battery saver control may not be fully supported on this device")
//                    notifySuccess(context, "Battery saver control may be limited on this device")
//                }
//            } else {
//                // Pre-Lollipop: Show toast that it's not supported
//                Log.w(TAG, "Battery saver API not available on this device")
//                notifySuccess(context, "Battery saver control not supported on this device")
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to set battery saver", e)
//        }
//    }
//
//    private fun notifySuccess(context: Context, message: String) {
//        val notification = NotificationCompat.Builder(context, SUCCESS_CHANNEL_ID)
//            .setSmallIcon(android.R.drawable.ic_dialog_info)
//            .setContentTitle("Automation executed")
//            .setContentText(message)
//            .setAutoCancel(true)
//            .build()
//
//        notifyIfAllowed(context, message.hashCode(), notification)
//    }
//
//    private fun notifyIfAllowed(
//        context: Context,
//        id: Int,
//        notification: Notification
//    ) {
//        if (
//            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
//            ContextCompat.checkSelfPermission(
//                context,
//                Manifest.permission.POST_NOTIFICATIONS
//            ) == PackageManager.PERMISSION_GRANTED
//        ) {
//            NotificationManagerCompat.from(context)
//                .notify(id, notification)
//        }
//    }
//}
