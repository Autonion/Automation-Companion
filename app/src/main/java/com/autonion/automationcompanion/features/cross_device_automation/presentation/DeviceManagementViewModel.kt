package com.autonion.automationcompanion.features.cross_device_automation.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.autonion.automationcompanion.features.cross_device_automation.domain.Device
import com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DeviceManagementViewModel(
    private val manager: CrossDeviceAutomationManager
) : ViewModel() {

    private val _isFeatureEnabled = MutableStateFlow(manager.isFeatureEnabled())
    val isFeatureEnabled: StateFlow<Boolean> = _isFeatureEnabled.asStateFlow()

    // Directly expose the manager's reactive StateFlow — updates from both
    // local toggles and remote desktop pushes are reflected automatically.
    val isClipboardSyncEnabled: StateFlow<Boolean> = manager.clipboardSyncStateFlow

    /// Non-null when the connected desktop agent requires a newer companion version.
    val compatibilityWarning: StateFlow<String?> = manager.compatibilityWarning

    val activePairingDevice: StateFlow<Device?> = manager.activePairingDevice
    val pairingError: StateFlow<String?> = manager.pairingError

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    init {
        viewModelScope.launch {
            manager.deviceRepository.getAllDevices().collect {
                _devices.value = it
            }
        }
    }

    fun toggleFeature(enabled: Boolean) {
        manager.setFeatureEnabled(enabled)
        _isFeatureEnabled.value = enabled
    }

    fun toggleClipboardSync(enabled: Boolean) {
        manager.setClipboardSyncEnabled(enabled)
    }

    fun updateDeviceRole(deviceId: String, role: DeviceRole) {
        viewModelScope.launch {
             val device = manager.deviceRepository.getDeviceById(deviceId)
             if (device != null) {
                 manager.deviceRepository.addOrUpdateDevice(device.copy(role = role))
             }
        }
    }

    fun toggleDeviceSelection(deviceId: String) {
        viewModelScope.launch {
            manager.deviceRepository.toggleDeviceSelection(deviceId)
        }
    }

    fun submitPairingPin(deviceId: String, pin: String) {
        manager.submitPairingPin(deviceId, pin)
    }

    fun dismissPairing() {
        manager.dismissPairing()
    }

    fun unpairDevice(deviceId: String) {
        manager.unpairDevice(deviceId)
    }
}
