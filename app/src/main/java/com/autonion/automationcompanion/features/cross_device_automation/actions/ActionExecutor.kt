package com.autonion.automationcompanion.features.cross_device_automation.actions

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceRole
import com.autonion.automationcompanion.features.cross_device_automation.domain.RuleAction
import com.autonion.automationcompanion.features.cross_device_automation.networking.NetworkingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ActionExecutor(
    private val context: Context,
    private val networkingManager: NetworkingManager
) {

    init {
        createNotificationChannel()
    }

    fun execute(action: RuleAction) {
        if (action.targetDeviceId == null || action.targetDeviceId == "local") {
             executeLocal(action)
        } else {
             executeRemote(action)
        }
    }

    private fun executeLocal(action: RuleAction) {
        Log.d("ActionExecutor", "Executing local action: ${action.type}")
        when (action.type) {
            "enable_dnd" -> {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                if (notificationManager.isNotificationPolicyAccessGranted) {
                    notificationManager.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    Log.i("ActionExecutor", "DND Enabled")
                } else {
                    Log.w("ActionExecutor", "DND Permission missing")
                }
            }
            "open_url" -> {
                val url = action.parameters["url"]
                if (!url.isNullOrEmpty()) {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        Log.i("ActionExecutor", "Opened URL: $url")
                    } catch (e: Exception) {
                        Log.e("ActionExecutor", "Failed to open URL", e)
                    }
                }
            }
            "send_notification" -> {
                val title = action.parameters["title"] ?: "Automation"
                val message = action.parameters["message"] ?: "Action Executed"
                showNotification(title, message)
            }
        }
    }

    private fun executeRemote(action: RuleAction) {
         Log.d("ActionExecutor", "Executing remote action on ${action.targetDeviceId}")
         val command = mapOf(
             "action" to action.type
         ) + action.parameters
         
         if (action.targetDeviceId == "Remote (All)") {
             networkingManager.broadcast(command)
         } else if (action.targetDeviceId != null) {
             networkingManager.sendCommand(action.targetDeviceId, command)
         }
    }
    
    // --- Notification Helpers ---
    
    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val name = "Automation Actions"
            val descriptionText = "Notifications triggered by automation rules"
            val importance = android.app.NotificationManager.IMPORTANCE_DEFAULT
            val channel = android.app.NotificationChannel("automation_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: android.app.NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, message: String) {
        if (androidx.core.app.ActivityCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("ActionExecutor", "Notification permission missing")
            return
        }

        val builder = androidx.core.app.NotificationCompat.Builder(context, "automation_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // TODO: Use app icon
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)

        val notificationManager = androidx.core.app.NotificationManagerCompat.from(context)
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
