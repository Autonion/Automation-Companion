package com.autonion.automationcompanion.features.screen_understanding_ml.model

import com.google.gson.annotations.SerializedName
import java.util.UUID

enum class ExecutionMode {
    @SerializedName("STRICT")
    STRICT,   // All steps must succeed in order
    @SerializedName("FLEXIBLE")
    FLEXIBLE  // Execute steps if found, skip otherwise
}

enum class ScopeType {
    @SerializedName("GLOBAL")
    GLOBAL,
    @SerializedName("APP_SPECIFIC")
    APP_SPECIFIC
}

// These names are the on-disk preset format and must survive release obfuscation.
data class AutomationPreset(
    @field:SerializedName("id")
    val id: String = UUID.randomUUID().toString(),
    @field:SerializedName("name")
    val name: String,
    @field:SerializedName("scope")
    val scope: ScopeType,
    @field:SerializedName("targetPackageName")
    val targetPackageName: String? = null,
    @field:SerializedName("executionMode")
    val executionMode: ExecutionMode,
    @field:SerializedName("steps")
    val steps: List<AutomationStep>,
    @field:SerializedName("createdAt")
    val createdAt: Long = System.currentTimeMillis()
)
