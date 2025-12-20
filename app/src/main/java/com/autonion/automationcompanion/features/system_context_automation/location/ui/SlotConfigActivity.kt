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
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.LocationReminderReceiver
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.TrackingForegroundService

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // optionally load defaults from intent extras

        setContent {
            MaterialTheme {
                SlotConfigScreen(
                    latitude = lat,
                    longitude = lng,
                    radiusMeters = radius,
                    message = message,
                    contactsCsv = contactsCsv,
                    startLabel = startLabel,
                    endLabel = endLabel,
                    onLatitudeChanged = { lat = it },
                    onLongitudeChanged = { lng = it },
                    onRadiusChanged = { radius = it },
                    onMessageChanged = { message = it },
                    onPickContactClicked = {
                        // ensure permission
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                            requestMultiplePermissions.launch(arrayOf(Manifest.permission.READ_CONTACTS))
                        }
                        val pick = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                        contactPickerLauncher.launch(pick)
                    },
//                    onPickLocationClicked = {
//                        // if you have a separate map-picker activity, launch it
//                        // mapPickerLauncher.launch(Intent(this, MapPickerActivity::class.java))
//                    },
                    onStartTimeClicked = {
                        showTimePicker(true)
                    },
                    onEndTimeClicked = {
                        showTimePicker(false)
                    },
//                    onSaveClicked = { remindMinutes,useRootToggle ->
//                        doSaveSlot(remindMinutes,useRootToggle)
//                    },
                    onSaveClicked = { remindMinutes ->
                        doSaveSlot(remindMinutes)
                    },
//                    initialLat = lat.toDoubleOrNull() ?: 16.504464,
//                    initialLng = lng.toDoubleOrNull() ?: 80.652678,
//                    initialRadius = radius.toFloat(),
//                    onMapPointSelected = { aLat, aLng ->
//                        lat = aLat.toString()
//                        lng = aLng.toString()
//                    }
                )
            }
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



    //doSaveSlot(remindMinutes: Int, useRootToggle: Boolean)
    private fun doSaveSlot(remindMinutes: Int) {
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
        if (contactsCsv.isBlank()) {
            Toast.makeText(this, "Select contacts", Toast.LENGTH_SHORT).show()
            return
        }

        // compute start/end millis similar to your earlier logic
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

        // persist using repository (example)
        CoroutineScope(Dispatchers.IO).launch {
            val repo = AppDatabase.get(applicationContext).slotDao()
            val slotEntity = Slot(
                lat = latD,
                lng = lngD,
                radiusMeters = radius.toFloat(),
                startMillis = startMillis,
                endMillis = endMillis,
                message = message.ifBlank { "Auto message" },
                contactsCsv = contactsCsv,
                remindBeforeMinutes = remindMinutes
//                useRootToggle = useRootToggle
            )
            val id = repo.insert(slotEntity)

            runOnUiThread {
                scheduleLocationReminders(
                    context = this@SlotConfigActivity,
                    slotId = id,
                    startMillis = startMillis,
                    remindMinutes = remindMinutes
                )

                TrackingForegroundService.startForSlot(this@SlotConfigActivity, id)
                Toast.makeText(this@SlotConfigActivity, "Saved slot id=$id", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
