package com.autonion.automationcompanion.features.cross_device_automation.data

import com.autonion.automationcompanion.features.cross_device_automation.domain.Device
import com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class InMemoryDeviceRepository : DeviceRepository {
    private val _devices = MutableStateFlow<List<Device>>(emptyList())

    override fun getAllDevices(): Flow<List<Device>> = _devices.asStateFlow()

    override suspend fun getDeviceById(id: String): Device? {
        return _devices.value.find { it.id == id }
    }

    override suspend fun addOrUpdateDevice(device: Device) {
        _devices.update { currentList ->
            val existingIndex = currentList.indexOfFirst { it.id == device.id }
            if (existingIndex >= 0) {
                val mutableList = currentList.toMutableList()
                mutableList[existingIndex] = device
                mutableList
            } else {
                currentList + device
            }
        }
    }

    override suspend fun removeDevice(id: String) {
        _devices.update { currentList ->
            currentList.filterNot { it.id == id }
        }
    }
}
