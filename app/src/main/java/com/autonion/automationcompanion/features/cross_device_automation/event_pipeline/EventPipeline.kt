package com.autonion.automationcompanion.features.cross_device_automation.event_pipeline

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.cross_device_automation.domain.RawEvent
import com.autonion.automationcompanion.features.cross_device_automation.rules.RuleEngine
import com.autonion.automationcompanion.features.cross_device_automation.tagging.TaggingSystem

class EventPipeline(
    private val context: Context,
    private val enricher: EventEnricher,
    private val taggingSystem: TaggingSystem,
    private val broadcastStrategy: ((RawEvent) -> Unit)? = null
) : EventReceiver {

    override suspend fun onEventReceived(event: RawEvent) {
        Log.d("EventPipeline", "Received event: ${event.type}")
        DebugLogger.info(
            context, LogCategory.CROSS_DEVICE_SYNC,
            "Event: ${event.type}",
            "Processing from ${event.sourceDeviceId}",
            "EventPipeline"
        )
        
        // 0. Publish Raw Event
        EventBus.publish(event)
        
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
        EventBus.publish(enrichedEvent)
        
        // 2. Tag
        val taggedEvent = taggingSystem.tag(enrichedEvent)
        Log.d("EventPipeline", "Tagged event: ${taggedEvent.detectedTags}")
        
        // 3. Publish Tagged Event (Decoupled from Rule Engine)
        EventBus.publish(taggedEvent)
    }
}

