package com.autonion.automationcompanion

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

object AccessibilityRouter {

    private val features = mutableSetOf<AccessibilityFeature>()
    private var connectedServiceRef: java.lang.ref.WeakReference<AccessibilityService>? = null

    fun register(feature: AccessibilityFeature) {
        features.add(feature)
        // If service is already connected, notify the new feature immediately
        connectedServiceRef?.get()?.let { feature.onServiceConnected(it) }
    }

    fun unregister(feature: AccessibilityFeature) {
        features.remove(feature)
    }

    fun onServiceConnected(service: AccessibilityService) {
        connectedServiceRef = java.lang.ref.WeakReference(service)
        features.forEach { it.onServiceConnected(service) }
    }
    
    fun onServiceDestroyed() {
        connectedServiceRef = null
        features.forEach { it.onServiceDisconnected() }
    }
    
    fun isServiceConnected(): Boolean = connectedServiceRef?.get() != null

    fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {
        features.forEach { it.onEvent(service, event) }
    }
}

interface AccessibilityFeature {
    fun onServiceConnected(service: AccessibilityService) {}
    fun onServiceDisconnected() {}
    fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {}
}
