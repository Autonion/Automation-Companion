package com.autonion.automationcompanion.features.gesture_recording_playback.ui.presets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Stateless-ish screen composable for list of preset names.
 *
 * Interaction is passed upward to the caller (MainActivity).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsScreen(
    presets: List<String>,
    onAddNewClicked: () -> Unit,
    onPlay: (String) -> Unit,
    onDelete: (String) -> Unit,
    onItemClicked: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Automations") })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddNewClicked,
                icon = { Icon(Icons.Default.Add, contentDescription = "Add") },
                text = { Text("Add") }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = modifier.fillMaxSize()
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (presets.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No automations yet — tap + to create one")
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(presets, key = { it }) { presetName ->
                        PresetCard(
                            name = presetName,
                            onClick = { onItemClicked(presetName) },
                            onPlay = { onPlay(it) },
                            onDelete = {
                                // we delegate delete to caller (who will confirm or show undo)
                                onDelete(it)
                                // sample snackbar with undo can be shown by caller if desired
                                scope.launch {
                                    val action = snackbarHostState.showSnackbar(
                                        "Deleted \"$it\"",
                                        actionLabel = "UNDO",
                                        duration = SnackbarDuration.Short
                                    )
                                    @Suppress("ControlFlowWithEmptyBody")
                                    if (action == SnackbarResult.ActionPerformed) {
                                        // Caller must implement undo if desired
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
