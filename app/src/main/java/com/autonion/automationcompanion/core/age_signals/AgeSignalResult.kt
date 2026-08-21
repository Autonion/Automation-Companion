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
     * @param significantChangeStatus Whether a parent/guardian approved or declined access
     * @param installId Identifier for supervised installs (used for approval revocation)
     */
    data class Available(
        val ageLower: Int,
        val ageUpper: Int,
        val ageRangeSource: AgeRangeSource = AgeRangeSource.UNKNOWN,
        val ageSharingStatus: AgeSharingStatus = AgeSharingStatus.SHARED,
        val significantChangeStatus: SignificantChangeStatus = SignificantChangeStatus.UNSPECIFIED,
        val installId: String? = null
    ) : AgeSignalResult()

    /**
     * The user is not in a regulated region, or Google Play's age
     * verification service is not yet active for this user.
     * The app grants full unrestricted access.
     */
    data object Unavailable : AgeSignalResult()

    /**
     * The API call failed (network, service binding, outdated SDK, etc.).
     * The app grants full unrestricted access (graceful degradation).
     */
    data class Error(val cause: Throwable? = null) : AgeSignalResult()
}

/**
 * Indicates the source / verification tier of the age range data.
 *
 * Replaces the deprecated `userStatus` field from API v0.0.3.
 */
enum class AgeRangeSource {
    UNKNOWN,
    SELF_DECLARED,
    ACCOUNT_SIGNALS,
    SUPERVISED,
    VERIFIED
}

/**
 * Result of calling `requestAgeSignalsAccess(Activity)`.
 */
enum class AgeSharingStatus {
    UNSPECIFIED,
    SHARED,
    NOT_SHARED,
    VERIFICATION_REQUIRED
}

/**
 * Indicates whether a parent/guardian approved or declined access in Google Play.
 * Only DECLINED triggers an access restriction.
 */
enum class SignificantChangeStatus {
    UNSPECIFIED,
    APPROVED,
    PENDING,
    DECLINED
}
