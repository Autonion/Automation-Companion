package com.autonion.automationcompanion.features.automation.actions.models

/**
 * ConfiguredAction represents the configuration state of an action as the user builds it.
 * This is separate from AutomationAction which is the final executable action.
 *
 * ConfiguredAction holds validation-relevant data needed by UI:
 * - For SMS: requires message and contacts validation
 * - For Volume: no validation needed (safe defaults)
 * - For Brightness: no validation needed
 * - For DND: no validation needed
 * - For Display actions: no validation needed (safe defaults, with graceful fallbacks)
 *
 * This model is trigger-agnostic: any system context (location, battery, app state, time, etc.)
 * can construct and reuse these actions without modification.
 */
sealed class ConfiguredAction {
    /**
     * Send SMS action configuration.
     * Requires: non-empty message and at least one contact
     */
    data class Audio(
        val ringVolume: Int,      // 0-7
        val mediaVolume: Int      // 0-15
    ) : ConfiguredAction()

    /**
     * Brightness action configuration.
     * Level: 10-255 (standard Android brightness range)
     */
    data class Brightness(
        val level: Int            // 10-255
    ) : ConfiguredAction()

    /**
     * Do Not Disturb action configuration.
     */
    data class Dnd(
        val enabled: Boolean
    ) : ConfiguredAction()

    /**
     * Send SMS action configuration.
     * Requires: non-empty message and at least one contact
     */
    data class SendSms(
        val message: String,
        val contactsCsv: String   // Semicolon-separated phone numbers
    ) : ConfiguredAction()

    /**
     * Dark Mode action configuration.
     * Non-root only: Uses Settings.Secure on Android 10+ or graceful fallback to Settings app.
     */
    data class DarkMode(
        val enabled: Boolean
    ) : ConfiguredAction()

    /**
     * Auto-rotate action configuration.
     * Non-root only: Uses Settings.System ACCELEROMETER_ROTATION.
     */
    data class AutoRotate(
        val enabled: Boolean
    ) : ConfiguredAction()

    /**
     * Screen timeout action configuration.
     * Values: 15_000, 30_000, 60_000, 300_000 milliseconds
     * Non-root only: Uses Settings.System SCREEN_OFF_TIMEOUT.
     */
    data class ScreenTimeout(
        val durationMs: Int       // 15000, 30000, 60000, or 300000
    ) : ConfiguredAction()

    /**
     * Night Light action configuration.
     * Non-root only: Uses Settings.Secure NIGHT_DISPLAY on Android 7+ or graceful fallback.
     */
    data class NightLight(
        val enabled: Boolean
    ) : ConfiguredAction()

    /**
     * Keep screen awake while slot is active.
     * Implementation: Uses partial wake lock acquired by foreground service.
     * Duration managed by trigger lifecycle (acquired when trigger activates, released when deactivates).
     */
    data class KeepScreenAwake(
        val enabled: Boolean
    ) : ConfiguredAction()
}
