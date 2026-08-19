package com.autonion.automationcompanion.core.age_signals

/**
 * Domain model for Play Age Signals API results (v0.0.4).
 *
 * Decouples the app from the raw Play Core library types so that
 * the rest of the codebase depends only on this sealed hierarchy.
 */
sealed class AgeSignalResult {

    /**
     * Age signals were successfully retrieved for a user in a regulated region
     * (currently Texas; may expand to Utah, Louisiana, Brazil, etc.).
     *
     * @param ageLower Inclusive lower bound of the user's age range (e.g. 13)
     * @param ageUpper Inclusive upper bound of the user's age range (e.g. 17)
     * @param ageRangeSource How the age range was determined (replaces deprecated userStatus in v0.0.4)
     * @param ageSharingStatus Whether the user/parent agreed to share age signals
     * @param installId Identifier for supervised installs (used for approval revocation)
     */
    data class Available(
        val ageLower: Int,
        val ageUpper: Int,
        val ageRangeSource: AgeRangeSource,
        val ageSharingStatus: AgeSharingStatus = AgeSharingStatus.SHARED,
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
 * Indicates the source / verification tier of the age range data.
 *
 * Replaces the deprecated `userStatus` field from API v0.0.3.
 * Higher tiers represent stronger verification methods.
 */
enum class AgeRangeSource {
    /** Source could not be determined */
    UNKNOWN,

    /** Age was self-declared by the user */
    SELF_DECLARED,

    /** Age was determined via Google account signals */
    ACCOUNT_SIGNALS,

    /** Age was verified via parental/family link supervision */
    SUPERVISED,

    /** Age was verified via government ID or equivalent strong verification */
    VERIFIED
}

/**
 * Result of calling `requestAgeSignalsAccess(Activity)` — indicates
 * whether the user or parent has agreed to share age signals with the app.
 *
 * New in API v0.0.4.
 */
enum class AgeSharingStatus {
    /** User/parent agreed to share age signals; proceed to checkAgeSignals() */
    SHARED,

    /** User declined or parent denied sharing age signals — app should restrict access */
    NOT_SHARED,

    /** User's age is unknown in a mandatory verification region; prompt to visit Play Store */
    VERIFICATION_REQUIRED
}
