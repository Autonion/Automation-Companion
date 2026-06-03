package com.autonion.automationcompanion.core.age_signals

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.model.AgeSignalsVerificationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Singleton repository for the Google Play Age Signals API.
 *
 * Responsibilities:
 * - Fetches age signals via [AgeSignalsManagerFactory]
 * - Caches the latest result in memory ([StateFlow]) and on disk ([SharedPreferences])
 * - Exposes a single blocking predicate: [isParentallyBlocked]
 *
 * This API only returns data for users in jurisdictions with age-verification
 * laws (currently Texas). For all other users the result is [AgeSignalResult.Unavailable]
 * and access is fully unrestricted.
 *
 * The ONLY scenario that blocks the user is [UserStatus.SUPERVISED_APPROVAL_DENIED],
 * meaning a parent explicitly denied access through Google Play parental controls.
 */
object AgeSignalsRepository {

    private const val TAG = "AgeSignalsRepo"
    private const val PREFS_NAME = "autonion_age_signals"
    private const val KEY_AGE_LOWER = "age_lower"
    private const val KEY_AGE_UPPER = "age_upper"
    private const val KEY_USER_STATUS = "user_status"
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
     * Fetch age signals from Google Play.
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

            val userStatus = mapUserStatus(playResult.userStatus())
            val result = AgeSignalResult.Available(
                ageLower = playResult.ageLower() ?: 0,
                ageUpper = playResult.ageUpper() ?: 0,
                userStatus = userStatus,
                installId = playResult.installId()
            )
            _ageSignalState.value = result
            cacheResult(result)
            Log.d(TAG, "Age signals fetched: age=${result.ageLower}-${result.ageUpper}, status=$userStatus")
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
     * Returns `true` ONLY when a parent has explicitly denied access
     * via Google Play parental controls ([UserStatus.SUPERVISED_APPROVAL_DENIED]).
     *
     * All other cases — including minors, errors, and users outside
     * regulated regions — return `false` (unrestricted access).
     */
    fun isParentallyBlocked(): Boolean {
        val current = _ageSignalState.value
        return current is AgeSignalResult.Available &&
                current.userStatus == UserStatus.SUPERVISED_APPROVAL_DENIED
    }

    // ─── Internal Helpers ──────────────────────────────────

    private fun mapUserStatus(playStatus: Int?): UserStatus {
        // Constants from AgeSignalsVerificationStatus
        return when (playStatus) {
            AgeSignalsVerificationStatus.VERIFIED -> UserStatus.VERIFIED
            AgeSignalsVerificationStatus.SUPERVISED -> UserStatus.SUPERVISED
            AgeSignalsVerificationStatus.SUPERVISED_APPROVAL_PENDING -> UserStatus.SUPERVISED_APPROVAL_PENDING
            AgeSignalsVerificationStatus.SUPERVISED_APPROVAL_DENIED -> UserStatus.SUPERVISED_APPROVAL_DENIED
            AgeSignalsVerificationStatus.DECLARED -> UserStatus.DECLARED
            else -> UserStatus.UNKNOWN
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
            .putString(KEY_USER_STATUS, result.userStatus.name)
            .putString(KEY_INSTALL_ID, result.installId)
            .apply()
    }

    private fun restoreCachedResult() {
        if (!prefs.getBoolean(KEY_HAS_RESULT, false)) return
        try {
            val statusName = prefs.getString(KEY_USER_STATUS, null) ?: return
            _ageSignalState.value = AgeSignalResult.Available(
                ageLower = prefs.getInt(KEY_AGE_LOWER, 0),
                ageUpper = prefs.getInt(KEY_AGE_UPPER, 0),
                userStatus = UserStatus.valueOf(statusName),
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
