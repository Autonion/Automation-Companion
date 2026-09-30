// app/src/main/java/com/example/automationcompanion/features/system_context_automation/ui/SlotConfigActivity.kt
package com.autonion.automationcompanion.features.system_context_automation.location.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.autonion.automationcompanion.ui.theme.AppTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import androidx.core.net.toUri
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAutomationController
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationSchedule
import com.autonion.automationcompanion.features.system_context_automation.location.LocationPermissionFlow
import com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils
import com.autonion.automationcompanion.automation.actions.models.AutomationAction
import com.autonion.automationcompanion.automation.actions.models.ConfiguredAction
import com.autonion.automationcompanion.automation.actions.ui.AppPickerActivity
import com.autonion.automationcompanion.features.system_context_automation.location.permissions.PermissionPreflight
import com.google.android.gms.location.LocationServices
import java.util.Locale


class SlotConfigActivity : AppCompatActivity() {

    // UI state — keep simple and lift into a ViewModel when desired
    private var lat by mutableStateOf("")
    private var lng by mutableStateOf("")
    private var radius by mutableIntStateOf(300)
    private var coordinatesChanged = false
    private var isSaving by mutableStateOf(false)
    private var isLoading by mutableStateOf(false)
    private val locationPermissions = LocationPermissionFlow(this)

    private var startLabel by mutableStateOf("--:--")
    private var endLabel by mutableStateOf("--:--")
    private var startHour = -1
    private var startMinute = -1
    private var endHour = -1
    private var endMinute = -1
    private var editingSlotId: Long? = null

    // Action configuration state — managed as a list of ConfiguredActions
    // This replaces all the individual action toggles (smsEnabled, volumeEnabled, etc.)
    private var configuredActions by mutableStateOf<List<ConfiguredAction>>(emptyList())

    private var remindBeforeMinutes by mutableStateOf("15")

    private var selectedDays by mutableStateOf(
        setOf("MON","TUE","WED","THU","FRI","SAT","SUN")
    )



    // Activity result launchers
    private var contactPickerActionIndex = -1  // Track which SMS action's contact picker is open

    private val contactPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val uri: Uri? = res.data!!.data
            uri?.let { u ->
                val num = fetchPhoneNumberFromContact(u)
                if (!num.isNullOrBlank()) {
                    // Update the SMS action's contacts
                    if (contactPickerActionIndex >= 0 && contactPickerActionIndex < configuredActions.size) {
                        val smsAction = configuredActions.getOrNull(contactPickerActionIndex)
                        if (smsAction is ConfiguredAction.SendSms) {
                            val updatedContacts = if (smsAction.contactsCsv.isBlank())
                                num else "${smsAction.contactsCsv};$num"
                            
                            configuredActions = configuredActions.mapIndexed { idx, action ->
                                if (idx == contactPickerActionIndex) {
                                    smsAction.copy(contactsCsv = updatedContacts)
                                } else {
                                    action
                                }
                            }
                            contactPickerActionIndex = -1
                        }
                    }
                } else {
                    Toast.makeText(this, "No number in contact", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var appPickerActionIndex = -1  // Track which App action's picker is open

    private val appPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val packageName = res.data?.getStringExtra("selected_package_name")
            packageName?.let { pkg ->
                if (appPickerActionIndex >= 0 && appPickerActionIndex < configuredActions.size) {
                    val appAction = configuredActions.getOrNull(appPickerActionIndex)
                    if (appAction is ConfiguredAction.AppAction) {
                        configuredActions = configuredActions.mapIndexed { idx, action ->
                            if (idx == appPickerActionIndex) {
                                appAction.copy(packageName = pkg)
                            } else {
                                action
                            }
                        }
                        appPickerActionIndex = -1
                    }
                }
            }
        }
    }

    // Add this function to open app picker:
    private fun openAppPicker() {
        val intent = Intent(this, AppPickerActivity::class.java)
        appPickerLauncher.launch(intent)
    }

    private val requestMultiplePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        // handle permission results
        // nothing special here; we'll check again at save
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return

        val latParam = uri.getQueryParameter("lat")
        val lngParam = uri.getQueryParameter("lng")
        val radiusParam = uri.getQueryParameter("radius")

        if (latParam != null && lngParam != null) {
            coordinatesChanged = true
            lat = latParam
            lng = lngParam
        }

        radiusParam?.toIntOrNull()?.let {
            radius = it
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    "Notifications disabled — success alerts won’t appear",
                    Toast.LENGTH_LONG
                ).show()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editingSlotId = intent.getLongExtra("slotId", -1L)
            .takeIf { it != -1L }

        handleDeepLink(intent)
        if (editingSlotId == null && !coordinatesChanged) fetchCurrentLocation()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }


