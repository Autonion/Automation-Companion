package com.autonion.automationcompanion.features.system_context_automation.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.autonion.automationcompanion.features.system_context_automation.location.engine.accessibility.isAutomationAccessibilityEnabled
import com.autonion.automationcompanion.features.system_context_automation.location.ui.SlotConfigActivity
import com.autonion.automationcompanion.ui.theme.AppTheme
import com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils

class LocationSlotsActivity : ComponentActivity() {
    private val locationPermissions = LocationPermissionFlow(this)
    private val locationSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (isLocationEnabled()) {
                openSlotConfig()
            } else {
                Toast.makeText(this, "Enable Location before creating an automation", Toast.LENGTH_LONG).show()
            }
        }

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
                    },
                    onRequestLocationPermission = { onGranted ->
                        locationPermissions.request(showDisclosure = false, onGranted = onGranted)
                    }
                )
            }
        }
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
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
        locationPermissions.request {
            if (isLocationEnabled()) openSlotConfig()
            else locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }


}

/** Sequential geofencing consent shared by the list and the editor. */
internal class LocationPermissionFlow(private val activity: ComponentActivity) {
    private var pendingAction: (() -> Unit)? = null
    private var requestingForegroundInSettings = false

    private val settingsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val wasForegroundRequest = requestingForegroundInSettings
        requestingForegroundInSettings = false
        if (wasForegroundRequest && hasFinePermission()) requestBackground()
        else finishRequest()
    }

    private val backgroundLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { finishRequest() }

    private val foregroundLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasFinePermission()) requestBackground()
        else {
            if (!androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)) {
                android.app.AlertDialog.Builder(activity)
                    .setTitle("Precise Location Required")
                    .setMessage("Open Permissions → Location in app settings and allow precise location to use geofence triggers.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        requestingForegroundInSettings = true
                        openAppSettings()
                    }
                    .setNegativeButton("Cancel") { _, _ -> pendingAction = null }
                    .setOnCancelListener { pendingAction = null }
                    .show()
            } else {
                pendingAction = null
                Toast.makeText(activity, "Precise location is required for geofence triggers", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun request(showDisclosure: Boolean = true, onGranted: () -> Unit) {
        pendingAction = onGranted
        if (PermissionUtils.isLocationPermissionGranted(activity)) {
            finishRequest()
        } else if (hasFinePermission()) {
            requestBackground()
        } else if (showDisclosure) {
            android.app.AlertDialog.Builder(activity)
                .setTitle("Location Permission Required")
                .setMessage("Autonion uses precise location to enable geofence triggers, including when the app is closed or not in use. We do not share your location data.")
                .setPositiveButton("Continue") { _, _ -> requestForeground() }
                .setNegativeButton("Cancel") { _, _ -> pendingAction = null }
                .setOnCancelListener { pendingAction = null }
                .show()
        } else {
            requestForeground()
        }
    }

    private fun hasFinePermission() = ContextCompat.checkSelfPermission(
        activity, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestForeground() {
        foregroundLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun requestBackground() {
        if (PermissionUtils.isLocationPermissionGranted(activity)) {
            finishRequest()
            return
        }
        val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "Geofence triggers need location access while Autonion is closed. Open Permissions → Location and choose '${activity.packageManager.backgroundPermissionOptionLabel}'. Keep precise location enabled."
        } else {
            "Geofence triggers need location access while Autonion is closed. Choose 'Allow all the time' in the next permission prompt."
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle("Allow Location in the Background")
            .setMessage(message)
            .setPositiveButton("Continue") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    openAppSettings()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    finishRequest()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> pendingAction = null }
            .setOnCancelListener { pendingAction = null }
            .show()
    }

    private fun finishRequest() {
        val action = pendingAction
        pendingAction = null
        if (PermissionUtils.isLocationPermissionGranted(activity)) action?.invoke()
        else Toast.makeText(activity, "Allow precise location all the time to enable geofence triggers", Toast.LENGTH_LONG).show()
    }

    private fun openAppSettings() {
        settingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
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

