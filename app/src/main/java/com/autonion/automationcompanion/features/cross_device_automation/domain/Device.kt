package com.autonion.automationcompanion.features.cross_device_automation.domain

import java.util.UUID

enum class DeviceRole {
    CONTROLLER,
    WORK_DEVICE,
    MEDIA_DEVICE,
    UNKNOWN
}

enum class DeviceStatus {
    ONLINE,
    OFFLINE,
    UNKNOWN
}

data class Device(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: DeviceRole = DeviceRole.UNKNOWN,
    val ipAddress: String,
    val port: Int,
    val status: DeviceStatus = DeviceStatus.UNKNOWN,
    val lastSeen: Long = System.currentTimeMillis(),
    val capabilities: List<String> = emptyList()
)
