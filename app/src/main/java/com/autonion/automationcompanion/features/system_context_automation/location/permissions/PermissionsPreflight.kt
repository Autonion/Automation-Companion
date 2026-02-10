package com.autonion.automationcompanion.features.system_context_automation.location.permissions

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.autonion.automationcompanion.features.automation.actions.models.AutomationAction



object PermissionPreflight {

    fun missingSystemPermissions(
        context: Context,
        actions: List<AutomationAction>
    ): List<SystemPermission> {

        val missing = mutableListOf<SystemPermission>()

        actions.forEach { action ->
            when (action) {
                is AutomationAction.SetBrightness -> {
                    if (!Settings.System.canWrite(context)) {
                        missing += SystemPermission.WriteSettings
                    }
                }

                is AutomationAction.SetDnd -> {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    if (!nm.isNotificationPolicyAccessGranted) {
                        missing += SystemPermission.DndAccess
                    }
                }

                else -> Unit
            }
        }

        return missing.distinct()
    }

    fun settingsIntent(context: Context, permission: SystemPermission): Intent {
        return when (permission) {
            SystemPermission.WriteSettings ->
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))

            SystemPermission.DndAccess ->
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        }
    }
}

enum class SystemPermission {
    WriteSettings,
    DndAccess
}
