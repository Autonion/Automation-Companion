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
                    val enabled = action.parameters["enabled"]?.toBoolean() ?: true
                    if (enabled) {
                        notificationManager.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    } else {
                        notificationManager.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                    }
                    Log.i("ActionExecutor", "DND set to $enabled")
                } else {
                    Log.w("ActionExecutor", "DND Permission missing")
                    showNotification("Action Failed", "Grant DND permission to AutomationCompanion")
                }
            }
            "set_volume" -> {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                try {
                    action.parameters["ring_volume"]?.toIntOrNull()?.let { 
                        audioManager.setStreamVolume(android.media.AudioManager.STREAM_RING, it, 0)
                    }
                    action.parameters["media_volume"]?.toIntOrNull()?.let { 
                        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, it, 0)
                    }
                    action.parameters["alarm_volume"]?.toIntOrNull()?.let { 
                        audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, it, 0)
                    }
                    action.parameters["ringer_mode"]?.let { mode ->
                        when(mode) {
                            "SILENT" -> audioManager.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                            "VIBRATE" -> audioManager.ringerMode = android.media.AudioManager.RINGER_MODE_VIBRATE
                            "NORMAL" -> audioManager.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ActionExecutor", "Failed to set volume", e)
                }
            }
            "set_brightness" -> {
                if (android.provider.Settings.System.canWrite(context)) {
                    action.parameters["level"]?.toIntOrNull()?.let { level ->
                        // Clamp to valid range (0-255)
                        val safeLevel = level.coerceIn(0, 255)
                        android.provider.Settings.System.putInt(
                            context.contentResolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS,
                            safeLevel
                        )
                    }
                } else {
                    Log.w("ActionExecutor", "Write Settings permission missing for brightness")
                }
            }
            "set_auto_rotate" -> {
                if (android.provider.Settings.System.canWrite(context)) {
                    val enabled = action.parameters["enabled"]?.toBoolean() ?: false
                    android.provider.Settings.System.putInt(
                        context.contentResolver,
                        android.provider.Settings.System.ACCELEROMETER_ROTATION,
                        if (enabled) 1 else 0
                    )
                } else {
                    Log.w("ActionExecutor", "Write Settings permission missing for auto-rotate")
                }
            }
            "set_screen_timeout" -> {
                if (android.provider.Settings.System.canWrite(context)) {
                    action.parameters["duration_ms"]?.toIntOrNull()?.let { timeout ->
                         android.provider.Settings.System.putInt(
                            context.contentResolver,
                            android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
                            timeout
                        )
                    }
                }
            }
            "launch_app" -> {
                val packageName = action.parameters["package_name"]
                if (!packageName.isNullOrEmpty()) {
                    try {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                        if (launchIntent != null) {
                            launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launchIntent)
                        } else {
                            Log.w("ActionExecutor", "App not found: $packageName")
                        }
                    } catch (e: Exception) {
                         Log.e("ActionExecutor", "Failed to launch app $packageName", e)
                    }
                }
            }
            "send_sms" -> {
                val message = action.parameters["message"]
                val contacts = action.parameters["contacts_csv"]?.split(";") ?: emptyList()
                
                if (!message.isNullOrEmpty() && contacts.isNotEmpty()) {
                    if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                         val smsManager = context.getSystemService(android.telephony.SmsManager::class.java)
                         contacts.forEach { number -> 
                             if (number.isNotBlank()) {
                                 smsManager.sendTextMessage(number, null, message, null, null)
                             }
                         }
                    } else {
                        Log.w("ActionExecutor", "SMS Permission missing")
                    }
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
            "set_clipboard" -> {
                val text = action.parameters["text"]
                if (!text.isNullOrEmpty()) {
                    try {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Automation", text)
                        clipboard.setPrimaryClip(clip)
                        Log.i("ActionExecutor", "Clipboard Updated: $text")
                    } catch (e: Exception) {
                        Log.e("ActionExecutor", "Failed to set clipboard", e)
                    }
                }
            }
            "set_battery_saver" -> {
                // Battery Saver is generally read-only for apps, but we can open settings
                 val intent = android.content.Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
                 intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                 context.startActivity(intent)
                 showNotification("Automation", "Please toggle Battery Saver manually")
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