        editingSlotId?.let { slotId ->
            isLoading = true
            lifecycleScope.launch {
                try {
                    val slot = withContext(Dispatchers.IO) {
                        AppDatabase.get(applicationContext).slotDao().getById(slotId)
                    }
                    if (slot == null) {
                        Toast.makeText(this@SlotConfigActivity, "This automation was deleted", Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        populateFromSlot(slot)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(this@SlotConfigActivity, "Could not load automation", Toast.LENGTH_LONG).show()
                    finish()
                } finally {
                    isLoading = false
                }
            }
        }


        setContent {
            AppTheme {
                SlotConfigScreen(
                    title = if (editingSlotId == null) "Create Slot" else "Edit Slot",
                    latitude = lat,
                    longitude = lng,
                    radiusMeters = radius,
                    startLabel = startLabel,
                    endLabel = endLabel,
                    startHour = startHour,
                    startMinute = startMinute,
                    endHour = endHour,
                    endMinute = endMinute,
                    onLatitudeChanged = { coordinatesChanged = true; lat = it },
                    onLongitudeChanged = { coordinatesChanged = true; lng = it },
                    onRadiusChanged = { radius = it },
                    onStartTimeChanged = { h, m ->
                        startHour = h
                        startMinute = m
                        startLabel = "%02d:%02d".format(h, m)
                    },
                    onEndTimeChanged = { h, m ->
                        endHour = h
                        endMinute = m
                        endLabel = "%02d:%02d".format(h, m)
                    },
                    onPickFromMapClicked = { openMapPickerWebsite() },
                    onPickContactClicked = { actionIndex ->
                        contactPickerActionIndex = actionIndex
                        pickContact()
                    },
                    onPickAppClicked = { actionIndex ->  // NEW PARAMETER
                        appPickerActionIndex = actionIndex
                        openAppPicker()
                    },
                    onSaveClicked = { remind, actions ->
                        doSaveSlot(remind, actions)
                    },
                    remindBeforeMinutes = remindBeforeMinutes,
                    onRemindBeforeMinutesChange = { remindBeforeMinutes = it },
                    selectedDays = selectedDays,
                    onSelectedDaysChange = { selectedDays = it },
                    configuredActions = configuredActions,
                    onActionsChanged = { newActions ->
                        // Check permissions before allowing brightness or DND actions
                        val filteredActions = newActions.filter { action ->
                            when (action) {
                                // Brightness requires WRITE_SETTINGS permission
                                is ConfiguredAction.Brightness -> {
                                    if (!Settings.System.canWrite(this@SlotConfigActivity)) {
                                        showWriteSettingsDialog()
                                        false  // Don't add action yet
                                    } else {
                                        true
                                    }
                                }
                                // DND requires NOTIFICATION_POLICY_ACCESS permission
                                is ConfiguredAction.Dnd -> {
                                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                                    if (!nm.isNotificationPolicyAccessGranted) {
                                        showDndPermissionDialog()
                                        false  // Don't add action yet
                                    } else {
                                        true
                                    }
                                }
                                else -> true  // Audio and SMS don't need special permissions here
                            }
                        }
                        configuredActions = filteredActions
                    },
                    volumeEnabled = configuredActions.any { it is ConfiguredAction.Audio },
                    context = this,
                    saveEnabled = !isSaving && !isLoading
                )
            }
        }
    }

    private fun pickContact() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestMultiplePermissions.launch(
                arrayOf(Manifest.permission.READ_CONTACTS)
            )
            return
        }

