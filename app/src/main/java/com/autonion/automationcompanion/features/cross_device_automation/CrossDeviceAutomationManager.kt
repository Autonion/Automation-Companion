package com.autonion.automationcompanion.features.cross_device_automation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch
import com.autonion.automationcompanion.features.cross_device_automation.actions.ActionExecutor
import com.autonion.automationcompanion.features.cross_device_automation.data.InMemoryDeviceRepository
import com.autonion.automationcompanion.features.cross_device_automation.data.InMemoryRuleRepository
import com.autonion.automationcompanion.features.cross_device_automation.event_pipeline.EventEnricher
import com.autonion.automationcompanion.features.cross_device_automation.event_pipeline.EventPipeline
import com.autonion.automationcompanion.features.cross_device_automation.host_management.HostManager
import com.autonion.automationcompanion.features.cross_device_automation.networking.NetworkingManager
import com.autonion.automationcompanion.features.cross_device_automation.rules.RuleEngine
import com.autonion.automationcompanion.features.cross_device_automation.tagging.TaggingSystem

class CrossDeviceAutomationManager(private val context: Context) {
    
    // Repositories
    val deviceRepository = InMemoryDeviceRepository()
    val ruleRepository = InMemoryRuleRepository()

    // Event Pipeline Components
    private val enricher = EventEnricher()
    private val taggingSystem = TaggingSystem()
    
    lateinit var networkingManager: NetworkingManager
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var ruleEngine: RuleEngine
    private lateinit var eventPipeline: EventPipeline
    private lateinit var hostManager: HostManager
    private lateinit var clipboardMonitor: com.autonion.automationcompanion.features.cross_device_automation.event_source.ClipboardMonitor
    private var isStarted = false

    // Background Execution Locks
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    // Preferences
    private val prefs = context.getSharedPreferences("cross_device_prefs", Context.MODE_PRIVATE)
    private val PREF_FEATURE_ENABLED = "feature_enabled"

    fun initialize() {
        // ... (Existing initialization logic) ...
        
        // 3. Components
        val eventReceiverProxy = object : com.autonion.automationcompanion.features.cross_device_automation.event_pipeline.EventReceiver {
            override suspend fun onEventReceived(event: com.autonion.automationcompanion.features.cross_device_automation.domain.RawEvent) {
                if (::eventPipeline.isInitialized) {
                    eventPipeline.onEventReceived(event)
                }
            }
        }
        
        networkingManager = NetworkingManager(deviceRepository, eventReceiverProxy)
        actionExecutor = ActionExecutor(context, networkingManager)
        
        ruleEngine = RuleEngine(ruleRepository, actionExecutor) 
        
        eventPipeline = EventPipeline(enricher, taggingSystem) { event ->
            if (::networkingManager.isInitialized) {
                networkingManager.broadcast(event)
            }
        }
        
        hostManager = HostManager(context, deviceRepository)
        
        val stateEvaluator = com.autonion.automationcompanion.features.cross_device_automation.state.StateEvaluator()

        clipboardMonitor = com.autonion.automationcompanion.features.cross_device_automation.event_source.ClipboardMonitor(context, eventPipeline)
        com.autonion.automationcompanion.AccessibilityRouter.register(clipboardMonitor)
    }

    fun isFeatureEnabled(): Boolean {
        return prefs.getBoolean(PREF_FEATURE_ENABLED, true) // Default true
    }

    fun setFeatureEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_FEATURE_ENABLED, enabled).apply()
        if (enabled) {
            start()
        } else {
            stop()
        }
    }

    fun start() {
        if (isStarted) return
        if (!isFeatureEnabled()) {
            Log.d("CrossDeviceManager", "Feature disabled by user. Not starting.")
            return
        }

        isStarted = true
        acquireLocks()
        hostManager.startDiscovery()
        networkingManager.start()
        Log.d("CrossDeviceManager", "Service started (Background Mode)")
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        hostManager.stopDiscovery()
        networkingManager.stop()
        releaseLocks()
        Log.d("CrossDeviceManager", "Service stopped")
    }

    private fun acquireLocks() {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "AutomationCompanion::CrossDeviceWakeLock")
            wakeLock?.acquire(10 * 60 * 1000L /*10 minutes timeout just in case*/)

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wifiLock = wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AutomationCompanion::CrossDeviceWifiLock")
            wifiLock?.acquire()
            
            Log.d("CrossDeviceManager", "Acquired WakeLock and WifiLock")
        } catch (e: Exception) {
            Log.e("CrossDeviceManager", "Failed to acquire locks", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
            Log.d("CrossDeviceManager", "Released WakeLock and WifiLock")
        } catch (e: Exception) {
            Log.e("CrossDeviceManager", "Failed to release locks", e)
        }
    }

    // Called by AutomationService (Accessibility Service) which has permission to read clipboard in background
    fun injectClipboardEvent(text: String) {
        if (!isStarted) return
        // No-op for now, waiting for implementation
    }
    
    fun syncClipboard(context: Context? = null) {
        if (::clipboardMonitor.isInitialized && isFeatureEnabled()) {
            clipboardMonitor.checkNow(context)
        }
    }
    
    fun onExternalEvent(event: com.autonion.automationcompanion.features.cross_device_automation.domain.RawEvent) {
        if (!isStarted || !::eventPipeline.isInitialized) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
             eventPipeline.onEventReceived(event)
        }
    }
    
    companion object {
        @Volatile
        private var instance: CrossDeviceAutomationManager? = null

        fun getInstance(context: Context): CrossDeviceAutomationManager {
            return instance ?: synchronized(this) {
                instance ?: CrossDeviceAutomationManager(context.applicationContext).also { 
                    it.initialize()
                    instance = it 
                }
            }
        }
    }
}
