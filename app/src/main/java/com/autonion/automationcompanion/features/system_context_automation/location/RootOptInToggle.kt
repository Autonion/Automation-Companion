//package com.example.automationcompanion.features.system_context_automation.location
//
//import android.content.Context
//import android.widget.Toast
//import androidx.compose.foundation.layout.Column
//import androidx.compose.foundation.layout.Row
//import androidx.compose.material3.AlertDialog
//import androidx.compose.material3.Checkbox
//import androidx.compose.material3.Text
//import androidx.compose.material3.TextButton
//import androidx.compose.runtime.Composable
//import androidx.compose.runtime.mutableStateOf
//import androidx.compose.runtime.remember
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.platform.LocalContext
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.setValue
//import com.example.automationcompanion.core.helpers.RootLocationToggle
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//
//@Composable
//fun RootOptInToggle() {
//    val context = LocalContext.current
//    val prefs = context.getSharedPreferences("automation_prefs", Context.MODE_PRIVATE)
//    // load initial value once
//    var enabled by remember { mutableStateOf(prefs.getBoolean("root_toggle_enabled", false)) }
//    var showDialog by remember { mutableStateOf(false) }
//
//    Column {
//        Row(verticalAlignment = Alignment.CenterVertically) {
//            Checkbox(
//                checked = enabled,
//                onCheckedChange = { new ->
//                    if (new) {
//                        // show confirmation dialog
//                        showDialog = true
//                    } else {
//                        enabled = false
//                        prefs.edit().putBoolean("root_toggle_enabled", false).apply()
//                    }
//                }
//            )
//            Text("Enable root-based silent location toggle (advanced)")
//        }
//
//        if (showDialog) {
//            AlertDialog(
//                onDismissRequest = { showDialog = false },
//                title = { Text("Enable root toggling?") },
//                text = {
//                    Text(
//                        "This feature requires a rooted device and will temporarily enable Location " +
//                                "on your device using root. The app will attempt to restore the previous state after use. " +
//                                "Only enable this on devices you control."
//                    )
//                },
//                confirmButton = {
//                    TextButton(onClick = {
//                        // run a quick su check before fully enabling
//                        CoroutineScope(Dispatchers.IO).launch {
//                            val suOk = RootLocationToggle.isSuAvailable()
//                            withContext(Dispatchers.Main) {
//                                if (suOk) {
//                                    enabled = true
//                                    prefs.edit().putBoolean("root_toggle_enabled", true).apply()
//                                    Toast.makeText(context, "Root toggle enabled (device appears rooted)", Toast.LENGTH_SHORT).show()
//                                } else {
//                                    // don't persist the opt-in if su not available
//                                    enabled = false
//                                    prefs.edit().putBoolean("root_toggle_enabled", false).apply()
//                                    Toast.makeText(context,
//                                        "Root not available on this device. Install/enable root (e.g., Magisk) and grant su, then try again.",
//                                        Toast.LENGTH_LONG
//                                    ).show()
//                                }
//                                showDialog = false
//                            }
//                        }
//                    }) { Text("Enable") }
//                },
//                dismissButton = {
//                    TextButton(onClick = {
//                        showDialog = false
//                    }) { Text("Cancel") }
//                }
//            )
//        }
//    }
//}
