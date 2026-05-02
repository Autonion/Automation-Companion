package com.autonion.automationcompanion.features.system_context_automation.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.autonion.automationcompanion.features.system_context_automation.location.engine.accessibility.isAutomationAccessibilityEnabled
import com.autonion.automationcompanion.features.system_context_automation.location.ui.SlotConfigActivity
import com.autonion.automationcompanion.ui.theme.AppTheme

class LocationSlotsActivity : ComponentActivity() {
    private var waitingForAccessibility = false

    private val accessibilitySettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (isAutomationAccessibilityEnabled(this)) {
                continueAddFlow()
            } else {
                Toast.makeText(
                    this,
                    "Accessibility still not enabled",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AppTheme {
                LocationSlotsScreen(
                    onAddClicked = {
                        onAddSlotClicked()
                    },
                    onEditSlot = { slotId ->
                        startActivity(
                            Intent(this, SlotConfigActivity::class.java)
                                .putExtra("slotId", slotId)
                        )
                    }
                )
            }
        }
    }

    private fun ensureAccessibilityEnabled(onSuccess: () -> Unit) {
        if (isAccessibilityEnabled(this)) {
            onSuccess()
        } else {
            waitingForAccessibility = true
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
            Toast.makeText(
                this,
                "Enable Accessibility for automation",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                openSlotConfig()
            } else {
                Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show()
            }
        }
    private fun onAddSlotClicked() {
        if (!isAutomationAccessibilityEnabled(this)) {
            accessibilitySettingsLauncher.launch(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
            return
        }

        continueAddFlow()
    }


    private fun openSlotConfig() {
        startActivity(Intent(this, SlotConfigActivity::class.java))
    }

    private fun continueAddFlow() {
        if (!isLocationEnabled()) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            openSlotConfig()
        }
    }


    override fun onResume() {
        super.onResume()

        if (waitingForAccessibility && isMyAccessibilityServiceEnabled(this)) {
            waitingForAccessibility = false
            onAddSlotClicked() // resume flow
        }
    }


}

fun isMyAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/.features.gesture_recording_playback.overlay.AutomationService"

    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
}

fun isAccessibilityEnabled(context: Context): Boolean {
    val am = Settings.Secure.getInt(
        context.contentResolver,
        Settings.Secure.ACCESSIBILITY_ENABLED, 0
    )
    return am == 1
}

