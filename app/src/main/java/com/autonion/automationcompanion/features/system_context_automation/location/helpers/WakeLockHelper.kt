package com.autonion.automationcompanion.features.system_context_automation.location.helpers

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Helper to manage partial/screen wake locks directly without requiring
 * location-typed foreground services or location permissions.
 */
object WakeLockHelper {
    private const val TAG = "WakeLockHelper"
    private var wakeLock: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    @Synchronized
    fun acquire(context: Context) {
        try {
            if (wakeLock?.isHeld == true) {
                Log.w(TAG, "Wake lock already held")
                return
            }

            val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "automationcompanion:keep_screen_awake"
            ).apply {
                acquire()
                Log.i(TAG, "Screen wake lock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock", e)
        }
    }

    @Synchronized
    fun release() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                wakeLock = null
                Log.i(TAG, "Screen wake lock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wake lock", e)
        }
    }
}
