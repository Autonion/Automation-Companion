package com.autonion.automationcompanion.features.system_context_automation.location.helpers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.GeofenceBroadcastReceiver
import com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** Access is serialized by LocationAutomationController.mutex, including event validation. */
internal object LocationGeofenceRegistry {
    private const val PREFS = "location_geofence_registry_v2"
    private const val RETIRED = "retired_ids"
    private const val PENDING = "pending_additions"
    private const val LEGACY_REMOVED = "legacy_removed"
    private const val SLOT_PREFIX = "slot_"
    private const val SIGNATURE_PREFIX = "signature_"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = "com.autonion.automationcompanion.LOCATION_GEOFENCE_TRANSITION"
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    fun currentSlotId(context: Context, requestId: String): Long? {
        val slotId = requestId.substringBefore(':').toLongOrNull() ?: return null
        val prefs = preferences(context)
        return slotId.takeIf {
            prefs.getString("$SLOT_PREFIX$slotId", null) == requestId &&
                requestId !in prefs.getStringSet(PENDING, emptySet()).orEmpty()
        }
    }

    fun hasPendingCleanup(context: Context): Boolean =
        !preferences(context).getStringSet(RETIRED, emptySet()).isNullOrEmpty() ||
            !preferences(context).getStringSet(PENDING, emptySet()).isNullOrEmpty()

    fun retireSlot(context: Context, slotId: Long) {
        val prefs = preferences(context)
        val retired = prefs.getStringSet(RETIRED, emptySet()).orEmpty().toMutableSet()
        retired.add(slotId.toString())
        prefs.getString("$SLOT_PREFIX$slotId", null)?.let(retired::add)
        prefs.edit().remove("$SLOT_PREFIX$slotId").remove("$SIGNATURE_PREFIX$slotId")
            .putStringSet(RETIRED, retired).commit()
    }

    fun retireTokens(context: Context, tokens: Set<String>) {
        val prefs = preferences(context)
        val retired = prefs.getStringSet(RETIRED, emptySet()).orEmpty() + tokens
        prefs.edit().putStringSet(RETIRED, retired).commit()
    }

    fun registeredSlotIds(context: Context): Set<Long> = preferences(context).all
        .filterKeys { it.startsWith(SLOT_PREFIX) }
        .mapNotNull { (_, value) -> currentSlotId(context, value.toString()) }
        .toSet()

    private fun signature(slot: Slot) = "${slot.lat}:${slot.lng}:${slot.radiusMeters}"

    fun validCoordinates(slot: Slot): Boolean =
        slot.lat?.let { it.isFinite() && it in -90.0..90.0 } == true &&
            slot.lng?.let { it.isFinite() && it in -180.0..180.0 } == true &&
            slot.radiusMeters?.let { it.isFinite() && it > 0f } == true

    /**
     * Track presence continuously; LocationSchedule gates actions by day and time. Retired IDs
     * are persisted before API calls, rejecting late events and allowing cleanup to be retried.
     */
    suspend fun synchronize(context: Context, slots: List<Slot>, resetPresence: Boolean = false): Set<Long> {
        val prefs = preferences(context)
        val dao = AppDatabase.get(context).slotDao()
        val permitted = PermissionUtils.isLocationPermissionGranted(context) &&
            LocationAutomationController.isLocationEnabled(context)
        val desired = slots.filter {
            permitted && it.enabled && it.triggerType == "LOCATION" && validCoordinates(it) &&
                LocationSchedule.nextWindow(it) != null
        }.associateBy { it.id }
        val current = prefs.all.filterKeys { it.startsWith(SLOT_PREFIX) }
            .mapNotNull { (key, value) ->
                key.removePrefix(SLOT_PREFIX).toLongOrNull()?.let { it to value.toString() }
            }.toMap().toMutableMap()
        val retired = prefs.getStringSet(RETIRED, emptySet()).orEmpty().toMutableSet()
        val pending = prefs.getStringSet(PENDING, emptySet()).orEmpty()
        val editor = prefs.edit()
        for ((id, token) in current.toMap()) {
            val slot = desired[id]
            if (resetPresence || token in pending || slot == null ||
                prefs.getString("$SIGNATURE_PREFIX$id", null) != signature(slot)) {
                retired.add(token)
                editor.remove("$SLOT_PREFIX$id").remove("$SIGNATURE_PREFIX$id")
                current.remove(id)
                dao.updateInsideGeofence(id, false)
            }
        }
        // Old registrations used numeric IDs and a different PendingIntent for each slot.
        if (!prefs.getBoolean(LEGACY_REMOVED, false)) retired.addAll(slots.map { it.id.toString() })
        retired.addAll(pending)
        editor.putStringSet(RETIRED, retired).remove(PENDING).commit()

        val client = LocationServices.getGeofencingClient(context)
        if (retired.isNotEmpty()) {
            try {
                withTimeout(4_000) { client.removeGeofences(retired.toList()).await() }
                prefs.edit().putStringSet(RETIRED, emptySet()).putBoolean(LEGACY_REMOVED, true).commit()
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                Log.w("LocationGeofences", "Cleanup pending; will retry", e)
                return current.keys
            }
        } else {
            prefs.edit().putBoolean(LEGACY_REMOVED, true).apply()
        }

        val additions = desired.values.filter { it.id !in current }
        if (additions.isEmpty()) return current.keys
        val tokens = additions.associate { it.id to "${it.id}:${UUID.randomUUID()}" }
        val geofences = additions.map { slot ->
            dao.updateInsideGeofence(slot.id, false)
            Geofence.Builder()
                .setRequestId(tokens.getValue(slot.id))
                .setCircularRegion(slot.lat!!, slot.lng!!, slot.radiusMeters!!)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        }
        val addEditor = prefs.edit().putStringSet(PENDING, tokens.values.toSet())
        additions.forEach { slot ->
            addEditor.putString("$SLOT_PREFIX${slot.id}", tokens.getValue(slot.id))
                .putString("$SIGNATURE_PREFIX${slot.id}", signature(slot))
        }
        addEditor.commit()
        var addition: com.google.android.gms.tasks.Task<Void>? = null
        try {
            addition = client.addGeofences(
                GeofencingRequest.Builder()
                    .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                    .addGeofences(geofences).build(), pendingIntent(context)
            )
            withTimeout(4_000) { addition.await() }
            prefs.edit().remove(PENDING).commit()
            return current.keys + tokens.keys
        } catch (e: Exception) {
            val failed = prefs.edit().remove(PENDING)
            tokens.keys.forEach { failed.remove("$SLOT_PREFIX$it").remove("$SIGNATURE_PREFIX$it") }
            failed.putStringSet(RETIRED, tokens.values.toSet()).commit()
            // Cancelling await does not cancel the Play Services task. If it succeeds after an
            // earlier removal, retire the exact abandoned tokens again; new registrations have
            // different tokens and are unaffected.
            addition?.addOnSuccessListener {
                LocationAutomationController.retireAbandonedRegistration(context, tokens.values.toSet())
            }
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            Log.w("LocationGeofences", "Registration failed; will retry", e)
            return current.keys
        }
    }
}
