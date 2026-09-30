package com.autonion.automationcompanion.core.vision

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build

/** Builds the system consent intent without attempting to retain or reuse projection grants. */
fun MediaProjectionManager.createAutomationCaptureIntent(fullDisplayOnly: Boolean): Intent {
    return if (fullDisplayOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    } else {
        createScreenCaptureIntent()
    }
}
