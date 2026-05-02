package com.autonion.automationcompanion.features.semantic_automation.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Provides encrypted SharedPreferences for storing sensitive data like API keys.
 *
 * Uses Android's EncryptedSharedPreferences backed by the Android Keystore,
 * ensuring API keys are encrypted at rest with AES-256 GCM.
 */
object SecurePrefsHelper {

    private const val TAG = "SecurePrefs"
    private const val PREFS_FILE = "cloud_api_secure_prefs"

    // ── Keys ──
    const val KEY_API_KEY = "cloud_api_key"
    const val KEY_BASE_URL = "cloud_api_base_url"
    const val KEY_MODEL_NAME = "cloud_api_model"
    const val KEY_PROVIDER_ID = "cloud_api_provider_id"

    @Volatile
    private var instance: SharedPreferences? = null

    /**
     * Returns the encrypted SharedPreferences singleton.
     * Falls back to standard SharedPreferences if encryption fails (e.g. rooted device).
     */
    fun getSecurePrefs(context: Context): SharedPreferences {
        return instance ?: synchronized(this) {
            instance ?: createEncryptedPrefs(context).also { instance = it }
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences, falling back to standard", e)
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    // ─── Convenience Methods ─────────────────────────────────

    fun saveApiKey(context: Context, key: String) {
        getSecurePrefs(context).edit().putString(KEY_API_KEY, key).apply()
    }

    fun getApiKey(context: Context): String {
        return getSecurePrefs(context).getString(KEY_API_KEY, "") ?: ""
    }

    fun saveBaseUrl(context: Context, url: String) {
        getSecurePrefs(context).edit().putString(KEY_BASE_URL, url).apply()
    }

    fun getBaseUrl(context: Context): String {
        return getSecurePrefs(context).getString(KEY_BASE_URL, "") ?: ""
    }

    fun saveModelName(context: Context, model: String) {
        getSecurePrefs(context).edit().putString(KEY_MODEL_NAME, model).apply()
    }

    fun getModelName(context: Context): String {
        return getSecurePrefs(context).getString(KEY_MODEL_NAME, "") ?: ""
    }

    fun saveProviderId(context: Context, providerId: String) {
        getSecurePrefs(context).edit().putString(KEY_PROVIDER_ID, providerId).apply()
    }

    fun getProviderId(context: Context): String {
        return getSecurePrefs(context).getString(KEY_PROVIDER_ID, "custom") ?: "custom"
    }

    fun clearAll(context: Context) {
        getSecurePrefs(context).edit().clear().apply()
    }
}
