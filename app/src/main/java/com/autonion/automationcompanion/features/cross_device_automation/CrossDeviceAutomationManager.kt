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
    
    // Networking
    // Creates a circular dependency if NetworkingManager needs EventReceiver (EventPipeline)
    // and EventPipeline needs RuleEngine which needs ActionExecutor which needs NetworkingManager.
    // Solution: Break cycle or use `lateinit` / setter injection.
    
    private lateinit var networkingManager: NetworkingManager
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var ruleEngine: RuleEngine
    private lateinit var eventPipeline: EventPipeline
    private lateinit var hostManager: HostManager
    private lateinit var clipboardMonitor: com.autonion.automationcompanion.features.cross_device_automation.event_source.ClipboardMonitor
    private var isStarted = false

    fun initialize() {
        // Initialize components in order
        // 1. NetworkingManager needs EventReceiver (Pipeline), pass placeholder or use lazy/setter pipeline
        // Let's create components first
        
        // We defer initialization to avoid 'this' escape in constructor if possible, or build graph here.
        
        // Circular dependency resolution:
        // Networking -> needs EventReceiver
        // EventPipeline (Receiver) -> needs RuleEngine
        // RuleEngine -> needs ActionExecutor
        // ActionExecutor -> needs NetworkingManager

        // Construct NetworkingManager with a proxy receiver or defer
        // Or setter injection.
        
        // Let's assume we can construct NetworkingManager without receiver first? No, constructor param.
        // We can make EventPipeline implement EventReceiver and pass it later? No.
        
        // Let's use a forward reference via a lambda or object.
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
        eventPipeline = EventPipeline(enricher, taggingSystem, ruleEngine) { event ->
            // Broadcast strategy: send local events to all connected devices
            if (::networkingManager.isInitialized) {
                networkingManager.broadcast(event)
            }
        }
        
        hostManager = HostManager(context, deviceRepository)
        
        clipboardMonitor = com.autonion.automationcompanion.features.cross_device_automation.event_source.ClipboardMonitor(context, eventPipeline)
        com.autonion.automationcompanion.AccessibilityRouter.register(clipboardMonitor)
    }

    fun start() {
        if (isStarted) return
        isStarted = true
        hostManager.startDiscovery()
        networkingManager.start()
        Log.d("CrossDeviceManager", "Service started")
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        hostManager.stopDiscovery()
        networkingManager.stop()
        Log.d("CrossDeviceManager", "Service stopped")
    }

    // Called by AutomationService (Accessibility Service) which has permission to read clipboard in background
    fun injectClipboardEvent(text: String) {
        if (!isStarted) return
        // We can reconstruct the event here or pass RawEvent? 
        // Let's reuse the logic if possible, but actually ClipboardMonitor creates the event.
        // So we can just accept the event if we want, or text.
        // Let's accept the RawEvent to minimize changes.
    }
    
    fun syncClipboard(context: Context? = null) {
        if (::clipboardMonitor.isInitialized) {
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
