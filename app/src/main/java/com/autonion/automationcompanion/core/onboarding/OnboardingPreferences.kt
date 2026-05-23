package com.autonion.automationcompanion.core.onboarding

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized manager for all onboarding and user-education state.
 *
 * Tracks:
 * - Whether the first-launch setup wizard has been completed
 * - Per-feature "first visit" tip dismissals
 * - Getting Started checklist dismissal
 * - Setup progress milestones (AI connected, first automation created)
 *
 * Uses SharedPreferences under "autonion_onboarding".
 */
object OnboardingPreferences {

    private const val PREFS_NAME = "autonion_onboarding"

    // Keys
    private const val KEY_ONBOARDING_COMPLETED = "has_completed_onboarding"
    private const val KEY_CHECKLIST_DISMISSED = "is_getting_started_dismissed"
    private const val KEY_HAS_CONNECTED_AI = "has_connected_ai"
    private const val KEY_HAS_CREATED_AUTOMATION = "has_created_first_automation"
    private const val KEY_TIP_PREFIX = "has_seen_tip_"

    @Volatile
    private var instance: OnboardingPreferences? = null

    private lateinit var prefs: SharedPreferences

    // Compose State backed properties for real-time reactivity
    private val _hasConnectedAI = androidx.compose.runtime.mutableStateOf(false)
    private val _hasCreatedFirstAutomation = androidx.compose.runtime.mutableStateOf(false)

    fun getInstance(context: Context): OnboardingPreferences {
        if (!::prefs.isInitialized) {
            synchronized(this) {
                if (!::prefs.isInitialized) {
                    prefs = context.applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    // Sync initial values from SharedPreferences
                    _hasConnectedAI.value = prefs.getBoolean(KEY_HAS_CONNECTED_AI, false)
                    _hasCreatedFirstAutomation.value = prefs.getBoolean(KEY_HAS_CREATED_AUTOMATION, false)
                }
            }
        }
        return this
    }

    // ─── First-Launch Wizard ──────────────────────────────

    var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    // ─── Getting Started Checklist ────────────────────────

    var isGettingStartedDismissed: Boolean
        get() = prefs.getBoolean(KEY_CHECKLIST_DISMISSED, false)
        set(value) = prefs.edit().putBoolean(KEY_CHECKLIST_DISMISSED, value).apply()

    var hasConnectedAI: Boolean
        get() = _hasConnectedAI.value
        set(value) {
            prefs.edit().putBoolean(KEY_HAS_CONNECTED_AI, value).apply()
            _hasConnectedAI.value = value
        }

    var hasCreatedFirstAutomation: Boolean
        get() = _hasCreatedFirstAutomation.value
        set(value) {
            prefs.edit().putBoolean(KEY_HAS_CREATED_AUTOMATION, value).apply()
            _hasCreatedFirstAutomation.value = value
        }

    /**
     * Returns true if all Getting Started checklist items are complete.
     */
    fun isChecklistComplete(): Boolean {
        return hasConnectedAI && hasCreatedFirstAutomation
    }

    // ─── Per-Feature Tips ─────────────────────────────────

    /**
     * Check if the first-visit tip for a specific feature has been shown.
     *
     * @param featureId One of: gesture_recording, flow_builder, visual_trigger,
     *                  cross_device, omni_chat, screen_ml, semantic_automation,
     *                  system_context, debugger
     */
    fun hasTipBeenSeen(featureId: String): Boolean {
        return prefs.getBoolean(KEY_TIP_PREFIX + featureId, false)
    }

    /**
     * Mark the first-visit tip for a feature as seen (won't show again).
     */
    fun markTipSeen(featureId: String) {
        prefs.edit().putBoolean(KEY_TIP_PREFIX + featureId, true).apply()
    }

    /**
     * Reset all tip states (for debugging / testing).
     */
    fun resetAllTips() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(KEY_TIP_PREFIX) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    /**
     * Full reset — clears all onboarding state (for testing).
     */
    fun resetAll() {
        prefs.edit().clear().apply()
        _hasConnectedAI.value = false
        _hasCreatedFirstAutomation.value = false
    }
}
