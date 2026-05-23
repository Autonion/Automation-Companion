package com.autonion.automationcompanion

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AccessibilityRouter {

    private val features = mutableSetOf<AccessibilityFeature>()
    private var connectedServiceRef: java.lang.ref.WeakReference<AccessibilityService>? = null

    // Observable connection state for Compose UI
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

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
        _isConnected.value = true
        features.forEach { it.onServiceConnected(service) }
    }
    
    fun onServiceDestroyed() {
        connectedServiceRef = null
        _isConnected.value = false
        features.forEach { it.onServiceDisconnected() }
    }
    
    fun isServiceConnected(): Boolean = connectedServiceRef?.get() != null

    fun getService(): AccessibilityService? = connectedServiceRef?.get()

    fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {
        features.forEach { it.onEvent(service, event) }
    }
}

interface AccessibilityFeature {
    fun onServiceConnected(service: AccessibilityService) {}
    fun onServiceDisconnected() {}
    fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {}
}
