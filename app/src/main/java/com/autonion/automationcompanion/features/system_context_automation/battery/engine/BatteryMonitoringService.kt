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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.shared.SystemSlotController

class BatteryMonitoringService : Service() {
    private lateinit var batteryReceiver: BatteryBroadcastReceiver
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "battery_monitoring_channel"
    private val TAG = "BatteryMonitoringService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var receiverRegistered = false
    private var foregroundReady = false

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
        try {
            startForegroundService()
            foregroundReady = true
        } catch (error: Exception) {
            Log.e(TAG, "Battery foreground service is unavailable", error)
            stopSelf()
        }
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
        if (receiverRegistered) return
        batteryReceiver = BatteryBroadcastReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, filter)
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) return START_NOT_STICKY
        serviceScope.launch {
            try {
                if (intent?.action == ACTION_STOP) {
                    SystemSlotController.stopBattery(applicationContext)
                    stopForeground(true)
                    stopSelf()
                } else {
                    val enabled = withContext(Dispatchers.IO) {
                        AppDatabase.get(applicationContext).slotDao().getEnabledSlotsByType("BATTERY").isNotEmpty()
                    }
                    if (enabled) registerBatteryReceiver() else {
                        stopForeground(true)
                        stopSelfResult(startId)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Could not restore battery monitoring", error)
                stopForeground(true)
                stopSelf()
            }
        }
        return if (intent?.action == ACTION_STOP) START_NOT_STICKY else START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
        try {
            if (receiverRegistered) unregisterReceiver(batteryReceiver)
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
        internal const val ACTION_STOP = "com.autonion.automationcompanion.ACTION_STOP_BATTERY_MONITORING"

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
