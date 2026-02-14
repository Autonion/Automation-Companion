package com.autonion.automationcompanion.features.cross_device_automation.event_pipeline

import com.autonion.automationcompanion.features.cross_device_automation.domain.RawEvent

interface EventReceiver {
    suspend fun onEventReceived(event: RawEvent)
}
