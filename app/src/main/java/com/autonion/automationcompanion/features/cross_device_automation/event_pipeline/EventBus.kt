package com.autonion.automationcompanion.features.cross_device_automation.event_pipeline

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Central Event Bus for the automation system.
 * Decouples components by allowing them to publish and subscribe to events asynchronously.
 */
object EventBus {
    private val _events = MutableSharedFlow<Any>(extraBufferCapacity = 64)
    val events: SharedFlow<Any> = _events.asSharedFlow()

    suspend fun publish(event: Any) {
        _events.emit(event)
    }
}
