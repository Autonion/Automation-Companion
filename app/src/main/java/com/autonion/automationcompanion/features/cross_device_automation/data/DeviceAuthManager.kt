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
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.autonion.automationcompanion.features.cross_device_automation.domain.Device
import com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceStatus
import com.autonion.automationcompanion.features.cross_device_automation.domain.ConnectionState
import com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceRole

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

    fun rotateSecret(): String {
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        val newSecret = Base64.encodeToString(randomBytes, Base64.NO_WRAP)
        prefs.edit().putString(KEY_DEVICE_SECRET, newSecret).apply()
        Log.i(TAG, "Rotated companion device secret")
        return newSecret
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

    /** Remember only trusted endpoints; every restored connection must authenticate again. */
    @Synchronized
    fun rememberDevice(device: Device) {
        val agentId = device.agentId ?: return
        if (!isAgentPaired(agentId) || device.ipAddress.isBlank() || device.port !in 1..65535) return
        val key = "agent_endpoint_$agentId"
        // Explicit keys keep the on-disk format stable across optimized app builds.
        val json = Gson().toJson(mapOf("id" to device.id, "agentId" to agentId,
            "name" to device.name, "ip" to device.ipAddress, "port" to device.port,
            "selected" to device.isSelected, "serviceOnly" to device.isServiceOnly, "role" to device.role.name))
        if (prefs.getString(key, null) != json) prefs.edit().putString(key, json).apply()
    }

    @Synchronized
    fun rememberedDevices(): List<Device> =
        (prefs.getStringSet(KEY_PAIRED_AGENTS, emptySet()) ?: emptySet()).mapNotNull { agentId ->
            runCatching {
                val json = prefs.getString("agent_endpoint_$agentId", null) ?: return@mapNotNull null
                val data = Gson().fromJson(json, JsonObject::class.java)
                Device(id = data.get("id").asString, agentId = data.get("agentId").asString,
                    name = data.get("name").asString, ipAddress = data.get("ip").asString,
                    port = data.get("port").asInt, isSelected = data.get("selected").asBoolean,
                    isServiceOnly = data.get("serviceOnly").asBoolean,
                    role = DeviceRole.valueOf(data.get("role").asString)).takeIf {
                    it.agentId == agentId && it.ipAddress.isNotBlank() && it.port in 1..65535
                }?.copy(status = DeviceStatus.OFFLINE, connectionState = ConnectionState.DISCONNECTED,
                    isPaired = true, isPairingRequired = false)
            }.getOrNull()
        }

    @Synchronized
    fun markAgentPaired(agentId: String, secret: String = secretForAgent(agentId)) {
        if (agentId.isBlank()) return
        val current = prefs.getStringSet(KEY_PAIRED_AGENTS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(agentId)
        prefs.edit().putStringSet(KEY_PAIRED_AGENTS, current)
            .putString("agent_secret_$agentId", secret).apply()
        Log.d(TAG, "Marked agent as paired: $agentId")
    }

    /** Existing pairings retain their legacy token; new pairings get independent credentials. */
    @Synchronized
    fun secretForAgent(agentId: String?): String {
        if (agentId.isNullOrBlank()) return deviceSecret
        val key = "agent_secret_$agentId"
        prefs.getString(key, null)?.let { return it }
        val secret = if (isAgentPaired(agentId)) deviceSecret else newSecret()
        prefs.edit().putString(key, secret).apply()
        return secret
    }

    private fun newSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    @Synchronized
    fun unpairAgent(agentId: String) {
        if (agentId.isBlank()) return
        val current = prefs.getStringSet(KEY_PAIRED_AGENTS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(agentId)
        prefs.edit().putStringSet(KEY_PAIRED_AGENTS, current)
            .putString("agent_secret_$agentId", newSecret()).apply()
        Log.d(TAG, "Removed paired agent: $agentId")
    }
}
