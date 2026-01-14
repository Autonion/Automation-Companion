package com.autonion.automationcompanion.features.system_context_automation.location.helpers

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.automation.actions.models.AutomationAction
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
                        return@forEach
                    }
                    executeSetBrightness(context, action)
                }

                is AutomationAction.SetDnd -> {
                    if (!canAccessDnd) {
                        Log.w(TAG, "ACCESS_NOTIFICATION_POLICY not granted, skipping DND")
                        return@forEach
                    }
                    executeSetDnd(context, action)
                }

                // ───────────── Display Actions ─────────────
                is AutomationAction.SetDarkMode -> {
                    if (!canWriteSettings) {
                        Log.w(TAG, "WRITE_SETTINGS not granted, skipping dark mode")
                        return@forEach
                    }
                    executeSetDarkMode(context, action)
                }

                is AutomationAction.SetAutoRotate -> {
                    if (!canWriteSettings) {
                        Log.w(TAG, "WRITE_SETTINGS not granted, skipping auto-rotate")
                        return@forEach
                    }
                    executeSetAutoRotate(context, action)
                }

                is AutomationAction.SetScreenTimeout -> {
                    if (!canWriteSettings) {
                        Log.w(TAG, "WRITE_SETTINGS not granted, skipping screen timeout")
                        return@forEach
                    }
                    executeSetScreenTimeout(context, action)
                }

                is AutomationAction.SetNightLight -> {
                    if (!canWriteSettings) {
                        Log.w(TAG, "WRITE_SETTINGS not granted, skipping night light")
                        return@forEach
                    }
                    executeSetNightLight(context, action)
                }

                is AutomationAction.SetKeepScreenAwake -> {
                    executeSetKeepScreenAwake(context, action)
                }
            }
        }
    }

    // ───────────────── Volume ─────────────────

    private fun executeSetVolume(context: Context, action: AutomationAction.SetVolume) {
        try {
            // Delegate to foreground service which runs with a persistent notification.
            com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.TrackingForegroundService
                .startVolumeChange(context, action.ring, action.media)
            Log.i(TAG, "Delegated volume change to TrackingForegroundService: ring=${action.ring}, media=${action.media}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delegate volume change to foreground service", e)
        }
    }

    // ───────────────── Brightness ─────────────────

    private fun executeSetBrightness(context: Context, action: AutomationAction.SetBrightness) {
        try {
            val brightness = action.level.coerceIn(10, 255)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness
            )
            Log.i(TAG, "Brightness set to $brightness")
            notifySuccess(context, "Brightness set to $brightness")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set brightness", e)
        }
    }

    // ───────────────── DND ─────────────────

    private fun executeSetDnd(context: Context, action: AutomationAction.SetDnd) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.setInterruptionFilter(
                if (action.enabled)
                    NotificationManager.INTERRUPTION_FILTER_NONE
                else
                    NotificationManager.INTERRUPTION_FILTER_ALL
            )
            val status = if (action.enabled) "enabled" else "disabled"
            Log.i(TAG, "DND $status")
            notifySuccess(context, "Do Not Disturb $status")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set DND", e)
        }
    }

    // ───────────────── Display Actions ─────────────────

    private fun executeSetDarkMode(context: Context, action: AutomationAction.SetDarkMode) {
        try {
            val modeValue = if (action.enabled) 1 else 0
            Settings.Secure.putInt(context.contentResolver, "dark_mode_override", modeValue)
            val status = if (action.enabled) "enabled" else "disabled"
            Log.i(TAG, "Dark mode $status")
            notifySuccess(context, "Dark Mode $status")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set dark mode", e)
        }
    }

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

    private fun executeSetNightLight(context: Context, action: AutomationAction.SetNightLight) {
        try {
            Settings.Secure.putInt(
                context.contentResolver,
                "night_display_activated",
                if (action.enabled) 1 else 0
            )
            val status = if (action.enabled) "enabled" else "disabled"
            Log.i(TAG, "Night light $status")
            notifySuccess(context, "Night Light $status")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set night light", e)
        }
    }

    private fun executeSetKeepScreenAwake(context: Context, action: AutomationAction.SetKeepScreenAwake) {
        try {
            if (action.enabled) {
                TrackingForegroundService.acquirePartialWakeLock(context)
                Log.i(TAG, "Keep screen awake activated")
                notifySuccess(context, "Keep screen awake activated")
            } else {
                TrackingForegroundService.releasePartialWakeLock(context)
                Log.i(TAG, "Keep screen awake deactivated")
                notifySuccess(context, "Keep screen awake deactivated")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to manage keep screen awake", e)
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

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(SUCCESS_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        SUCCESS_CHANNEL_ID,
                        "Automation Success",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
        }
    }
}
