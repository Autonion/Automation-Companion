package com.autonion.automationcompanion.features.cross_device_automation.data

import com.autonion.automationcompanion.features.cross_device_automation.domain.Device
import com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class InMemoryDeviceRepository : DeviceRepository {
    private val _devices = MutableStateFlow<List<Device>>(emptyList())

    override fun getAllDevices(): Flow<List<Device>> = _devices.asStateFlow()

    override fun getSelectedDevices(): Flow<List<Device>> =
        _devices.map { list -> list.filter { it.isSelected } }

    override suspend fun getDeviceById(id: String): Device? {
        return _devices.value.find { it.id == id }
    }

    override suspend fun addOrUpdateDevice(device: Device) {
        _devices.update { currentList ->
            val existingIndex = currentList.indexOfFirst { it.id == device.id }
            if (existingIndex >= 0) {
                val existing = currentList[existingIndex]
                val mutableList = currentList.toMutableList()
                // Preserve isSelected from existing device unless explicitly changed
                mutableList[existingIndex] = device.copy(isSelected = existing.isSelected)
                mutableList
            } else {
                // Auto-select if this is the first and only device
                val updatedDevice = if (currentList.isEmpty()) {
                    device.copy(isSelected = true)
                } else {
                    device
                }
                currentList + updatedDevice
            }
        }
    }

    override suspend fun removeDevice(id: String) {
        _devices.update { currentList ->
            currentList.filterNot { it.id == id }
        }
    }

    override suspend fun toggleDeviceSelection(id: String) {
        _devices.update { currentList ->
            currentList.map { device ->
                if (device.id == id) {
                    device.copy(isSelected = !device.isSelected)
                } else {
                    device
                }
            }
        }
    }
}
