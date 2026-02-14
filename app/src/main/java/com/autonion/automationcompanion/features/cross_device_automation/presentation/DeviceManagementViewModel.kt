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

    fun updateDeviceRole(deviceId: String, role: DeviceRole) {
        viewModelScope.launch {
             val device = manager.deviceRepository.getDeviceById(deviceId)
             if (device != null) {
                 manager.deviceRepository.addOrUpdateDevice(device.copy(role = role))
             }
        }
    }
}
