// app/src/main/java/com/example/automationcompanion/features/system_context_automation/ui/SlotConfigScreen.kt
package com.autonion.automationcompanion.features.system_context_automation.location.ui

import android.widget.SeekBar
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.ui.viewinterop.AndroidView

//import androidx.compose.ui.tooling.preview.Preview
//import com.example.automationcompanion.features.system_context_automation.location.RootOptInToggle
//import kotlin.math.asin
//import kotlin.math.atan2
//import kotlin.math.cos
//import kotlin.math.sin

@Composable
fun SlotConfigScreen(
    // UI state (can be backed by ViewModel) — provide defaults
    latitude: String,
    longitude: String,
    radiusMeters: Int,
    message: String,
    contactsCsv: String,
    startLabel: String,
    endLabel: String,
    onLatitudeChanged: (String) -> Unit,
    onLongitudeChanged: (String) -> Unit,
    onRadiusChanged: (Int) -> Unit,
    onMessageChanged: (String) -> Unit,
    onPickContactClicked: () -> Unit,
    //onPickLocationClicked: () -> Unit,
    onStartTimeClicked: () -> Unit,
    onEndTimeClicked: () -> Unit,
    //onSaveClicked: (Int, Boolean) -> Unit,
    onSaveClicked: (Int) -> Unit,
    // expose map callbacks for external actions
    //initialLat: Double,
    //initialLng: Double,
    //initialRadius: Float,
    //onMapPointSelected: (Double, Double) -> Unit
) {
    //val ctx = LocalContext.current
    Column(modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Create Slot", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = latitude,
            onValueChange = onLatitudeChanged,
            label = { Text("Latitude") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = longitude,
            onValueChange = onLongitudeChanged,
            label = { Text("Longitude") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Start time")
                Text(startLabel, modifier = Modifier
                    .padding(4.dp)
                    .clickable { onStartTimeClicked() })
            }
            Column {
                Text("End time")
                Text(endLabel, modifier = Modifier
                    .padding(4.dp)
                    .clickable { onEndTimeClicked() })
            }
        }

        OutlinedTextField(
            value = message,
            onValueChange = onMessageChanged,
            label = { Text("Message to send") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(onClick = onPickContactClicked, modifier = Modifier.fillMaxWidth()) {
            Text("Select contact")
        }
        Text(contactsCsv.ifBlank { "No contacts selected" })

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Radius: $radiusMeters m")
            Spacer(Modifier.width(8.dp))
            // Expose a SeekBar interop for precise control if needed
            AndroidView(factory = { ctx ->
                SeekBar(ctx).apply {
                    max = 2000
                    progress = radiusMeters
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                            val r = maxOf(50, progress)
                            onRadiusChanged(r)
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }
            }, modifier = Modifier.fillMaxWidth())
        }
        // inside SlotConfigScreen()
        var remindBeforeMinutes by remember { mutableStateOf("15") }  // default 15 min

        OutlinedTextField(
            value = remindBeforeMinutes,
            onValueChange = { remindBeforeMinutes = it },
            label = { Text("Remind before (minutes)") },
            modifier = Modifier.fillMaxWidth()
        )
//        // inside the composable state
//        var useRootForThisSlot by remember { mutableStateOf(false) }
//
//// UI
//        Row(verticalAlignment = Alignment.CenterVertically) {
//            Checkbox(checked = useRootForThisSlot, onCheckedChange = { useRootForThisSlot = it })
//            Spacer(modifier = Modifier.width(8.dp))
//            Text("Allow root toggle for this slot (advanced)")
//        }

        Spacer(modifier = Modifier.weight(1f))
//        RootOptInToggle()
        //onSaveClicked(remindBeforeMinutes.toIntOrNull() ?: 15,useRootForThisSlot)
        Button(onClick = {onSaveClicked(remindBeforeMinutes.toIntOrNull() ?: 15)}, modifier = Modifier.fillMaxWidth()) {
            Text("Save Slot")
        }
    }
}

//@Preview
//@Composable
//fun SlotConfigScreenPreview() {
//    SlotConfigScreen(
//        latitude = "",
//        longitude = "",
//        radiusMeters = 300,
//        message = "Take notes",
//        contactsCsv = "9999999999",
//        startLabel = "09:00",
//        endLabel = "17:00",
//        onLatitudeChanged = {},
//        onLongitudeChanged = {},
//        onRadiusChanged = {},
//        onMessageChanged = {},
//        onPickContactClicked = {},
//        onPickLocationClicked = {},
//        onStartTimeClicked = {},
//        onEndTimeClicked = {},
//        onSaveClicked = {},
//        initialLat = 16.504464,
//        initialLng = 80.652678,
//        initialRadius = 300f,
//        onMapPointSelected = { _, _ -> }
//    )
//}