package com.autonion.automationcompanion.features.system_context_automation.location.executor

import android.content.Context
import android.media.AudioManager
import android.app.NotificationManager
import android.provider.Settings
import android.telephony.SmsManager
import android.os.Build
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.AutomationAction

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
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        am.setStreamVolume(
            AudioManager.STREAM_RING,
            action.ring.coerceIn(0, am.getStreamMaxVolume(AudioManager.STREAM_RING)),
            0
        )

        am.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            action.media.coerceIn(0, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)),
            0
        )
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
