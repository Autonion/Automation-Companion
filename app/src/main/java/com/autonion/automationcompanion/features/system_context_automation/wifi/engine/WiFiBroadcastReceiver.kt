package com.autonion.automationcompanion.features.system_context_automation.wifi.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.shared.executor.SlotExecutor
import com.autonion.automationcompanion.features.system_context_automation.shared.models.TriggerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * WiFiBroadcastReceiver listens for Wi-Fi connectivity changes.
 * Fires when Wi-Fi is connected/disconnected, optionally matching SSID.
 */
class WiFiBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WiFiReceiver"
        private var networkCallback: ConnectivityManager.NetworkCallback? = null

        /**
         * Register network callback for Android 7+ (API 24+)
         * This is needed because CONNECTIVITY_ACTION cannot be used in manifest for API 24+
         */
        fun registerNetworkCallback(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                
                // Unregister existing callback if any
                networkCallback?.let {
                    try {
                        connectivityManager.unregisterNetworkCallback(it)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error unregistering network callback", e)
                    }
                }

                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()

                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        Log.i(TAG, "Network available (WiFi connected)")
                        CoroutineScope(Dispatchers.IO).launch {
                            evaluateWiFiSlots(context)
                        }
                    }

                    override fun onLost(network: Network) {
                        super.onLost(network)
                        Log.i(TAG, "Network lost (WiFi disconnected)")
                        CoroutineScope(Dispatchers.IO).launch {
                            evaluateWiFiSlots(context)
                        }
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        super.onCapabilitiesChanged(network, networkCapabilities)
                        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            Log.i(TAG, "WiFi capabilities changed")
                            CoroutineScope(Dispatchers.IO).launch {
                                evaluateWiFiSlots(context)
                            }
                        }
                    }
                }

                try {
                    connectivityManager.registerNetworkCallback(request, networkCallback!!)
                    Log.i(TAG, "Network callback registered for Android 7+")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register network callback", e)
                }
            }
        }

        fun unregisterNetworkCallback(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                networkCallback?.let {
                    try {
                        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        connectivityManager.unregisterNetworkCallback(it)
                        networkCallback = null
                        Log.i(TAG, "Network callback unregistered")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error unregistering network callback", e)
                    }
                }
            }
        }

        private suspend fun evaluateWiFiSlots(context: Context) {
            val currentState = getCurrentWiFiState(context)
            val currentSsid = getCurrentSsid(context)

            Log.i(TAG, "Current Wi-Fi state: $currentState, SSID: $currentSsid")

            val dao = AppDatabase.get(context).slotDao()
            val allSlots = dao.getAllEnabled()

            val json = Json {
                ignoreUnknownKeys = true
                classDiscriminator = "type"
            }

            for (slot in allSlots) {
                if (slot.triggerType != "WIFI") continue

                val triggerConfig = try {
                    slot.triggerConfigJson?.let { json.decodeFromString<TriggerConfig.WiFi>(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to deserialize Wi-Fi config for slot ${slot.id}", e)
                    null
                } ?: continue

                // Check if Wi-Fi state matches
                val stateMatches = when (triggerConfig.connectionState) {
                    TriggerConfig.WiFi.ConnectionState.CONNECTED -> currentState == WiFiState.CONNECTED
                    TriggerConfig.WiFi.ConnectionState.DISCONNECTED -> currentState == WiFiState.DISCONNECTED
                }

                if (!stateMatches) continue

                // Check SSID if specified
                if (triggerConfig.optionalSsid != null && triggerConfig.optionalSsid != currentSsid) {
                    Log.i(TAG, "SSID mismatch: expected ${triggerConfig.optionalSsid}, got $currentSsid")
                    continue
                }

                Log.i(TAG, "Wi-Fi slot ${slot.id} triggered (state=$currentState, SSID=$currentSsid)")
                SlotExecutor.execute(context, slot.id)
            }
        }

        private fun getCurrentWiFiState(context: Context): WiFiState {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                
                return if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    WiFiState.CONNECTED
                } else {
                    WiFiState.DISCONNECTED
                }
            } else {
                // Fallback for Android < M (API 23)
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                return if (networkInfo != null && networkInfo.type == ConnectivityManager.TYPE_WIFI && networkInfo.isConnected) {
                    WiFiState.CONNECTED
                } else {
                    WiFiState.DISCONNECTED
                }
            }
        }

        private fun getCurrentSsid(context: Context): String? {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo ?: return null
                val ssid = wifiInfo.ssid ?: return null

                // Check for unknown SSID (happens without location permission on Android 10+)
                if (ssid == "<unknown ssid>" || ssid == "0x") {
                    Log.w(TAG, "SSID unavailable - likely missing location permission (Android 10+)")
                    return null
                }

                // Remove surrounding quotes if present
                return if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid.substring(1, ssid.length - 1)
                } else {
                    ssid
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get current SSID", e)
                return null
            }
        }

        private enum class WiFiState {
            CONNECTED,
            DISCONNECTED
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "Received intent: ${intent?.action}")
        
        // This receiver is only for Android < 7 (API < 24)
        // For Android 7+, we use NetworkCallback registered separately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Log.i(TAG, "Android 7+ detected, should use NetworkCallback instead")
            return
        }
        
        val action = intent?.action
        when (action) {
            WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                Log.i(TAG, "Network state changed")
                CoroutineScope(Dispatchers.IO).launch {
                    evaluateWiFiSlots(context)
                }
            }
            WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                Log.i(TAG, "Wi-Fi state changed")
                CoroutineScope(Dispatchers.IO).launch {
                    evaluateWiFiSlots(context)
                }
            }
            @Suppress("DEPRECATION")
            ConnectivityManager.CONNECTIVITY_ACTION -> {
                Log.i(TAG, "Connectivity changed")
                CoroutineScope(Dispatchers.IO).launch {
                    evaluateWiFiSlots(context)
                }
            }
        }
    }
}
