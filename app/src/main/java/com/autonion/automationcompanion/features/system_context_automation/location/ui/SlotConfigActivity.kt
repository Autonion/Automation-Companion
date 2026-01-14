// app/src/main/java/com/example/automationcompanion/features/system_context_automation/ui/SlotConfigActivity.kt
package com.autonion.automationcompanion.features.system_context_automation.location.ui

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.TimePicker
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.LocationReminderReceiver
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.TrackingForegroundService
import androidx.core.net.toUri
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.MidnightResetReceiver
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.SlotStartAlarmReceiver
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.AppInitManager
import com.autonion.automationcompanion.features.automation.actions.models.AutomationAction
import com.autonion.automationcompanion.features.automation.actions.models.ConfiguredAction
import com.autonion.automationcompanion.features.automation.actions.builders.ActionBuilder
import com.autonion.automationcompanion.features.system_context_automation.location.permissions.PermissionPreflight
import com.google.android.gms.location.LocationServices


class SlotConfigActivity : AppCompatActivity() {

    // UI state — keep simple and lift into a ViewModel when desired
    private var lat by mutableStateOf("0.0")
    private var lng by mutableStateOf("0.0")
    private var radius by mutableIntStateOf(300)

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
        AppInitManager.init(applicationContext)
        editingSlotId = intent.getLongExtra("slotId", -1L)
            .takeIf { it != -1L }

