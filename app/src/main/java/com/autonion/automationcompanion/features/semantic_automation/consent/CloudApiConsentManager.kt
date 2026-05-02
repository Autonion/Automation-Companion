package com.autonion.automationcompanion.features.semantic_automation.consent

import android.content.Context

/**
 * Manages user consent for Cloud API usage.
 *
 * The consent flag is stored in standard SharedPreferences (not encrypted)
 * because it's a boolean preference, not sensitive data.
 *
 * Consent must be explicitly given before Cloud API mode can be activated.
 */
object CloudApiConsentManager {

    private const val PREFS_NAME = "cloud_api_consent_prefs"
    private const val KEY_CONSENT_GIVEN = "cloud_api_consent_given"
    private const val KEY_CONSENT_TIMESTAMP = "cloud_api_consent_timestamp"

    fun hasConsent(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONSENT_GIVEN, false)
    }

    fun setConsent(context: Context, accepted: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONSENT_GIVEN, accepted)
            .putLong(KEY_CONSENT_TIMESTAMP, if (accepted) System.currentTimeMillis() else 0L)
            .apply()
    }

    fun revokeConsent(context: Context) {
        setConsent(context, false)
    }
}
