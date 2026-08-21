package com.autonion.automationcompanion.core.update

import android.app.Activity
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.isFlexibleUpdateAllowed
import com.google.android.play.core.ktx.isImmediateUpdateAllowed

/**
 * Manages Google Play In-App Updates.
 *
 * ## How it works
 * - **Flexible update** (default): Downloads in the background, then shows a callback
 *   so you can display a Snackbar/banner asking the user to restart.
 * - **Immediate update**: Full-screen blocking UI — the user *must* update before
 *   continuing. Use for critical/security updates.
 *
 * ## Usage
 * ```kotlin
 * // In your Activity.onCreate(), BEFORE super.onCreate():
 * val updateManager = InAppUpdateManager(this)
 * // later…
 * updateManager.checkForUpdate()
 * ```
 */
class InAppUpdateManager(
    private val activity: ComponentActivity,
    /**
     * Called when a flexible update has been downloaded and is ready to install.
     * Show a Snackbar or dialog, then call [completeUpdate] when the user agrees.
     */
    private val onUpdateDownloaded: (() -> Unit)? = null,
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "InAppUpdateManager"

        /**
         * How many days since the Play Store learned about the update before we
         * escalate from flexible → immediate. Adjust to your preference.
         */
        private const val DAYS_FOR_IMMEDIATE_UPDATE = 7
    }

    private val appUpdateManager: AppUpdateManager =
        AppUpdateManagerFactory.create(activity.applicationContext)

    private lateinit var updateResultLauncher: ActivityResultLauncher<IntentSenderRequest>

    private val installStateListener: InstallStateUpdatedListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADED -> {
                Log.i(TAG, "Update downloaded — ready to install")
                onUpdateDownloaded?.invoke() ?: completeUpdate()
            }
            InstallStatus.INSTALLED -> {
                Log.i(TAG, "Update installed successfully")
                unregisterListener()
            }
            InstallStatus.FAILED -> {
                Log.w(TAG, "Update failed — errorCode=${state.installErrorCode()}")
            }
            else -> {
                // PENDING, DOWNLOADING, INSTALLING, CANCELED, UNKNOWN — no action
            }
        }
    }

    init {
        // Register the activity result launcher — must happen before STARTED
        updateResultLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                Log.w(TAG, "User declined or update flow failed (code=${result.resultCode})")
            }
        }

        // Observe lifecycle so we can clean up and handle the resume case
        activity.lifecycle.addObserver(this)
    }

    // ─── Public API ──────────────────────────────────────────────────────

    /**
     * Checks the Play Store for an available update and starts the update flow
     * automatically if one is found.
     */
    fun checkForUpdate() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            val isAvailable =
                info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE

            val staleDays = info.clientVersionStalenessDays() ?: 0

            when {
                // If the update has been available for a while, force an immediate update
                isAvailable && staleDays >= DAYS_FOR_IMMEDIATE_UPDATE
                        && info.isImmediateUpdateAllowed -> {
                    Log.i(TAG, "Starting IMMEDIATE update (stale $staleDays days)")
                    startUpdate(info, AppUpdateType.IMMEDIATE)
                }

                // Otherwise use a non-disruptive flexible update
                isAvailable && info.isFlexibleUpdateAllowed -> {
                    Log.i(TAG, "Starting FLEXIBLE update")
                    appUpdateManager.registerListener(installStateListener)
                    startUpdate(info, AppUpdateType.FLEXIBLE)
                }

                // Immediate-only fallback (some apps don't support flexible)
                isAvailable && info.isImmediateUpdateAllowed -> {
                    Log.i(TAG, "Starting IMMEDIATE update (flexible not allowed)")
                    startUpdate(info, AppUpdateType.IMMEDIATE)
                }

                else -> {
                    Log.d(TAG, "No update available or not allowed")
                }
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to check for update", e)
        }
    }

    /**
     * Triggers the actual install after a flexible download completes.
     * Call this from your Snackbar / dialog action button.
     */
    fun completeUpdate() {
        appUpdateManager.completeUpdate()
    }

    // ─── Lifecycle handling ──────────────────────────────────────────────

    override fun onResume(owner: LifecycleOwner) {
        // If the user left the app mid-update, resume the flow
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            // Flexible: download finished while app was backgrounded
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                Log.i(TAG, "Resuming — update was downloaded in background")
                onUpdateDownloaded?.invoke() ?: completeUpdate()
            }

            // Immediate: update was in progress but the user navigated away
            if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                Log.i(TAG, "Resuming — continuing immediate update")
                startUpdate(info, AppUpdateType.IMMEDIATE)
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        unregisterListener()
    }

    // ─── Internals ───────────────────────────────────────────────────────

    private fun unregisterListener() {
        appUpdateManager.unregisterListener(installStateListener)
    }

    private fun startUpdate(info: AppUpdateInfo, @AppUpdateType type: Int) {
        appUpdateManager.startUpdateFlowForResult(
            info,
            updateResultLauncher,
            AppUpdateOptions.newBuilder(type).build()
        )
    }
}