        val pick = Intent(
            Intent.ACTION_PICK,
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        )
        contactPickerLauncher.launch(pick)
    }


    private fun populateFromSlot(slot: Slot) {
        if (!coordinatesChanged) {
            lat = slot.lat?.toString() ?: ""
            lng = slot.lng?.toString() ?: ""
            radius = (slot.radiusMeters?.toInt()) ?: 300
        }
        remindBeforeMinutes = slot.remindBeforeMinutes.toString()
        selectedDays =
            if (slot.activeDays == "ALL") {
                setOf("MON","TUE","WED","THU","FRI","SAT","SUN")
            } else {
                slot.activeDays.split(",").toSet()
            }

        // Reconstruct ConfiguredActions from AutomationActions
        configuredActions = slot.actions.mapNotNull { action ->
            when (action) {
                is AutomationAction.SendSms -> {
                    ConfiguredAction.SendSms(action.message, action.contactsCsv)
                }

                is AutomationAction.SetVolume -> {
                    ConfiguredAction.Audio(action.ring, action.media)
                }

                is AutomationAction.SetBrightness -> {
                    ConfiguredAction.Brightness(action.level)
                }

                is AutomationAction.SetDnd -> {
                    ConfiguredAction.Dnd(action.enabled)
                }

                // ───────────── Display Actions ─────────────

                is AutomationAction.SetAutoRotate -> {
                    ConfiguredAction.AutoRotate(action.enabled)
                }

                is AutomationAction.SetScreenTimeout -> {
                    ConfiguredAction.ScreenTimeout(action.durationMs)
                }

                is AutomationAction.SetKeepScreenAwake -> {
                    ConfiguredAction.KeepScreenAwake(action.enabled)
                }

                // ============ NEW ACTIONS ============

                is AutomationAction.AppAction -> {
                    ConfiguredAction.AppAction(action.packageName, action.actionType)
                }

                is AutomationAction.NotificationAction -> {
                    ConfiguredAction.NotificationAction(
                        action.title,
                        action.text,
                        action.notificationType,
                        action.delayMinutes
                    )
                }

                is AutomationAction.SetBatterySaver -> {
                    ConfiguredAction.BatterySaver(action.enabled)
                }
            }
        }

        val startClock = LocationSchedule.clockMinutes(slot, start = true)
        val endClock = LocationSchedule.clockMinutes(slot, start = false)
        startHour = startClock?.div(60) ?: -1
        startMinute = startClock?.rem(60) ?: -1
        endHour = endClock?.div(60) ?: -1
        endMinute = endClock?.rem(60) ?: -1

        startLabel = if (startClock == null) "--:--" else "%02d:%02d".format(startHour, startMinute)
        endLabel = if (endClock == null) "--:--" else "%02d:%02d".format(endHour, endMinute)
    }

    /**
     * Fetch the current device location and update lat/lng values.
     * Uses FusedLocationProviderClient to get the last known location.
     */
    private fun fetchCurrentLocation() {
        if (editingSlotId != null || coordinatesChanged || lat.isNotBlank() || lng.isNotBlank()) return
        // Check if location permission is granted
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i("LocationFetch", "Location permission not granted, using default values")
            return
        }

        try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            fusedLocationClient.lastLocation.addOnSuccessListener(this) { location ->
                if (location != null && !coordinatesChanged && lat.isBlank() && lng.isBlank()) {
                    lat = String.format(Locale.US, "%.6f", location.latitude)
                    lng = String.format(Locale.US, "%.6f", location.longitude)
                    Log.i("LocationFetch", "Got current location: lat=$lat, lng=$lng")
                } else {
                    Log.i("LocationFetch", "Last location is null, keeping default values")
                }
            }.addOnFailureListener { exception ->
                Log.w("LocationFetch", "Failed to get location: ${exception.message}")
            }
        } catch (e: Exception) {
            Log.w("LocationFetch", "Error fetching location: ${e.message}")
        }
    }

    private fun fetchPhoneNumberFromContact(uri: Uri): String? {
        var number: String? = null
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val cursor = contentResolver.query(uri, projection, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) number = it.getString(0)
        }
        return number
    }



    private fun doSaveSlot(
        remindMinutes: Int,
        actions: List<AutomationAction>
    ) {
        if (isSaving || isLoading) return
        if (!PermissionUtils.isLocationPermissionGranted(this)) {
            locationPermissions.request { doSaveSlot(remindMinutes, actions) }
            return
        }
        // 🔒 1️⃣ Permission preflight (NEW)
        val missing = PermissionPreflight
            .missingSystemPermissions(this, actions)

        if (missing.isNotEmpty()) {
            val intent = PermissionPreflight.settingsIntent(this, missing.first())
            startActivity(intent)

            Toast.makeText(
                this,
                "Grant required permission to enable selected automations",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        // 2️⃣ RUNTIME permission: SEND_SMS (ONLY if SMS action exists)
        if (
            actions.any { it is AutomationAction.SendSms } &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSave = remindMinutes to actions
            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
            return
        }

        // 3️⃣ All permissions OK → save
        saveSlotInternal(remindMinutes, actions)
    }

    private var pendingSave: Pair<Int, List<AutomationAction>>? = null

    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingSave?.let { (remind, actions) ->
                    saveSlotInternal(remind, actions)
                    pendingSave = null
                }
            } else {
                Toast.makeText(
                    this,
                    "SMS permission is required to send messages",
                    Toast.LENGTH_LONG
                ).show()
            }
        }



    private fun saveSlotInternal(
        remindMinutes: Int,
        actions: List<AutomationAction>
    ) {
        // validate
        val latD = lat.toDoubleOrNull()
        val lngD = lng.toDoubleOrNull()
        if (latD == null || !latD.isFinite() || latD !in -90.0..90.0 ||
            lngD == null || !lngD.isFinite() || lngD !in -180.0..180.0) {
            Toast.makeText(this, "Enter latitude from -90 to 90 and longitude from -180 to 180", Toast.LENGTH_LONG).show()
            return
        }
        if (radius <= 0 || !radius.toFloat().isFinite()) {
            Toast.makeText(this, "Radius must be greater than zero", Toast.LENGTH_SHORT).show()
            return
        }
        if (startHour !in 0..23 || endHour !in 0..23 || startMinute !in 0..59 || endMinute !in 0..59) {
            Toast.makeText(this, "Set start/end times", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedDays.isEmpty()) {
            Toast.makeText(this, "Select at least one active day", Toast.LENGTH_SHORT).show()
            return
        }
        if (remindMinutes < 0) {
            Toast.makeText(this, "Reminder minutes cannot be negative", Toast.LENGTH_SHORT).show()
            return
        }


        // compute start/end millis
        val now = System.currentTimeMillis()
        val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = now }

        val startCal = nowCal.clone() as java.util.Calendar
        startCal.set(java.util.Calendar.HOUR_OF_DAY, startHour)
        startCal.set(java.util.Calendar.MINUTE, startMinute)
        startCal.set(java.util.Calendar.SECOND, 0)
        startCal.set(java.util.Calendar.MILLISECOND, 0)
        var startMillis = startCal.timeInMillis

        val endCal = nowCal.clone() as java.util.Calendar
        endCal.set(java.util.Calendar.HOUR_OF_DAY, endHour)
        endCal.set(java.util.Calendar.MINUTE, endMinute)
        endCal.set(java.util.Calendar.SECOND, 0)
        endCal.set(java.util.Calendar.MILLISECOND, 0)
        var endMillis = endCal.timeInMillis

        if (endMillis <= startMillis) {
            endCal.add(java.util.Calendar.DATE, 1)
            endMillis = endCal.timeInMillis
        }
        if (now > endMillis) {
            startCal.add(java.util.Calendar.DATE, 1)
            endCal.add(java.util.Calendar.DATE, 1)
            startMillis = startCal.timeInMillis
            endMillis = endCal.timeInMillis
        }

        val slotEntity = Slot(
            id = editingSlotId ?: 0L,
            lat = latD,
            lng = lngD,
            radiusMeters = radius.toFloat(),
            startMillis = startMillis,
            endMillis = endMillis,
            remindBeforeMinutes = remindMinutes,
            actions = actions,
            activeDays = if (selectedDays.size == 7) "ALL" else selectedDays.joinToString(",")
        )
        isSaving = true
        lifecycleScope.launch {
            try {
                val finalId = LocationAutomationController.save(applicationContext, slotEntity)
                Toast.makeText(
                    this@SlotConfigActivity,
                    "Saved slot id=$finalId",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SlotConfig", "Could not save automation", e)
                Toast.makeText(this@SlotConfigActivity, "Could not save automation: ${e.message ?: "please retry"}", Toast.LENGTH_LONG).show()
            } finally {
                isSaving = false
            }
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent) // 👈 REQUIRED for repeat links
    }

    private fun openMapPickerWebsite() {
        val url = ("https://geo-radius-picker.vercel.app/" +
                "?lat=$lat&lng=$lng&radius=$radius").toUri()

        val intent = Intent(Intent.ACTION_VIEW, url)
        startActivity(intent)
    }

    // ───────────── Permission Handling for Special Permissions ─────────────

    private fun checkAndRequestWriteSettingsPermission(): Boolean {
        return if (Settings.System.canWrite(this)) {
            true
        } else {
            showWriteSettingsDialog()
            false
        }
    }

    private fun checkAndRequestDndPermission(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        return if (nm.isNotificationPolicyAccessGranted) {
            true
        } else {
            showDndPermissionDialog()
            false
        }
    }

    private fun showWriteSettingsDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Brightness Permission Required")
            .setMessage("To set brightness automatically, this app needs the 'Modify System Settings' permission.\n\nTap 'Grant' to open Settings where you can enable it.")
            .setPositiveButton("Grant") { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    .setData("package:$packageName".toUri())
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDndPermissionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Do Not Disturb Permission Required")
            .setMessage("To control Do Not Disturb automatically, this app needs the 'Access Notification Policy' permission.\n\nTap 'Grant' to open Settings where you can enable it.")
            .setPositiveButton("Grant") { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

}

