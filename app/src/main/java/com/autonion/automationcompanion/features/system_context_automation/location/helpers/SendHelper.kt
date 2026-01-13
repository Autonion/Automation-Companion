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
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.AutomationAction
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
