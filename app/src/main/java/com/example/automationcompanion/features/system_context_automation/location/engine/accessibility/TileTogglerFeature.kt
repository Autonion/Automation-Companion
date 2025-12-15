package com.example.automationcompanion.features.system_context_automation.location.engine.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.example.automationcompanion.AccessibilityFeature

object TileToggleFeature : AccessibilityFeature {

    private var tileToggler: TileTogglerHelper ?= null

    override fun onServiceConnected(service: AccessibilityService) {
        tileToggler = TileTogglerHelper(service)
    }

    fun toggleLocation(onComplete: (Boolean) -> Unit) {
        val h = tileToggler
        if (h == null) {
            onComplete(false) // service not ready
        } else {
            h.attemptToggleLocation(onComplete)
        }
    }
}
