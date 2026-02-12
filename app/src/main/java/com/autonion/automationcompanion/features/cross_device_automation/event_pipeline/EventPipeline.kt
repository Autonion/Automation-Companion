package com.autonion.automationcompanion.features.cross_device_automation.event_pipeline

import android.util.Log
import com.autonion.automationcompanion.features.cross_device_automation.domain.RawEvent
import com.autonion.automationcompanion.features.cross_device_automation.rules.RuleEngine
import com.autonion.automationcompanion.features.cross_device_automation.tagging.TaggingSystem

class EventPipeline(
    private val enricher: EventEnricher,
    private val taggingSystem: TaggingSystem,
    private val ruleEngine: RuleEngine,
    private val broadcastStrategy: ((RawEvent) -> Unit)? = null
) : EventReceiver {

    override suspend fun onEventReceived(event: RawEvent) {
        Log.d("EventPipeline", "Received event: ${event.type}")
        
        // Broadcast local events to connected devices
        if (event.sourceDeviceId == "local" && broadcastStrategy != null) {
            try {
                broadcastStrategy.invoke(event)
                Log.d("EventPipeline", "Broadcasted local event: ${event.type}")
            } catch (e: Exception) {
                Log.e("EventPipeline", "Failed to broadcast event", e)
            }
        }
        
        // 1. Enrich
        val enrichedEvent = enricher.enrich(event)
        
        // 2. Tag
        val taggedEvent = taggingSystem.tag(enrichedEvent)
        Log.d("EventPipeline", "Tagged event: ${taggedEvent.detectedTags}")
        
        // 3. Rule Engine
        ruleEngine.evaluate(taggedEvent)
    }
}
