package com.autonion.automationcompanion.features.system_context_automation

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
import com.autonion.automationcompanion.features.system_context_automation.location.LocationSlotsActivity


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemContextMainScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current

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
                    context.startActivity (
                        Intent(context, LocationSlotsActivity::class.java)
                    )
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
//fun isAccessibilityEnabled(context: Context): Boolean {
//    val am = Settings.Secure.getInt(
//        context.contentResolver,
//        Settings.Secure.ACCESSIBILITY_ENABLED, 0
//    )
//    return am == 1
//}

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
