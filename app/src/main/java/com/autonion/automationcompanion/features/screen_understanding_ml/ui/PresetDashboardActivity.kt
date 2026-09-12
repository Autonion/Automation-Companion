package com.autonion.automationcompanion.features.screen_understanding_ml.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.autonion.automationcompanion.ui.theme.AppTheme

/** Legacy entry point uses the same dashboard and preset editor as in-app navigation. */
class PresetDashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { ScreenMLRoute(onBack = { finish() }) } }
    }
}
