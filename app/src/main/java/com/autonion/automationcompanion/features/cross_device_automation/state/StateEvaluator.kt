package com.autonion.automationcompanion.features.cross_device_automation.state

import android.util.Log
import com.autonion.automationcompanion.features.cross_device_automation.domain.EnrichedEvent
import com.autonion.automationcompanion.features.cross_device_automation.event_pipeline.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Represents a change in system state.
 */
data class StateChangeEvent(
    val key: String,
    val newValue: String,
    val previousValue: String?
)

/**
 * Maintains the persistent state of the automation system.
 * Listens to Tagged Events and updates state accordingly.
 */
class StateEvaluator(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val stateStore = mutableMapOf<String, String>()

    init {
        scope.launch {
            EventBus.events.collect { event ->
                if (event is EnrichedEvent) {
                    evaluateEvent(event)
                }
            }
        }
    }

    private suspend fun evaluateEvent(event: EnrichedEvent) {
        // Example Logic: Tag "meeting" sets "context" = "meeting"
        event.detectedTags.forEach { tag ->
            when (tag) {
                "meeting" -> updateState("context", "meeting")
                "video" -> updateState("media_mode", "active")
                "coding" -> updateState("work_mode", "active")
                "social" -> updateState("context", "social")
            }
        }
        
        // Example: Payload specific state updates
        if (event.originalEvent.type == "device.online") {
            updateState("device_${event.originalEvent.sourceDeviceId}_status", "online")
        }
    }

    private suspend fun updateState(key: String, newValue: String) {
        val previousValue = stateStore[key]
        if (previousValue != newValue) {
            stateStore[key] = newValue
            Log.d("StateEvaluator", "State Changed: $key = $newValue (was $previousValue)")
            
            // Publish State Change Event
            EventBus.publish(StateChangeEvent(key, newValue, previousValue))
        }
    }
    
    fun getCurrentState(): Map<String, String> = stateStore.toMap()
}
