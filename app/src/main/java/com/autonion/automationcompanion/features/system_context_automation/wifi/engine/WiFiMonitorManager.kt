package com.autonion.automationcompanion.features.system_context_automation.wifi.engine

import android.app.Application
import android.content.Context
import android.util.Log

/**
 * WiFi Monitor Manager - Handles WiFi network monitoring for Android 7+
 * Call initialize() in your Application onCreate() or MainActivity onCreate()
 */
object WiFiMonitorManager {
    private const val TAG = "WiFiMonitorManager"
    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return
        }

        try {
            // Register network callback for Android 7+
            WiFiBroadcastReceiver.registerNetworkCallback(context.applicationContext)
            isInitialized = true
            Log.i(TAG, "WiFi monitoring initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WiFi monitoring", e)
        }
    }

    fun shutdown(context: Context) {
        if (!isInitialized) {
            return
        }

        try {
            WiFiBroadcastReceiver.unregisterNetworkCallback(context.applicationContext)
            isInitialized = false
            Log.i(TAG, "WiFi monitoring shutdown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to shutdown WiFi monitoring", e)
        }
    }
}
