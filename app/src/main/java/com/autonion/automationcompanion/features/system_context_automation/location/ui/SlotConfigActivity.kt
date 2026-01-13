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
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.AppInitManager
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.AutomationAction
import com.autonion.automationcompanion.features.system_context_automation.location.permissions.PermissionPreflight


class SlotConfigActivity : AppCompatActivity() {

    // UI state — keep simple and lift into a ViewModel when desired
    private var lat by mutableStateOf("61.979434")
    private var lng by mutableStateOf("99.171125")
    private var radius by mutableIntStateOf(300)
    private var message by mutableStateOf("")
    private var contactsCsv by mutableStateOf("")

    private var startLabel by mutableStateOf("--:--")
    private var endLabel by mutableStateOf("--:--")
    private var startHour = -1
    private var startMinute = -1
    private var endHour = -1
    private var endMinute = -1
    private var editingSlotId: Long? = null

    // Action toggle state (EDIT MODE AWARE)
    private var smsEnabled by mutableStateOf(false)

    private var volumeEnabled by mutableStateOf(false)
    private var ringVolume by mutableStateOf(5f)
    private var mediaVolume by mutableStateOf(5f)

    private var brightnessEnabled by mutableStateOf(false)
    private var brightness by mutableStateOf(150f)

    private var dndEnabled by mutableStateOf(false)
    private var remindBeforeMinutes by mutableStateOf("15")

    private var selectedDays by mutableStateOf(
        setOf("MON","TUE","WED","THU","FRI","SAT","SUN")
    )



    // Activity result launchers
    private val contactPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val uri: Uri? = res.data!!.data
            uri?.let { u ->
                val num = fetchPhoneNumberFromContact(u)
                if (!num.isNullOrBlank()) {
                    contactsCsv = if (contactsCsv.isBlank()) num else "$contactsCsv;$num"
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
                    message = message,
                    contactsCsv = contactsCsv,
                    startLabel = startLabel,
                    endLabel = endLabel,

                    smsEnabled = smsEnabled,
                    onSmsEnabledChange = { smsEnabled = it },

                    volumeEnabled = volumeEnabled,
                    ringVolume = ringVolume,
                    mediaVolume = mediaVolume,
                    onVolumeEnabledChange = { volumeEnabled = it },
                    onRingVolumeChange = { ringVolume = it },
                    onMediaVolumeChange = { mediaVolume = it },

                    brightnessEnabled = brightnessEnabled,
                    brightness = brightness,
                    onBrightnessEnabledChange = { brightnessEnabled = it },
                    onBrightnessChange = { brightness = it },

                    dndEnabled = dndEnabled,
                    onDndEnabledChange = { dndEnabled = it },
                    remindBeforeMinutes = remindBeforeMinutes,
                    onRemindBeforeMinutesChange = { remindBeforeMinutes = it },

                    onLatitudeChanged = { lat = it },
                    onLongitudeChanged = { lng = it },
                    onRadiusChanged = { radius = it },
                    onMessageChanged = { message = it },
                    onPickContactClicked = { pickContact() },
                    onStartTimeClicked = { showTimePicker(true) },
                    onEndTimeClicked = { showTimePicker(false) },
                    onPickFromMapClicked = { openMapPickerWebsite() },
                    onSaveClicked = { remind, actions ->
                        doSaveSlot(remind, actions)
                    },
                    selectedDays = selectedDays,
                    onSelectedDaysChange = { selectedDays = it },
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



        message = ""
        contactsCsv = ""

        slot.actions.forEach { action ->
            when (action) {
                is AutomationAction.SendSms -> {
                    smsEnabled = true
                    message = action.message
                    contactsCsv = action.contactsCsv
                }

                is AutomationAction.SetVolume -> {
                    volumeEnabled = true
                    ringVolume = action.ring.toFloat()
                    mediaVolume = action.media.toFloat()
                }

                is AutomationAction.SetBrightness -> {
                    brightnessEnabled = true
                    brightness = action.level.toFloat()
                }

                is AutomationAction.SetDnd -> {
                    dndEnabled = action.enabled
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

    val actions = mutableListOf<AutomationAction>()


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
        if (actions.any { it is AutomationAction.SendSms } && contactsCsv.isBlank()) {
            Toast.makeText(this, "Select contacts", Toast.LENGTH_SHORT).show()
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

}
