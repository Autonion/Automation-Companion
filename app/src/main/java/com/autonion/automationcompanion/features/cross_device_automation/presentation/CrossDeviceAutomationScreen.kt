package com.autonion.automationcompanion.features.cross_device_automation.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrossDeviceAutomationScreen(onBack: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Devices", "Rules")

    val context = LocalContext.current
    var showPermissionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        CrossDeviceAutomationManager.getInstance(context).start()
        if (!com.autonion.automationcompanion.AccessibilityRouter.isServiceConnected()) {
            showPermissionDialog = true
        }
    }
    
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val scope = androidx.compose.runtime.rememberCoroutineScope() // Need a scope for the delay
    
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // Pass the Activity Context to verify we are in the foreground.
                // Critical: Add a small delay to ensure the system considers the window "focused".
                // 'ON_RESUME' can fire slightly before Window Focus.
                scope.launch {
                    kotlinx.coroutines.delay(500) 
                    CrossDeviceAutomationManager.getInstance(context).syncClipboard(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (showPermissionDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Permission Required") },
            text = { Text("This feature requires the Automation Companion Accessibility Service to function (for Clipboard Sync). Please enable it in Settings.") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showPermissionDialog = false
                        val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showPermissionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cross-Device Automation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }

                when (selectedTab) {
                    0 -> {
                        Column(modifier = Modifier.weight(1f)) {
                            DeviceManagementScreen()
                        }
                    }
                    1 -> {
                        Column(modifier = Modifier.weight(1f)) {
                            RuleBuilderScreen()
                        }
                    }
                }
            }
        }
    }
}
