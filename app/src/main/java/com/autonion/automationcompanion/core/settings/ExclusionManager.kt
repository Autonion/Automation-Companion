package com.autonion.automationcompanion.core.settings

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.autonion.automationcompanion.features.gesture_recording_playback.overlay.AutomationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ExclusionManager {
    private const val PREFS_NAME = "exclusion_prefs"
    private const val KEY_EXCLUDED_PACKAGES = "excluded_packages"
    private const val KEY_STRICT_MODE = "strict_mode_enabled"
    private const val KEY_BANKING_MODE = "banking_mode_enabled"

    private val _excludedPackages = MutableStateFlow<Set<String>>(emptySet())
    val excludedPackages: StateFlow<Set<String>> = _excludedPackages.asStateFlow()

    private val _isStrictMode = MutableStateFlow(false)
    val isStrictMode: StateFlow<Boolean> = _isStrictMode.asStateFlow()

    private val _isBankingMode = MutableStateFlow(false)
    val isBankingMode: StateFlow<Boolean> = _isBankingMode.asStateFlow()

    private var sharedPreferences: SharedPreferences? = null

    fun initialize(context: Context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = sharedPreferences?.getStringSet(KEY_EXCLUDED_PACKAGES, emptySet()) ?: emptySet()
        _excludedPackages.value = saved

        val strictMode = sharedPreferences?.getBoolean(KEY_STRICT_MODE, false) ?: false
        _isStrictMode.value = strictMode

        // Check actual component state on init
        val componentState = context.packageManager.getComponentEnabledSetting(
            ComponentName(context, AutomationService::class.java)
        )
        _isBankingMode.value = (componentState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
    }

    fun setStrictMode(enabled: Boolean) {
        _isStrictMode.value = enabled
        sharedPreferences?.edit()?.putBoolean(KEY_STRICT_MODE, enabled)?.apply()
    }

    /**
     * Toggles "Banking Mode" — dynamically disables or enables the
     * AutomationService component. When disabled, banking apps can't
     * detect the accessibility service at all (it vanishes from the system).
     * When re-enabled, the service reappears in Accessibility Settings.
     */
    fun setBankingMode(context: Context, enabled: Boolean) {
        val componentName = ComponentName(context, AutomationService::class.java)
        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        context.packageManager.setComponentEnabledSetting(
            componentName,
            newState,
            PackageManager.DONT_KILL_APP
        )
        _isBankingMode.value = enabled
        sharedPreferences?.edit()?.putBoolean(KEY_BANKING_MODE, enabled)?.apply()
    }

    fun addPackage(context: Context, packageName: String) {
        val current = _excludedPackages.value.toMutableSet()
        if (current.add(packageName)) {
            _excludedPackages.value = current
            save(context, current)
        }
    }

    fun removePackage(context: Context, packageName: String) {
        val current = _excludedPackages.value.toMutableSet()
        if (current.remove(packageName)) {
            _excludedPackages.value = current
            save(context, current)
        }
    }

    fun isExcluded(packageName: String): Boolean {
        return _excludedPackages.value.contains(packageName)
    }

    private fun save(context: Context, packages: Set<String>) {
        sharedPreferences?.edit()?.putStringSet(KEY_EXCLUDED_PACKAGES, packages)?.apply()
    }
}
