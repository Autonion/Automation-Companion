package com.autonion.automationcompanion.features.cross_device_automation.domain

import kotlinx.serialization.Serializable

/**
 * Response message sent back from Desktop → Android
 * when a cross-device prompt is processed.
 */
@Serializable
data class PromptResponse(
    val transactionId: String,
    val status: ResponseStatus,
    val message: String,
    val data: Map<String, String>? = null
)

@Serializable
enum class ResponseStatus {
    /** Desktop has received and started processing the prompt */
    STARTED,
    /** Desktop is actively working on the task (provides step updates) */
    IN_PROGRESS,
    /** Desktop has completed the task successfully */
    COMPLETED,
    /** Desktop failed to complete the task */
    FAILED,
    /** Desktop has started a scheduled/recurring task */
    SCHEDULED,
    /** A scheduled task was cancelled */
    CANCELLED
}
