package com.autonion.automationcompanion.core.backup

import kotlinx.serialization.Serializable

/**
 * Manifest included in every backup archive.
 * Describes what's inside and enables compatibility checks on import.
 */
@Serializable
data class BackupManifest(
    /** App version that created this backup (e.g. "1.0.1"). */
    val appVersion: String,

    /** Unix timestamp (millis) when the backup was created. */
    val backupTimestamp: Long,

    /** Whether the backup ZIP was encrypted before saving. */
    val isEncrypted: Boolean,

    /** Which feature data sets are included in this backup. */
    val includedFeatures: List<String>,

    /** Human-readable backup description (optional). */
    val description: String? = null
) {
    companion object {
        // Feature identifiers — used in includedFeatures list
        const val FEATURE_GESTURE_PRESETS = "gesture_presets"
        const val FEATURE_VISION_PRESETS = "vision_presets"
        const val FEATURE_VISION_IMAGES = "vision_images"
        const val FEATURE_ML_PRESETS = "ml_presets"
        const val FEATURE_FLOWS = "flows"
        const val FEATURE_FLOW_ASSETS = "flow_assets"
        const val FEATURE_SYSTEM_CONTEXT_DB = "system_context_db"
        const val FEATURE_EXCLUDED_APPS = "excluded_apps"

        val ALL_FEATURES = listOf(
            FEATURE_GESTURE_PRESETS,
            FEATURE_VISION_PRESETS,
            FEATURE_VISION_IMAGES,
            FEATURE_ML_PRESETS,
            FEATURE_FLOWS,
            FEATURE_FLOW_ASSETS,
            FEATURE_SYSTEM_CONTEXT_DB,
            FEATURE_EXCLUDED_APPS
        )
    }
}
