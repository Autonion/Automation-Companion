package com.autonion.automationcompanion.features.cross_device_automation.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.autonion.automationcompanion.features.cross_device_automation.domain.Device
import com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceRole
import com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceStatus

@Composable
fun DeviceManagementScreen() {
    val context = LocalContext.current
    val manager = CrossDeviceAutomationManager.getInstance(context)
    val viewModel = viewModel { DeviceManagementViewModel(manager) }
    val devices by viewModel.devices.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Text("Connected Devices", style = MaterialTheme.typography.titleLarge) // Removed redundancy if tab implies context, but kept for clarity
        // Or better, make it a descriptive header
        Text(
            text = "Discovered Devices", 
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        if (devices.isEmpty()) {
            Text(
                "Scanning for devices on local network...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        LazyColumn {
            items(devices) { device ->
                DeviceItem(device)
            }
        }
    }
}

@Composable
fun DeviceItem(device: Device) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = getDeviceIcon(device.role),
                contentDescription = null,
                modifier = Modifier.padding(end = 16.dp)
            )
            Column {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text(device.ipAddress, style = MaterialTheme.typography.bodySmall)
                Text("Status: ${device.status}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

fun getDeviceIcon(role: DeviceRole): ImageVector {
    return when (role) {
        DeviceRole.CONTROLLER -> Icons.Default.PhoneAndroid
        DeviceRole.WORK_DEVICE -> Icons.Default.Computer
        DeviceRole.MEDIA_DEVICE -> Icons.Default.Tv
        else -> Icons.Default.PhoneAndroid
    }
}
