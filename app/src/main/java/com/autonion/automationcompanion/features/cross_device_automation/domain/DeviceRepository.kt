package com.autonion.automationcompanion.features.cross_device_automation.domain

import kotlinx.coroutines.flow.Flow

interface DeviceRepository {
    fun getAllDevices(): Flow<List<Device>>
    suspend fun getDeviceById(id: String): Device?
    suspend fun addOrUpdateDevice(device: Device)
    suspend fun removeDevice(id: String)
}
