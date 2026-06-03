package com.autonion.automationcompanion.core.age_signals

/**
 * Domain model for Play Age Signals API results.
 *
 * Decouples the app from the raw Play Core library types so that
 * the rest of the codebase depends only on this sealed hierarchy.
 */
sealed class AgeSignalResult {

    /**
     * Age signals were successfully retrieved for a user in a regulated region
     * (currently Texas; may expand to Utah, Louisiana, etc.).
     *
     * @param ageLower Inclusive lower bound of the user's age range (e.g. 13)
     * @param ageUpper Inclusive upper bound of the user's age range (e.g. 17)
     * @param userStatus Verification / supervision status from Google Play
     * @param installId Identifier for supervised installs (used for approval revocation)
     */
    data class Available(
        val ageLower: Int,
        val ageUpper: Int,
        val userStatus: UserStatus,
        val installId: String? = null
    ) : AgeSignalResult()

    /**
     * The user is not in a regulated region, or Google Play's age
     * verification service is not yet active for this user.
     * The app should grant full unrestricted access.
     */
    data object Unavailable : AgeSignalResult()

    /**
     * The API call failed (network, service binding, outdated SDK, etc.).
     * The app should grant full unrestricted access (graceful degradation).
     */
    data class Error(val cause: Throwable? = null) : AgeSignalResult()
}

/**
 * Mirrors the verification / supervision statuses returned by the
 * Play Age Signals API.
 */
enum class UserStatus {
    /** Status could not be determined */
    UNKNOWN,

    /** User's age was verified through Google Play's age verification flow */
    VERIFIED,

    /** User self-declared their age (added in API v0.0.3) */
    DECLARED,

    /** User is supervised by a parent/guardian who approved this app */
    SUPERVISED,

    /** User is supervised, approval is pending parent/guardian decision */
    SUPERVISED_APPROVAL_PENDING,

    /**
     * User is supervised by a parent/guardian who **explicitly denied**
     * access to this app. This is the ONLY status that triggers blocking.
     */
    SUPERVISED_APPROVAL_DENIED
}
