package com.autonion.automationcompanion.features.system_context_automation.location

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSlotsScreen(
    onAddClicked: () -> Unit,
    onEditSlot: (Long) -> Unit
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).slotDao() }
    val scope = rememberCoroutineScope()

    val snackbarHostState = remember { SnackbarHostState() }
    var recentlyDeleted by remember { mutableStateOf<Slot?>(null) }

    val slots by dao.getSlotsByType("LOCATION").collectAsState(initial = emptyList())
    val now = System.currentTimeMillis()

    val grouped = slots.groupBy {
        val start = it.startMillis ?: 0L
        when {
            start < now -> "Past"
            start < now + 24 * 60 * 60 * 1000 -> "Today"
            else -> "Upcoming"
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var isLocationPermissionGranted by remember { mutableStateOf(true) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isLocationPermissionGranted = isGranted
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isLocationPermissionGranted = com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils.isLocationPermissionGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Location Automations") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClicked) {
                Icon(Icons.Default.Add, contentDescription = "Add Slot")
            }
        }
    ) { padding ->
        Column(
             modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (!isLocationPermissionGranted) {
                com.autonion.automationcompanion.features.system_context_automation.shared.ui.PermissionWarningCard(
                    title = "Location Permission Required",
                    description = "Location automation requires location access to trigger actions.",
                    buttonText = "Allow",
                    onClick = {
                        permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                )
            }

            if (slots.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No automations created yet")
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    grouped.forEach { (section, sectionSlots) ->
                        item {
                            Text(
                                section,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        items(sectionSlots, key = { it.id }) { slot ->
                            SlotCard(
                                slot = slot,
                                onToggleEnabled = { enabled ->
                                    if (enabled && !com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils.isLocationPermissionGranted(context)) {
                                         android.widget.Toast.makeText(context, "Location permission required to enable automation", android.widget.Toast.LENGTH_LONG).show()
                                         permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                                    } else {
                                        scope.launch {
                                            dao.setEnabled(slot.id, enabled)
                                        }
                                    }
                                },
                                onEdit = { onEditSlot(slot.id) },
                                onDelete = {
                                    recentlyDeleted = slot
                                    scope.launch {
                                        dao.delete(slot)
                                        val result = snackbarHostState.showSnackbar(
                                            message = "Slot deleted",
                                            actionLabel = "Undo"
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            recentlyDeleted?.let {
                                                dao.insert(it.copy(id = 0))
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotCard(
    slot: Slot,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Radius ${slot.radiusMeters?.toInt()} m",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = slot.enabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                "Time: ${formatTime(slot.startMillis)} → ${formatTime(slot.endMillis)}",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(4.dp))

            Text(
                "Actions: ${slot.actions.joinToString { it.javaClass.simpleName }}",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}

private fun formatTime(millis: Long?): String {
    if (millis == null) return "--:--"
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    return "%02d:%02d".format(
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE)
    )
}
