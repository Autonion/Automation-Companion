package com.autonion.automationcompanion.features.gesture_recording_playback.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class PermissionHelper(private val context: Context) {

    companion object {
        const val REQUEST_OVERLAY_PERMISSION = 1001
        const val REQUEST_ACCESSIBILITY_PERMISSION = 1002
        const val REQUEST_NOTIFICATION_PERMISSION = 1003
    }

    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun isAccessibilityServiceEnabled(): Boolean {
//        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
//                as AccessibilityManager

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

        return enabledServices?.contains(context.packageName) == true
    }

    fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        (context as? Activity)?.startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                context as Activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
        }
    }

    fun requestAccessibilityPermission() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        (context as? Activity)?.startActivityForResult(intent, REQUEST_ACCESSIBILITY_PERMISSION)
    }

//    fun getAccessibilityServiceInfo(): AccessibilityServiceInfo? {
//        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
//                as AccessibilityManager
//
//        return accessibilityManager.getEnabledAccessibilityServiceList(
//            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
//        ).firstOrNull { it.id.contains(context.packageName) }
//    }
}