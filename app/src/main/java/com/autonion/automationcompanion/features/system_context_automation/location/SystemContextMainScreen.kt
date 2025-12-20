package com.autonion.automationcompanion.features.system_context_automation.location

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.Settings
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.autonion.automationcompanion.features.system_context_automation.location.ui.SlotConfigActivity


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemContextMainScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current


    // State to hold whether we are waiting for accessibility flow to finish
    val pendingAfterAccessibility = remember { mutableStateOf(false) }
    val pendingAfterLocationSettings = remember { mutableStateOf(false) }

    // Launcher to open Accessibility settings and receive a callback when user returns
    val accessibilitySettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // user returned from settings screen — check Accessibility & continue if enabled
        if (isAccessibilityEnabled(context)) {
            // proceed to request the remaining runtime permissions
            pendingAfterAccessibility.value = true
        } else {
            Toast.makeText(context, "Accessibility still not enabled", Toast.LENGTH_SHORT).show()
        }
    }

    // Launcher for location + contacts + SMS
    val locationAndContactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fine = results[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val contacts = results[Manifest.permission.READ_CONTACTS] ?: false
        val sms = results[Manifest.permission.SEND_SMS] ?: false

        if (fine && contacts && sms) {
            context.startActivity(Intent(context, SlotConfigActivity::class.java))
        } else {
            Toast.makeText(context, "Please grant Location, Contacts and SMS permissions", Toast.LENGTH_LONG).show()
        }
    }

    // Notification permission launcher (Android 13+)
    val notificationLauncher =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                val need = mutableListOf<String>()

                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED
                ) need.add(Manifest.permission.ACCESS_FINE_LOCATION)

                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED
                ) need.add(Manifest.permission.READ_CONTACTS)

                if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
                    != PackageManager.PERMISSION_GRANTED
                ) need.add(Manifest.permission.SEND_SMS)

                if (need.isNotEmpty()) {
                    locationAndContactsLauncher.launch(need.toTypedArray())
                } else {
                    context.startActivity(Intent(context, SlotConfigActivity::class.java))
                }

                if (!granted) {
                    Toast.makeText(context,
                        "Notification permission recommended for alerts",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else null

    // helper checks
    fun isLocationServicesEnabled(ctx: Context): Boolean {
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Throwable) {
            false
        }
    }

    // Launcher: return from Location settings (ACTION_LOCATION_SOURCE_SETTINGS)
    val locationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _: ActivityResult ->
        // user returned from location settings — re-check location services
        if (isLocationServicesEnabled(context)) {
            pendingAfterLocationSettings.value = true
        } else {
            Toast.makeText(context, "Location services are still turned off", Toast.LENGTH_SHORT).show()
        }
    }

    // Common entry point for the runtime flow
    fun startRuntimePermissionsFlow() {
        // First request POST_NOTIFICATIONS if required (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        // If notification not required or already granted -> request the remaining ones
        val need = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.READ_CONTACTS)

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.SEND_SMS)

        if (need.isNotEmpty()) {
            locationAndContactsLauncher.launch(need.toTypedArray())
        } else {
            context.startActivity(Intent(context, SlotConfigActivity::class.java))
        }
    }

    // When we return from accessibility settings and flagged pending -> we must check location services next
    if (pendingAfterAccessibility.value) {
        pendingAfterAccessibility.value = false

        // Check location services BEFORE runtime permissions
        if (!isLocationServicesEnabled(context)) {
            // open system location settings; on return locationSettingsLauncher will set pendingAfterLocationSettings
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            locationSettingsLauncher.launch(intent)
        } else {
            // location services already ON -> proceed to runtime perms
            startRuntimePermissionsFlow()
        }
    }

    // If we returned from accessibility settings and need to proceed, do it:
    if (pendingAfterAccessibility.value) {
        pendingAfterAccessibility.value = false
        startRuntimePermissionsFlow()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("System Context Automation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ⭐ FEATURE CARD: Location Automation
            FeatureCard(
                title = "Location Automation",
                description = "Trigger messages/actions based on radius + time slot",
                onClick = {
                    // On click: check accessibility first
                    if (!isAccessibilityEnabled(context)) {
                        // Open accessibility settings; when user returns, accessibilitySettingsLauncher callback will set flag
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        accessibilitySettingsLauncher.launch(intent)
                        return@FeatureCard
                    }

                    // 2) accessibility ok -> check location services
                    if (!isLocationServicesEnabled(context)) {
                        // open location settings and wait
                        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        locationSettingsLauncher.launch(intent)
                        return@FeatureCard
                    }
                    // 3) accessibility + location services OK -> start runtime permission flow
                    startRuntimePermissionsFlow()
                }
            )

            HorizontalDivider()

            // ⭐ Remaining TODO features (not implemented yet)
            Text(
                text = "Upcoming Features",
                style = MaterialTheme.typography.titleMedium
            )

            TodoItem("Battery triggers")
            TodoItem("Wi-Fi connectivity triggers")
            TodoItem("Time-of-day context triggers")
            TodoItem("Permission fallback system")
            TodoItem("Settings Panel integration")
        }
    }
}
fun isAccessibilityEnabled(context: Context): Boolean {
    val am = Settings.Secure.getInt(
        context.contentResolver,
        Settings.Secure.ACCESSIBILITY_ENABLED, 0
    )
    return am == 1
}

//fun openAccessibilitySettings(context: Context) {
//    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
//    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//    context.startActivity(intent)
//}

@Composable
private fun FeatureCard(title: String, description: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TodoItem(label: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("• $label", style = MaterialTheme.typography.bodyMedium)
    }
}
