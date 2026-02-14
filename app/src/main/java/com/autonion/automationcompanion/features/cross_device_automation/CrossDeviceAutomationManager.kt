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
import kotlinx.coroutines.flow.first

class CrossDeviceAutomationManager(private val context: Context) : NetworkingManager.NetworkingListener {
    
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
                // Route through Manager's handler to catch specific logic (like Clipboard Sync)
                onExternalEvent(event)
            }
        }
        
        networkingManager = NetworkingManager(deviceRepository, eventReceiverProxy)
        networkingManager.setListener(this)
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

    private val PREF_CLIPBOARD_SYNC_ENABLED = "clipboard_sync_enabled"

    fun isClipboardSyncEnabled(): Boolean {
        return prefs.getBoolean(PREF_CLIPBOARD_SYNC_ENABLED, true)
    }

    fun setClipboardSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_CLIPBOARD_SYNC_ENABLED, enabled).apply()
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
        if (!isStarted || !isClipboardSyncEnabled()) return
        // No-op for now, waiting for implementation
    }

    // --- Rule Sync Logic ---

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    fun syncRulesToDesktop() {
        // Launch in IO scope to avoid blocking main thread
        scope.launch {
            val allRules = ruleRepository.getAllRules().first()
            val desktopRules = allRules.filter { it.trigger.eventType == "browser.navigation" }
            
            val payloadRules = desktopRules.mapNotNull { rule ->
                // Map the first condition to the criteria
                val condition = rule.conditions.firstOrNull() as? com.autonion.automationcompanion.features.cross_device_automation.domain.RuleCondition.PayloadContains
                if (condition != null) {
                    val criteriaType = if (condition.key == "url") "url_contains" else "category"
                    mapOf(
                        "id" to rule.id,
                        "criteria" to mapOf(
                            "type" to criteriaType,
                            "value" to condition.value
                        )
                    )
                } else null
            }

            if (payloadRules.isNotEmpty()) {
                val command = mapOf(
                    "type" to "register_triggers",
                    "payload" to mapOf("rules" to payloadRules)
                )
                
                Log.d("CrossDeviceManager", "Syncing ${payloadRules.size} rules to desktop")
                networkingManager.broadcast(command)
            }
        }
    }

    // --- Networking Events ---

    override fun onDeviceConnected(device: com.autonion.automationcompanion.features.cross_device_automation.domain.Device) {
        Log.d("CrossDeviceManager", "Device connected: ${device.name}")
        syncRulesToDesktop() // Sync rules immediately on connection
    }

    override fun onDeviceDisconnected(deviceId: String) {
        Log.d("CrossDeviceManager", "Device disconnected: $deviceId")
    }

    override fun onMessageReceived(deviceId: String, message: String) {
        try {
            val json = org.json.JSONObject(message)
            val type = json.optString("type")
            
            if (type == "rule_triggered") {
                val payload = json.optJSONObject("payload")
                val ruleId = payload?.optString("rule_id")
                if (ruleId != null) {
                    Log.d("CrossDeviceManager", "Received remote trigger for rule: $ruleId")
                    executeRuleById(ruleId)
                }
                return
            }

            // Existing logic for RawEvent/Events
            // Note: NetworkingManager now tries to parse and send to EventReceiver directly if it looks like an event.
            // But if we want to handle it here explicitly or if NetworkingManager failed to parse it as an event:
            if (type.startsWith("clipboard.") || type.contains("event")) {
                 try {
                     val event = com.google.gson.Gson().fromJson(message, com.autonion.automationcompanion.features.cross_device_automation.domain.RawEvent::class.java)
                     if (event != null) {
                        if (event.type == "clipboard.text_copied") {
                             onExternalEvent(event) // Special handling for clipboard
                        } else {
                             scope.launch {
                                 eventPipeline.onEventReceived(event)
                             }
                        }
                     }
                 } catch (e: Exception) {
                     Log.w("CrossDeviceManager", "Failed to parse as event in onMessageReceived: ${e.message}")
                 }
            }
        } catch (e: Exception) {
            Log.e("CrossDeviceManager", "Error parsing message", e)
        }
    }

    private fun executeRuleById(ruleId: String) {
         scope.launch {
             val rule = ruleRepository.getAllRules().first().find { it.id == ruleId }
             if (rule != null) {
                 Log.i("CrossDeviceManager", "Executing remote-triggered rule: ${rule.name}")
                 rule.actions.forEach { action ->
                     actionExecutor.execute(action)
                 }
             } else {
                 Log.w("CrossDeviceManager", "Rule not found for ID: $ruleId")
             }
         }
    }
    fun syncClipboard(context: Context? = null) {
        if (::clipboardMonitor.isInitialized && isFeatureEnabled() && isClipboardSyncEnabled()) {
            clipboardMonitor.checkNow(context)
        }
    }

    fun onExternalEvent(event: com.autonion.automationcompanion.features.cross_device_automation.domain.RawEvent) {
        if (!isStarted || !::eventPipeline.isInitialized) return
        
        // Incoming Clipboard Logic (Desktop -> Android)
        if (event.type == "clipboard.text_copied" && 
            isClipboardSyncEnabled() && 
            event.sourceDeviceId != "local") {
            
            val text = event.payload["text"]
            if (!text.isNullOrEmpty()) {
                Log.d("CrossDeviceManager", "Received Remote Clipboard Event: $text")
                // Execute on Main Thread as ClipboardManager requires it (sometimes) or just to be safe
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    actionExecutor.execute(
                        com.autonion.automationcompanion.features.cross_device_automation.domain.RuleAction(
                            type = "set_clipboard",
                            parameters = mapOf("text" to text),
                            targetDeviceId = "local"
                        )
                    )
                }
                return // Stop processing to avoid loops or redundant rule checks
            }
        }

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
