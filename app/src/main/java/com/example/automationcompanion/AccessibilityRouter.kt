package com.example.automationcompanion

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

object AccessibilityRouter {

    private val features = mutableSetOf<AccessibilityFeature>()

    fun register(feature: AccessibilityFeature) {
        features.add(feature)
    }

    fun unregister(feature: AccessibilityFeature) {
        features.remove(feature)
    }

    fun onServiceConnected(service: AccessibilityService) {
        features.forEach { it.onServiceConnected(service) }
    }

    fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {
        features.forEach { it.onEvent(service, event) }
    }
}

interface AccessibilityFeature {
    fun onServiceConnected(service: AccessibilityService) {}
    fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {}
}
