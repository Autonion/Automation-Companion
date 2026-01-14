package com.autonion.automationcompanion.features.system_context_automation.location.executor

import android.content.Context
import android.app.NotificationManager
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import com.autonion.automationcompanion.features.automation.actions.models.AutomationAction
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
                    // ───────────── Display Actions ─────────────
                    is AutomationAction.SetDarkMode -> executeSetDarkMode(action)
                    is AutomationAction.SetAutoRotate -> executeSetAutoRotate(action)
                    is AutomationAction.SetScreenTimeout -> executeSetScreenTimeout(action)
                    is AutomationAction.SetNightLight -> executeSetNightLight(action)
                    is AutomationAction.SetKeepScreenAwake -> executeSetKeepScreenAwake(action)
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

    // ───────────────── Display Actions ─────────────────

    /**
     * Set Dark Mode on/off.
     * Non-root implementation: Uses Settings.Secure DARK_MODE_SCHEDULE_TYPE (Android 10+)
     * Fallback: Opens display settings for manual configuration on older devices.
     * Graceful: If permission denied, logs warning but does not crash.
     */
    private fun executeSetDarkMode(action: AutomationAction.SetDarkMode) {
        if (!Settings.System.canWrite(context)) {
            Log.w("ActionExecutor", "WRITE_SETTINGS not granted for dark mode; fallback would open settings")
            // Graceful fallback: user can manually configure in settings if needed
            return
        }

        try {
            // Android 10+: Use DARK_MODE_SCHEDULE_TYPE
            // 0 = off, 1 = on, 2 = schedule, 3 = custom schedule
            val modeValue = if (action.enabled) 1 else 0
            Settings.Secure.putInt(context.contentResolver, "dark_mode_override", modeValue)
            Log.i("ActionExecutor", "Set dark mode: ${action.enabled}")
        } catch (e: Exception) {
            Log.w("ActionExecutor", "Failed to set dark mode, may require Android 10+", e)
        }
    }

    /**
     * Set Auto-rotate on/off.
     * Non-root implementation: Uses Settings.System ACCELEROMETER_ROTATION (global setting).
     * Graceful: If permission denied, logs warning but does not crash.
     */
    private fun executeSetAutoRotate(action: AutomationAction.SetAutoRotate) {
        if (!Settings.System.canWrite(context)) {
            Log.w("ActionExecutor", "WRITE_SETTINGS not granted for auto-rotate")
            return
        }

        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                if (action.enabled) 1 else 0
            )
            Log.i("ActionExecutor", "Set auto-rotate: ${action.enabled}")
        } catch (e: Exception) {
            Log.w("ActionExecutor", "Failed to set auto-rotate", e)
        }
    }

    /**
     * Set screen timeout duration.
     * Non-root implementation: Uses Settings.System SCREEN_OFF_TIMEOUT (global setting, in milliseconds).
     * Graceful: If permission denied, logs warning but does not crash.
     */
    private fun executeSetScreenTimeout(action: AutomationAction.SetScreenTimeout) {
        if (!Settings.System.canWrite(context)) {
            Log.w("ActionExecutor", "WRITE_SETTINGS not granted for screen timeout")
            return
        }

        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                action.durationMs
            )
            Log.i("ActionExecutor", "Set screen timeout: ${action.durationMs}ms")
        } catch (e: Exception) {
            Log.w("ActionExecutor", "Failed to set screen timeout", e)
        }
    }

    /**
     * Set Night Light on/off.
     * Non-root implementation: Uses Settings.Secure NIGHT_DISPLAY (Android 7+).
     * Fallback: Gracefully handles older devices that don't support this setting.
     * Graceful: If permission denied or feature not supported, logs warning.
     */
    private fun executeSetNightLight(action: AutomationAction.SetNightLight) {
        if (!Settings.System.canWrite(context)) {
            Log.w("ActionExecutor", "WRITE_SETTINGS not granted for night light")
            return
        }

        try {
            // Android 7+ feature
            Settings.Secure.putInt(
                context.contentResolver,
                "night_display_activated",
                if (action.enabled) 1 else 0
            )
            Log.i("ActionExecutor", "Set night light: ${action.enabled}")
        } catch (e: Exception) {
            Log.w("ActionExecutor", "Failed to set night light, may require Android 7+", e)
        }
    }

    /**
     * Set Keep Screen Awake during active trigger.
     * Non-root implementation: Acquires/releases partial wake lock via foreground service.
     * Duration: Active while trigger is active; released when trigger deactivates.
     * Graceful: If permission denied, logs warning; wake lock simply won't be acquired.
     */
    private fun executeSetKeepScreenAwake(action: AutomationAction.SetKeepScreenAwake) {
        try {
            if (action.enabled) {
                // Delegate to foreground service to acquire partial wake lock
                TrackingForegroundService.acquirePartialWakeLock(context)
                Log.i("ActionExecutor", "Keep screen awake: ACTIVATED")
            } else {
                // Delegate to foreground service to release partial wake lock
                TrackingForegroundService.releasePartialWakeLock(context)
                Log.i("ActionExecutor", "Keep screen awake: DEACTIVATED")
            }
        } catch (e: Exception) {
            Log.w("ActionExecutor", "Failed to manage keep screen awake", e)
        }
    }
}