        handleDeepLink(intent)
        // Fetch current device location for default values
        fetchCurrentLocation()
        // optionally load defaults from intent extras

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
            CoroutineScope(Dispatchers.IO).launch {
                val slot = AppDatabase
                    .get(applicationContext)
                    .slotDao()
                    .getById(slotId)

                slot?.let {
                    runOnUiThread {
                        populateFromSlot(it)
                    }
                }
            }
        }


        setContent {
            MaterialTheme {
                SlotConfigScreen(
                    title = if (editingSlotId == null) "Create Slot" else "Edit Slot",
                    latitude = lat,
                    longitude = lng,
                    radiusMeters = radius,
                    startLabel = startLabel,
                    endLabel = endLabel,

                    onLatitudeChanged = { lat = it },
                    onLongitudeChanged = { lng = it },
                    onRadiusChanged = { radius = it },
                    onStartTimeClicked = { showTimePicker(true) },
                    onEndTimeClicked = { showTimePicker(false) },
                    onPickFromMapClicked = { openMapPickerWebsite() },
                    onPickContactClicked = { actionIndex ->
                        contactPickerActionIndex = actionIndex
                        pickContact()
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
                    volumeEnabled = configuredActions.any { it is ConfiguredAction.Audio }
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
        lat = slot.lat.toString()
        lng = slot.lng.toString()
        radius = slot.radiusMeters.toInt()
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
                is AutomationAction.SetDarkMode -> {
                    ConfiguredAction.DarkMode(action.enabled)
                }

                is AutomationAction.SetAutoRotate -> {
                    ConfiguredAction.AutoRotate(action.enabled)
                }

                is AutomationAction.SetScreenTimeout -> {
                    ConfiguredAction.ScreenTimeout(action.durationMs)
                }

                is AutomationAction.SetNightLight -> {
                    ConfiguredAction.NightLight(action.enabled)
                }

                is AutomationAction.SetKeepScreenAwake -> {
                    ConfiguredAction.KeepScreenAwake(action.enabled)
                }
            }
        }

        val startCal = java.util.Calendar.getInstance().apply {
            timeInMillis = slot.startMillis
        }
        val endCal = java.util.Calendar.getInstance().apply {
            timeInMillis = slot.endMillis
        }

        startHour = startCal.get(java.util.Calendar.HOUR_OF_DAY)
        startMinute = startCal.get(java.util.Calendar.MINUTE)
        endHour = endCal.get(java.util.Calendar.HOUR_OF_DAY)
        endMinute = endCal.get(java.util.Calendar.MINUTE)

        startLabel = "%02d:%02d".format(startHour, startMinute)
        endLabel = "%02d:%02d".format(endHour, endMinute)
    }

    /**
     * Fetch the current device location and update lat/lng values.
     * Uses FusedLocationProviderClient to get the last known location.
     */
    private fun fetchCurrentLocation() {
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
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    lat = String.format("%.6f", location.latitude)
                    lng = String.format("%.6f", location.longitude)
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

    private fun showTimePicker(isStart: Boolean) {
        val c = java.util.Calendar.getInstance()
        val hour = c.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = c.get(java.util.Calendar.MINUTE)

        // create a listener that only updates state
        val listener = android.app.TimePickerDialog.OnTimeSetListener { _: TimePicker, h: Int, m: Int ->
            if (isStart) {
                startHour = h
                startMinute = m
                startLabel = "%02d:%02d".format(h, m) // mutableState change -> Compose will recompose
            } else {
                endHour = h
                endMinute = m
                endLabel = "%02d:%02d".format(h, m)
            }
            // Do NOT call setContent() or finish() here
        }

        // Use the Activity context (this) and show the dialog
        val tpd = android.app.TimePickerDialog(this@SlotConfigActivity, listener, hour, minute, true)
        tpd.show()
    }

//    fun scheduleReminderForSlot(context: Context, slotId: Long, remindAtMillis: Long) {
//        val am = context.getSystemService(android.app.AlarmManager::class.java)
//        val intent = Intent(context, com.example.automationcompanion.engine.location_receiver.LocationReminderReceiver::class.java).apply {
//            putExtra(com.example.automationcompanion.engine.location_receiver.LocationReminderReceiver.EXTRA_SLOT_ID, slotId)
//        }
//        val pi = PendingIntent.getBroadcast(
//            context,
//            ("reminder_$slotId").hashCode(),
//            intent,
//            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
//        )
//
//        // Use your AlarmHelpers helper to schedule exact or fallback; fallback to setExact if helper not available:
//        AlarmHelpers.scheduleExactOrFallback(context, remindAtMillis, pi, null)
//    }

    private fun scheduleLocationReminders(
        context: Context,
        slotId: Long,
        startMillis: Long,
        remindMinutes: Int
    ) {
        val am = context.getSystemService(AlarmManager::class.java)

        val reminderStart = startMillis - remindMinutes * 60_000L
        if (reminderStart <= System.currentTimeMillis()) {
            Log.i("Reminder", "Not scheduling reminder for slot=$slotId: time already passed")
            return // too late
        }

        // We repeat every 3 minutes until slot starts or location is ON
        val reminderInterval = 3 * 60_000L

        val intent = Intent(context, LocationReminderReceiver::class.java).apply {
            action = LocationReminderReceiver.ACTION_REMIND // important: stable action
            putExtra(LocationReminderReceiver.EXTRA_SLOT_ID, slotId)
        }

        val pi = PendingIntent.getBroadcast(
            context,
            ("reminder_$slotId").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Use setRepeating here as before (keeps checking every 3 minutes)
        am.setRepeating(
            AlarmManager.RTC_WAKEUP,
            reminderStart,
            reminderInterval,
            pi
        )

        Log.i("Reminder", "Scheduled repeating reminder for slot=$slotId start=$reminderStart interval=$reminderInterval")
    }

    /**
     * Schedule an alarm to automatically re-register the geofence at the slot's start time.
     * This ensures the geofence is registered even if the slot wasn't checked at startup.
     */
    private fun scheduleSlotStartAlarm(
        context: Context,
        slotId: Long,
        startMillis: Long
    ) {
        if (startMillis <= System.currentTimeMillis()) {
            Log.i("SlotStartAlarm", "Not scheduling start alarm for slot=$slotId: time already passed")
            // Immediately register if start time already reached
            TrackingForegroundService.startForSlot(context, slotId)
            return
        }

        val am = context.getSystemService(AlarmManager::class.java)

        val intent = Intent(context, SlotStartAlarmReceiver::class.java).apply {
            action = "com.autonion.automationcompanion.ACTION_SLOT_START"
            putExtra("slotId", slotId)
        }

        val pi = PendingIntent.getBroadcast(
            context,
            ("slot_start_$slotId").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                startMillis,
                pi
            )
            Log.i("SlotStartAlarm", "Scheduled slot start alarm for slot=$slotId at time=$startMillis")
        } catch (e: SecurityException) {
            Log.w("SlotStartAlarm", "Failed to schedule exact alarm, trying inexact: ${e.message}")
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                startMillis,
                pi
            )
        }
    }


    //doSaveSlot(remindMinutes: Int, useRootToggle: Boolean)
    private fun doSaveSlot(
        remindMinutes: Int,
        actions: List<AutomationAction>
    ) {
        // 🔒 1️⃣ Permission preflight (NEW)
        val missing = PermissionPreflight
            .missingSystemPermissions(this, actions)

        if (missing.isNotEmpty()) {
            val intent = PermissionPreflight.settingsIntent(missing.first())
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

    fun scheduleMidnightReset(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager

        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 5)
            add(java.util.Calendar.DATE, 1)
        }

        val intent = Intent(context, MidnightResetReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        am.setInexactRepeating(
            android.app.AlarmManager.RTC_WAKEUP,
            cal.timeInMillis,
            android.app.AlarmManager.INTERVAL_DAY,
            pi
        )
    }


    private fun ensureSmsPermission(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.SEND_SMS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            onGranted()
        } else {
            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
        }
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
        if (latD == null || lngD == null) {
            Toast.makeText(this, "Invalid lat/lng", Toast.LENGTH_SHORT).show()
            return
        }
        if (startHour < 0 || endHour < 0) {
            Toast.makeText(this, "Set start/end times", Toast.LENGTH_SHORT).show()
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

        CoroutineScope(Dispatchers.IO).launch {
            val repo = AppDatabase.get(applicationContext).slotDao()

            val activeDaysString =
                if (selectedDays.size == 7) {
                    "ALL"
                } else {
                    selectedDays.joinToString(",")
                }


            val slotEntity = Slot(
                lat = latD,
                lng = lngD,
                radiusMeters = radius.toFloat(),
                startMillis = startMillis,
                endMillis = endMillis,
                remindBeforeMinutes = remindMinutes,
                actions = actions,
                activeDays = activeDaysString
            )

            val dao = AppDatabase.get(applicationContext).slotDao()

            val finalId = if (editingSlotId == null) {
                dao.insert(slotEntity)
            } else {
                dao.update(
                    slotEntity.copy(
                        id = editingSlotId!!
                    )
                )
                editingSlotId!!
            }


            runOnUiThread {
                // 1️⃣ ensure all old slots are alive
                TrackingForegroundService.startAll(this@SlotConfigActivity)

                // 2️⃣ Schedule alarm for this slot's start time (NEW - CRITICAL FIX)
                scheduleSlotStartAlarm(
                    context = this@SlotConfigActivity,
                    slotId = finalId,
                    startMillis = startMillis
                )

                scheduleLocationReminders(
                    context = this@SlotConfigActivity,
                    slotId = finalId,
                    startMillis = startMillis,
                    remindMinutes = remindMinutes
                )

                Toast.makeText(
                    this@SlotConfigActivity,
                    "Saved slot id=$finalId",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
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

