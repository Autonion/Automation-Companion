//package com.example.automationcompanion.core.helpers
//
//import android.app.Activity
//import android.app.AlarmManager
//import android.app.PendingIntent
//import android.content.Context
//import android.content.Intent
//import android.net.Uri
//import android.os.Build
//import android.provider.Settings
//import android.util.Log
//
//object AlarmHelpers {
//    private const val TAG = "AlarmHelpers"
//
//    /**
//     * Schedule an alarm trying to use exact alarms if allowed; otherwise fall back safely.
//     *
//     * - context: used to get AlarmManager and possibly to start the "request exact alarm" settings activity.
//     * - triggerAtMillis: when to trigger
//     * - pi: pending intent for the alarm
//     * - activityToRequest (optional): Activity to use to request the user grant for exact alarms (if needed)
//     */
//    fun scheduleExactOrFallback(
//        context: Context,
//        triggerAtMillis: Long,
//        pi: PendingIntent,
//        activityToRequest: Activity? = null
//    ) {
//        val am = context.getSystemService(AlarmManager::class.java)
//        if (am == null) {
//            Log.w(TAG, "AlarmManager not available")
//            return
//        }
//
//        try {
//            // On Android 12L / 13 / 14+ we must check canScheduleExactAlarms()
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31
//                val can = am.canScheduleExactAlarms()
//                if (can) {
//                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
//                    return
//                } else {
//                    Log.i(TAG, "Exact alarms not allowed for this app — falling back to inexact alarm")
//                    // Fall back to inexact alarm that does not require SCHEDULE_EXACT_ALARM
//                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
//                    // Optionally prompt the user to grant exact alarm permission if an Activity is provided
//                    activityToRequest?.let { askUserToAllowExactAlarms(it) }
//                    return
//                }
//            } else {
//                // On older Android versions (below API 31) exact alarms do not require the new permission,
//                // so just schedule the exact alarm (the platform will allow it).
//                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
//                return
//            }
//        } catch (sec: SecurityException) {
//            // Defensive: if we still hit a SecurityException, fallback to an inexact alarm
//            Log.w(TAG, "SecurityException scheduling alarm: ${sec.message}. Falling back to inexact alarm.")
//            try {
//                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
//            } catch (e: Exception) {
//                Log.e(TAG, "Failed to schedule even fallback alarm: ${e.message}")
//            }
//        } catch (t: Throwable) {
//            Log.e(TAG, "Unexpected error scheduling alarm: ${t.message}")
//        }
//    }
//
//    /**
//     * Open the system UI that lets the user grant "Allow exact alarms" for this app.
//     * Only meaningful on API 31+. Uses ACTION_REQUEST_SCHEDULE_EXACT_ALARM.
//     */
//    private fun askUserToAllowExactAlarms(activity: Activity) {
//        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
//
//        try {
//            // The recommended intent
//            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
//                // include package URI to show the specific app page on some OEMs
//                data = Uri.parse("package:${activity.packageName}")
//            }
//            activity.startActivity(intent)
//        } catch (e: Exception) {
//            Log.w(TAG, "Could not open request-exact-alarm settings: ${e.message}")
//            // As a fallback you can open the app's settings page:
//            try {
//                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
//                    data = Uri.parse("package:${activity.packageName}")
//                }
//                activity.startActivity(i)
//            } catch (_: Exception) { /* ignore */ }
//        }
//    }
//}
