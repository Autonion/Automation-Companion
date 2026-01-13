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

    val slots by dao.getAllFlow().collectAsState(initial = emptyList())
    val now = System.currentTimeMillis()

    val grouped = slots.groupBy {
        when {
            it.startMillis < now -> "Past"
            it.startMillis < now + 24 * 60 * 60 * 1000 -> "Today"
            else -> "Upcoming"
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

        if (slots.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No automations created yet")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
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
                                scope.launch {
                                    dao.setEnabled(slot.id, enabled)
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
                    "Radius ${slot.radiusMeters.toInt()} m",
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

private fun formatTime(millis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    return "%02d:%02d".format(
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE)
    )
}
