package com.autonion.automationcompanion.core.age_signals

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.SignificantChangeStatus as PlaySignificantChangeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Singleton repository for the Google Play Age Signals API (v0.0.4).
 *
 * Responsibilities:
 * - Two-step flow: [requestAgeAccess] → [fetchAgeSignals]
 * - Caches the latest result in memory ([StateFlow]) and on disk ([SharedPreferences])
 * - Exposes a single blocking predicate: [isParentallyBlocked]
 *
 * The ONLY scenario that blocks the user is when a parent/guardian explicitly
 * declined access ([SignificantChangeStatus.DECLINED]).
 * All other users (minors, adults, users outside regulated regions, users who choose
 * not to share signals) have full unrestricted access to the application.
 */
object AgeSignalsRepository {

    private const val TAG = "AgeSignalsRepo"
    private const val PREFS_NAME = "autonion_age_signals"
    private const val KEY_AGE_LOWER = "age_lower"
    private const val KEY_AGE_UPPER = "age_upper"
    private const val KEY_AGE_RANGE_SOURCE = "age_range_source"
    private const val KEY_AGE_SHARING_STATUS = "age_sharing_status"
    private const val KEY_SIGNIFICANT_CHANGE_STATUS = "significant_change_status"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_HAS_RESULT = "has_result"

    @Volatile
    private var initialized = false
    private lateinit var prefs: SharedPreferences

    private val _ageSignalState = MutableStateFlow<AgeSignalResult>(AgeSignalResult.Unavailable)

    /** Observable age signal state for reactive UI consumption. */
    val ageSignalState: StateFlow<AgeSignalResult> = _ageSignalState.asStateFlow()

