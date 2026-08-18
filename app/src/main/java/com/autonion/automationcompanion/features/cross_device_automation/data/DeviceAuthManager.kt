package com.autonion.automationcompanion.features.cross_device_automation.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import java.util.UUID

/**
 * Generates and securely persists this companion device's identity (UUID & secret token)
 * and tracks trusted paired desktop agents.
 */
class DeviceAuthManager(private val context: Context) {

    companion object {
        private const val TAG = "DeviceAuthManager"
        private const val PREFS_FILE = "autonion_secure_auth_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_SECRET = "device_secret"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_PAIRED_AGENTS = "paired_agent_ids"
    }

    private val prefs: SharedPreferences by lazy {
        try {
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
            Log.e(TAG, "Failed to initialize EncryptedSharedPreferences, falling back to standard prefs", e)
            context.getSharedPreferences(PREFS_FILE + "_fallback", Context.MODE_PRIVATE)
        }
    }

    val deviceId: String
        get() {
            var id = prefs.getString(KEY_DEVICE_ID, null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }

    val deviceSecret: String
        get() {
            var secret = prefs.getString(KEY_DEVICE_SECRET, null)
            if (secret == null) {
                val randomBytes = ByteArray(32)
                SecureRandom().nextBytes(randomBytes)
                secret = Base64.encodeToString(randomBytes, Base64.NO_WRAP)
                prefs.edit().putString(KEY_DEVICE_SECRET, secret).apply()
            }
            return secret
        }

    val deviceName: String
        get() {
            val customName = prefs.getString(KEY_DEVICE_NAME, null)
            if (!customName.isNullOrBlank()) return customName

            val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val model = Build.MODEL
            return if (model.startsWith(manufacturer, ignoreCase = true)) {
                model
            } else {
                "$manufacturer $model"
            }
        }

    fun isAgentPaired(agentId: String): Boolean {
        if (agentId.isBlank()) return false
        val paired = prefs.getStringSet(KEY_PAIRED_AGENTS, emptySet()) ?: emptySet()
        return paired.contains(agentId)
    }

    fun markAgentPaired(agentId: String) {
        if (agentId.isBlank()) return
        val current = prefs.getStringSet(KEY_PAIRED_AGENTS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(agentId)
        prefs.edit().putStringSet(KEY_PAIRED_AGENTS, current).apply()
        Log.d(TAG, "Marked agent as paired: $agentId")
    }

    fun unpairAgent(agentId: String) {
        if (agentId.isBlank()) return
        val current = prefs.getStringSet(KEY_PAIRED_AGENTS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.remove(agentId)) {
            prefs.edit().putStringSet(KEY_PAIRED_AGENTS, current).apply()
            Log.d(TAG, "Removed paired agent: $agentId")
        }
    }
}
