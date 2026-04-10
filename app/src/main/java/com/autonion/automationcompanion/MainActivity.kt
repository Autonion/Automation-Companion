package com.autonion.automationcompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.autonion.automationcompanion.ui.AppNavHost
import androidx.activity.compose.setContent
import com.autonion.automationcompanion.ui.theme.AppTheme
import com.autonion.automationcompanion.features.system_context_automation.wifi.engine.WiFiMonitorManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize WiFi monitoring for Android 7+
        WiFiMonitorManager.initialize(this)
        
        // Start ExtensionBridgeServer (Semantic Automation) in the background so the browser extension can instantly connect anytime
        com.autonion.automationcompanion.features.semantic_automation.core.ExtensionBridgeServer.getInstance(this)
        
        setContent {
            AppTheme {
                AppNavHost()
            }
        }
    }
}