package com.autonion.automationcompanion.features.system_context_automation.location.helpers

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
                            notifySuccess(context, number)
                        }
                }

                // Future-proof: other actions stay here
                else -> {
                    // Volume / DND / Brightness already handled elsewhere
                }
            }
        }
    }

    // ---------- Notifications ----------

    private fun notifySuccess(context: Context, number: String) {
        val notification = NotificationCompat.Builder(context, SUCCESS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Automation executed")
            .setContentText("Message sent to $number")
            .setAutoCancel(true)
            .build()

        notifyIfAllowed(context, number.hashCode(), notification)
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
