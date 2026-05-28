package com.autonion.automationcompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.autonion.automationcompanion.ui.AppNavHost
import androidx.activity.compose.setContent
import com.autonion.automationcompanion.ui.theme.AppTheme
import com.autonion.automationcompanion.features.system_context_automation.wifi.engine.WiFiMonitorManager
import com.autonion.automationcompanion.features.system_context_automation.battery.engine.BatteryServiceManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        
        // Initialize WiFi monitoring for Android 7+
        WiFiMonitorManager.initialize(this)

        // Resume battery monitoring if any battery automations are active
        BatteryServiceManager.startMonitoringIfNeeded(this)
        
        // Start ExtensionBridgeServer in background — no longer blocks first frame
        lifecycleScope.launch(Dispatchers.IO) {
            com.autonion.automationcompanion.features.semantic_automation.core.ExtensionBridgeServer.getInstance(this@MainActivity)
        }
        
        setContent {
            AppTheme {
                AppNavHost()
            }
        }
    }
}