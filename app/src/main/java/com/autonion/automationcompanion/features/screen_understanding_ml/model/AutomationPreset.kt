package com.autonion.automationcompanion.features.screen_understanding_ml.model

import java.util.UUID

enum class ExecutionMode {
    STRICT,   // All steps must succeed in order
    FLEXIBLE  // Execute steps if found, skip otherwise
}

enum class ScopeType {
    GLOBAL,
    APP_SPECIFIC
}

data class AutomationPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val scope: ScopeType,
    val targetPackageName: String? = null,
    val executionMode: ExecutionMode,
    val steps: List<AutomationStep>,
    val createdAt: Long = System.currentTimeMillis()
)
