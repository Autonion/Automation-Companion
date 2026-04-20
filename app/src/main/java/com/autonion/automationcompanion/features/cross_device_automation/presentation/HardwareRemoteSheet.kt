package com.autonion.automationcompanion.features.cross_device_automation.presentation

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.cross_device_automation.engine.DesktopAction
import com.autonion.automationcompanion.features.cross_device_automation.engine.GestureType
import com.autonion.automationcompanion.features.cross_device_automation.engine.HardwareButtonMapper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareRemoteSheet(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val isActive by HardwareButtonMapper.isActive.collectAsState()

    var showDropdownFor by remember { mutableStateOf<Pair<Int, GestureType>?>(null) }
    
    // Default or existing mappings
    val mappings = remember { mutableStateMapOf<Pair<Int, GestureType>, DesktopAction>() }

    LaunchedEffect(Unit) {
        mappings.putAll(HardwareButtonMapper.currentMappings)
    }

    val availableKeys = listOf("Enter", "Space", "Up Arrow", "Down Arrow", "Left Arrow", "Right Arrow", "Escape", "Backspace")

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SettingsRemote,
                        contentDescription = "Hardware Remote",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Hardware Remote",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onDismissRequest) {
                    Icon(Icons.Default.Close, "Close")
                }
            }

            Text(
                text = "Map your physical volume buttons to send keyboard commands to your PC. Works even when the screen is off.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Volume Up Mappings
            Text("Volume Up", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            MappingRow("Single Tap", KeyEvent.KEYCODE_VOLUME_UP, GestureType.SINGLE_TAP, mappings, availableKeys) { showDropdownFor = it }
            MappingRow("Double Tap", KeyEvent.KEYCODE_VOLUME_UP, GestureType.DOUBLE_TAP, mappings, availableKeys) { showDropdownFor = it }
            MappingRow("Long Press", KeyEvent.KEYCODE_VOLUME_UP, GestureType.LONG_PRESS, mappings, availableKeys) { showDropdownFor = it }

            Spacer(modifier = Modifier.height(8.dp))

            // Volume Down Mappings
            Text("Volume Down", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            MappingRow("Single Tap", KeyEvent.KEYCODE_VOLUME_DOWN, GestureType.SINGLE_TAP, mappings, availableKeys) { showDropdownFor = it }
            MappingRow("Double Tap", KeyEvent.KEYCODE_VOLUME_DOWN, GestureType.DOUBLE_TAP, mappings, availableKeys) { showDropdownFor = it }
            MappingRow("Long Press", KeyEvent.KEYCODE_VOLUME_DOWN, GestureType.LONG_PRESS, mappings, availableKeys) { showDropdownFor = it }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (isActive) {
                        HardwareButtonMapper.deactivate()
                    } else {
                        HardwareButtonMapper.activate(context, mappings.toMap())
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (isActive) "Stop Remote" else "Start Remote",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Dropdown Menu
        if (showDropdownFor != null) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { showDropdownFor = null }
            ) {
                DropdownMenuItem(
                    text = { Text("None") },
                    onClick = {
                        mappings.remove(showDropdownFor!!)
                        showDropdownFor = null
                    }
                )
                availableKeys.forEach { key ->
                    DropdownMenuItem(
                        text = { Text("Send $key Key") },
                        onClick = {
                            mappings[showDropdownFor!!] = DesktopAction.SendKey(key)
                            showDropdownFor = null
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MappingRow(
    label: String,
    keyCode: Int,
    gesture: GestureType,
    mappings: MutableMap<Pair<Int, GestureType>, DesktopAction>,
    availableKeys: List<String>,
    onSelectClicked: (Pair<Int, GestureType>) -> Unit
) {
    val currentMapping = mappings[Pair(keyCode, gesture)]
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .clickable { onSelectClicked(Pair(keyCode, gesture)) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (currentMapping is DesktopAction.SendKey) "Send ${currentMapping.keyName}" else "None",
                style = MaterialTheme.typography.bodyMedium,
                color = if (currentMapping != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Select", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
