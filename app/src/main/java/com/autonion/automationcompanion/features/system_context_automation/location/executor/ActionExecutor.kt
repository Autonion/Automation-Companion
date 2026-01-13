package com.autonion.automationcompanion.features.system_context_automation.location.executor

import android.content.Context
import android.app.NotificationManager
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.AutomationAction
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.TrackingForegroundService

class ActionExecutor(
    private val context: Context
) {

    fun execute(actions: List<AutomationAction>) {
        actions.forEach { action ->
            try {
                when (action) {
                    is AutomationAction.SendSms -> executeSendSms(action)
                    is AutomationAction.SetVolume -> executeSetVolume(action)
                    is AutomationAction.SetBrightness -> executeSetBrightness(action)
                    is AutomationAction.SetDnd -> executeSetDnd(action)
                }
            } catch (t: Throwable) {
                Log.e("ActionExecutor", "Failed to execute $action", t)
            }
        }
    }

    // ───────────────── SMS ─────────────────

    private fun executeSendSms(action: AutomationAction.SendSms) {
        val smsManager = SmsManager.getDefault()
        val numbers = action.contactsCsv
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        numbers.forEach { number ->
            smsManager.sendTextMessage(
                number,
                null,
                action.message,
                null,
                null
            )
        }
    }

    // ───────────────── Volume ─────────────────

    private fun executeSetVolume(action: AutomationAction.SetVolume) {
        try {
            // Delegate to foreground service to perform the change reliably
            TrackingForegroundService.startVolumeChange(context, action.ring, action.media)
            Log.i("ActionExecutor", "Delegated volume change to TrackingForegroundService: ring=${action.ring}, media=${action.media}")
        } catch (e: Exception) {
            Log.e("ActionExecutor", "Failed to delegate volume change to foreground service", e)
        }
    }

    // ───────────────── Brightness ─────────────────

    private fun executeSetBrightness(action: AutomationAction.SetBrightness) {
        if (!Settings.System.canWrite(context)) {
            Log.w("ActionExecutor", "WRITE_SETTINGS not granted")
            return
        }

        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            action.level.coerceIn(10, 255)
        )
    }

    // ───────────────── DND ─────────────────

    private fun executeSetDnd(action: AutomationAction.SetDnd) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (!nm.isNotificationPolicyAccessGranted) {
            Log.w("ActionExecutor", "DND access not granted")
            return
        }

        nm.setInterruptionFilter(
            if (action.enabled)
                NotificationManager.INTERRUPTION_FILTER_NONE
            else
                NotificationManager.INTERRUPTION_FILTER_ALL
        )
    }
}
