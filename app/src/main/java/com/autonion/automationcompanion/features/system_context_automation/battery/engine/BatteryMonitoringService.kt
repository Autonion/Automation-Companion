package com.autonion.automationcompanion.features.system_context_automation.battery.engine

import android.app.*
import android.content.*
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.autonion.automationcompanion.R
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BatteryMonitoringService : Service() {
    private lateinit var batteryReceiver: BatteryBroadcastReceiver
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "battery_monitoring_channel"
    private val TAG = "BatteryMonitoringService"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BatteryMonitoringService: onCreate")
        DebugLogger.info(
            this, LogCategory.SYSTEM_CONTEXT,
            "Battery Monitoring Service",
            "Service created",
            TAG
        )
        createNotificationChannel()
        startForegroundService()
        registerBatteryReceiver()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Battery Automation Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors battery level for automations"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val stopIntent = Intent(this, BatteryMonitoringService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Battery Automation")
            .setContentText("Monitoring battery level")
            .setSmallIcon(R.drawable.ic_notification) // Use your own icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun registerBatteryReceiver() {
        batteryReceiver = BatteryBroadcastReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop action received — stopping battery monitoring")
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY // Service will restart if killed
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // Receiver was not registered
        }
        DebugLogger.info(
            this, LogCategory.SYSTEM_CONTEXT,
            "Battery monitoring stopped",
            "Receiver unregistered, service destroyed",
            TAG
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_STOP = "com.autonion.automationcompanion.ACTION_STOP_BATTERY_MONITORING"

        fun startService(context: Context) {
            val intent = Intent(context, BatteryMonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, BatteryMonitoringService::class.java)
            context.stopService(intent)
        }
    }
}