    fun getInstance(context: Context): AgeSignalsRepository {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    prefs = context.applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    // Restore cached result (if any) so UI has data before network call
                    restoreCachedResult()
                    initialized = true
                }
            }
        }
        return this
    }

    /**
     * Step 1 of the v0.0.4 two-step flow.
     *
     * Calls `requestAgeSignalsAccess(Activity)` to trigger the Google Play-managed
     * prompt for age signal sharing consent if applicable in the user's jurisdiction.
     */
    suspend fun requestAgeAccess(activity: Activity): AgeSharingStatus {
        return try {
            val manager = AgeSignalsManagerFactory.create(activity)
            val accessRequest = AgeSignalsAccessRequest.builder()
                .setActivity(activity)
                .build()
            val accessResult = manager.requestAgeSignalsAccess(accessRequest).await()

            val status = mapAgeSharingStatus(accessResult.ageSignalsStatus())
            Log.d(TAG, "Age signals access request result: $status")
            status
        } catch (e: Exception) {
            Log.d(TAG, "Age signals access request failed: ${e.message}")
            AgeSharingStatus.UNSPECIFIED
        }
    }

    /**
     * Step 2 of the v0.0.4 two-step flow.
     *
     * Fetch age signals from Google Play.
     */
    suspend fun fetchAgeSignals(context: Context): AgeSignalResult {
        return try {
            val manager = AgeSignalsManagerFactory.create(context)
            val request = AgeSignalsRequest.builder().build()
            val playResult = manager.checkAgeSignals(request).await()

            val ageSource = mapAgeRangeSource(playResult.ageRangeSource())
            val sigStatus = mapSignificantChangeStatus(playResult.significantChangeStatus())

            val result = AgeSignalResult.Available(
                ageLower = playResult.ageLower() ?: 0,
                ageUpper = playResult.ageUpper() ?: 0,
                ageRangeSource = ageSource,
                ageSharingStatus = AgeSharingStatus.SHARED,
                significantChangeStatus = sigStatus,
                installId = playResult.installId()
            )
            _ageSignalState.value = result
            cacheResult(result)
            Log.d(TAG, "Age signals fetched: age=${result.ageLower}-${result.ageUpper}, source=$ageSource, sigStatus=$sigStatus")
            result
        } catch (e: Exception) {
            Log.d(TAG, "Age signals unavailable: ${e.message}")
            val result = if (isServiceUnavailableError(e)) {
                AgeSignalResult.Unavailable
            } else {
                AgeSignalResult.Error(e)
            }
            _ageSignalState.value = result
            result
        }
    }

    /**
     * Combined convenience method: performs request + fetch.
     *
     * Note: If age sharing is not active or declined, the user receives
     * [AgeSignalResult.Unavailable] (unrestricted access).
     */
    suspend fun requestAndFetchAgeSignals(activity: Activity): AgeSignalResult {
        val sharingStatus = requestAgeAccess(activity)

        return if (sharingStatus == AgeSharingStatus.SHARED) {
            fetchAgeSignals(activity)
        } else {
            // User did not share age signals or not in regulated region.
            // App access is completely unrestricted.
            val result = AgeSignalResult.Unavailable
            _ageSignalState.value = result
            result
        }
    }

    /**
     * Returns `true` ONLY when a parent/guardian has explicitly declined
     * access via Google Play parental controls ([SignificantChangeStatus.DECLINED]).
     *
     * All other cases (including not sharing, minors, adults, errors, and users outside
     * regulated regions) return `false` (unrestricted access).
     */
    fun isParentallyBlocked(): Boolean {
        val current = _ageSignalState.value
        return current is AgeSignalResult.Available &&
                current.significantChangeStatus == SignificantChangeStatus.DECLINED
    }

    // ─── Internal Helpers ──────────────────────────────────

    private fun mapAgeRangeSource(sourceValue: Int?): AgeRangeSource {
        return when (sourceValue) {
            1 -> AgeRangeSource.SELF_DECLARED
            2 -> AgeRangeSource.ACCOUNT_SIGNALS
            3 -> AgeRangeSource.SUPERVISED
            4 -> AgeRangeSource.VERIFIED
            else -> AgeRangeSource.UNKNOWN
        }
    }

    private fun mapAgeSharingStatus(statusValue: Int?): AgeSharingStatus {
        return when (statusValue) {
            AgeSignalsStatus.SHARED -> AgeSharingStatus.SHARED
            AgeSignalsStatus.NOT_SHARED -> AgeSharingStatus.NOT_SHARED
            AgeSignalsStatus.VERIFICATION_REQUIRED -> AgeSharingStatus.VERIFICATION_REQUIRED
            else -> AgeSharingStatus.UNSPECIFIED
        }
    }

    private fun mapSignificantChangeStatus(statusValue: Int?): SignificantChangeStatus {
        return when (statusValue) {
            PlaySignificantChangeStatus.APPROVED -> SignificantChangeStatus.APPROVED
            PlaySignificantChangeStatus.PENDING -> SignificantChangeStatus.PENDING
            PlaySignificantChangeStatus.DECLINED -> SignificantChangeStatus.DECLINED
            else -> SignificantChangeStatus.UNSPECIFIED
        }
    }

    private fun isServiceUnavailableError(e: Exception): Boolean {
        val message = e.message ?: ""
        return message.contains("CANNOT_BIND_TO_SERVICE", ignoreCase = true) ||
                message.contains("API_NOT_AVAILABLE", ignoreCase = true)
    }

    private fun cacheResult(result: AgeSignalResult.Available) {
        prefs.edit()
            .putBoolean(KEY_HAS_RESULT, true)
            .putInt(KEY_AGE_LOWER, result.ageLower)
            .putInt(KEY_AGE_UPPER, result.ageUpper)
            .putString(KEY_AGE_RANGE_SOURCE, result.ageRangeSource.name)
            .putString(KEY_AGE_SHARING_STATUS, result.ageSharingStatus.name)
            .putString(KEY_SIGNIFICANT_CHANGE_STATUS, result.significantChangeStatus.name)
            .putString(KEY_INSTALL_ID, result.installId)
            .apply()
    }

    private fun restoreCachedResult() {
        if (!prefs.getBoolean(KEY_HAS_RESULT, false)) return
        try {
            val sigStatusName = prefs.getString(KEY_SIGNIFICANT_CHANGE_STATUS, null)
            val sigStatus = sigStatusName?.let {
                try { SignificantChangeStatus.valueOf(it) } catch (_: Exception) { SignificantChangeStatus.UNSPECIFIED }
            } ?: SignificantChangeStatus.UNSPECIFIED

            // If it was wrongfully saved as blocked from the previous code, discard it
            if (sigStatus != SignificantChangeStatus.DECLINED) {
                val sourceName = prefs.getString(KEY_AGE_RANGE_SOURCE, null)
                val sharingName = prefs.getString(KEY_AGE_SHARING_STATUS, null)

                val ageSource = sourceName?.let {
                    try { AgeRangeSource.valueOf(it) } catch (_: Exception) { AgeRangeSource.UNKNOWN }
                } ?: AgeRangeSource.UNKNOWN

                val sharingStatus = sharingName?.let {
                    try { AgeSharingStatus.valueOf(it) } catch (_: Exception) { AgeSharingStatus.SHARED }
                } ?: AgeSharingStatus.SHARED

                _ageSignalState.value = AgeSignalResult.Available(
                    ageLower = prefs.getInt(KEY_AGE_LOWER, 0),
                    ageUpper = prefs.getInt(KEY_AGE_UPPER, 0),
                    ageRangeSource = ageSource,
                    ageSharingStatus = sharingStatus,
                    significantChangeStatus = sigStatus,
                    installId = prefs.getString(KEY_INSTALL_ID, null)
                )
            } else {
                _ageSignalState.value = AgeSignalResult.Available(
                    ageLower = prefs.getInt(KEY_AGE_LOWER, 0),
                    ageUpper = prefs.getInt(KEY_AGE_UPPER, 0),
                    significantChangeStatus = SignificantChangeStatus.DECLINED
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore cached age signals", e)
        }
    }

    /**
     * Clears all cached age signal data.
     */
    fun clearCache() {
        prefs.edit().clear().apply()
        _ageSignalState.value = AgeSignalResult.Unavailable
    }
}
