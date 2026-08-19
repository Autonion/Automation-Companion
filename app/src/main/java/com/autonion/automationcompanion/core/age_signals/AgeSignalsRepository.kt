package com.autonion.automationcompanion.core.age_signals

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
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
 * v0.0.4 changes:
 * - `userStatus` is deprecated → replaced by `ageRangeSource`
 * - New `requestAgeSignalsAccess(Activity)` step returns sharing consent status
 * - Supports Brazil in-app prompt for unsupervised users
 *
 * This API only returns data for users in jurisdictions with age-verification
 * laws (currently Texas, expanding to Brazil and other regions). For all other
 * users the result is [AgeSignalResult.Unavailable] and access is fully unrestricted.
 *
 * The ONLY scenario that blocks the user is when [AgeSharingStatus.NOT_SHARED],
 * meaning the user or parent explicitly declined to share age signals.
 */
object AgeSignalsRepository {

    private const val TAG = "AgeSignalsRepo"
    private const val PREFS_NAME = "autonion_age_signals"
    private const val KEY_AGE_LOWER = "age_lower"
    private const val KEY_AGE_UPPER = "age_upper"
    private const val KEY_AGE_RANGE_SOURCE = "age_range_source"
    private const val KEY_AGE_SHARING_STATUS = "age_sharing_status"
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
     * prompt for age signal sharing consent. This prompt is only shown to users
     * in regulated jurisdictions (currently Texas and Brazil).
     *
     * @return the [AgeSharingStatus] indicating whether age signals will be available
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
            if (isServiceUnavailableError(e)) {
                AgeSharingStatus.SHARED // Not in a regulated region, allow access
            } else {
                AgeSharingStatus.SHARED // Graceful degradation — don't block on errors
            }
        }
    }

    /**
     * Step 2 of the v0.0.4 two-step flow.
     *
     * Fetch age signals from Google Play after access has been granted.
     * Should be called after [requestAgeAccess] returns [AgeSharingStatus.SHARED].
     *
     * Safe to call from any coroutine scope on any dispatcher — the Play
     * Task is inherently async and `.await()` suspends without blocking.
     *
     * @return the fetched [AgeSignalResult]
     */
    suspend fun fetchAgeSignals(context: Context): AgeSignalResult {
        return try {
            val manager = AgeSignalsManagerFactory.create(context)
            val request = AgeSignalsRequest.builder().build()
            val playResult = manager.checkAgeSignals(request).await()

            val ageSource = mapAgeRangeSource(playResult.ageRangeSource())
            val result = AgeSignalResult.Available(
                ageLower = playResult.ageLower() ?: 0,
                ageUpper = playResult.ageUpper() ?: 0,
                ageRangeSource = ageSource,
                ageSharingStatus = AgeSharingStatus.SHARED,
                installId = playResult.installId()
            )
            _ageSignalState.value = result
            cacheResult(result)
            Log.d(TAG, "Age signals fetched: age=${result.ageLower}-${result.ageUpper}, source=$ageSource")
            result
        } catch (e: Exception) {
            // Common: CANNOT_BIND_TO_SERVICE (user not in regulated region or service inactive)
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
     * Combined convenience method: performs both request + fetch in one call.
     *
     * If the user/parent declines sharing (NOT_SHARED), emits a blocked state.
     * If VERIFICATION_REQUIRED, emits Unavailable (user must visit Play Store).
     */
    suspend fun requestAndFetchAgeSignals(activity: Activity): AgeSignalResult {
        val sharingStatus = requestAgeAccess(activity)

        return when (sharingStatus) {
            AgeSharingStatus.SHARED -> {
                fetchAgeSignals(activity)
            }
            AgeSharingStatus.NOT_SHARED -> {
                // Parent/user explicitly denied sharing — block access
                val result = AgeSignalResult.Available(
                    ageLower = 0,
                    ageUpper = 0,
                    ageRangeSource = AgeRangeSource.UNKNOWN,
                    ageSharingStatus = AgeSharingStatus.NOT_SHARED
                )
                _ageSignalState.value = result
                cacheResult(result)
                result
            }
            AgeSharingStatus.VERIFICATION_REQUIRED -> {
                // User needs to verify in Play Store first
                val result = AgeSignalResult.Available(
                    ageLower = 0,
                    ageUpper = 0,
                    ageRangeSource = AgeRangeSource.UNKNOWN,
                    ageSharingStatus = AgeSharingStatus.VERIFICATION_REQUIRED
                )
                _ageSignalState.value = result
                cacheResult(result)
                result
            }
        }
    }

    /**
     * Returns `true` ONLY when a user or parent has explicitly declined
     * to share age signals ([AgeSharingStatus.NOT_SHARED]).
     *
     * All other cases — including errors, users outside regulated regions,
     * and users awaiting verification — return `false` (unrestricted access).
     */
    fun isParentallyBlocked(): Boolean {
        val current = _ageSignalState.value
        return current is AgeSignalResult.Available &&
                current.ageSharingStatus == AgeSharingStatus.NOT_SHARED
    }

    // ─── Internal Helpers ──────────────────────────────────

    private fun mapAgeRangeSource(sourceValue: Int?): AgeRangeSource {
        // Map API integer constants to our domain enum
        // The actual constant names/values depend on the 0.0.4 SDK;
        // these are mapped based on the documented tier system
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
            1 -> AgeSharingStatus.SHARED
            2 -> AgeSharingStatus.NOT_SHARED
            3 -> AgeSharingStatus.VERIFICATION_REQUIRED
            else -> AgeSharingStatus.SHARED // Default to allow — graceful degradation
        }
    }

    private fun isServiceUnavailableError(e: Exception): Boolean {
        // CANNOT_BIND_TO_SERVICE is the expected error when the user is not
        // in a regulated region or the service hasn't been activated yet
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
            .putString(KEY_INSTALL_ID, result.installId)
            .apply()
    }

    private fun restoreCachedResult() {
        if (!prefs.getBoolean(KEY_HAS_RESULT, false)) return
        try {
            val sourceName = prefs.getString(KEY_AGE_RANGE_SOURCE, null)
            val sharingName = prefs.getString(KEY_AGE_SHARING_STATUS, null)

            // Handle migration from v0.0.3 cache (old KEY_USER_STATUS)
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
                installId = prefs.getString(KEY_INSTALL_ID, null)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore cached age signals", e)
        }
    }

    /**
     * Clears all cached age signal data. Useful for testing or account switching.
     */
    fun clearCache() {
        prefs.edit().clear().apply()
        _ageSignalState.value = AgeSignalResult.Unavailable
    }
}
