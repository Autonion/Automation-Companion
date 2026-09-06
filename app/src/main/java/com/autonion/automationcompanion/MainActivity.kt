package com.autonion.automationcompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.autonion.automationcompanion.ui.AppNavHost
import androidx.activity.compose.setContent
import com.autonion.automationcompanion.ui.theme.AppTheme
import com.autonion.automationcompanion.features.system_context_automation.wifi.engine.WiFiMonitorManager
import com.autonion.automationcompanion.features.system_context_automation.battery.engine.BatteryServiceManager
import com.autonion.automationcompanion.core.update.InAppUpdateManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.widget.Toast

class MainActivity : ComponentActivity() {

    private lateinit var inAppUpdateManager: InAppUpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()

        // ── In-App Update: register BEFORE super.onCreate() ──
        inAppUpdateManager = InAppUpdateManager(
            activity = this,
            onUpdateDownloaded = {
                // Show a simple prompt; the user taps to restart
                Toast.makeText(
                    this,
                    "Update downloaded — restarting…",
                    Toast.LENGTH_SHORT
                ).show()
                inAppUpdateManager.completeUpdate()
            }
        )

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        // Initialize WiFi monitoring for Android 7+
        WiFiMonitorManager.initialize(this)

        // Resume battery monitoring if any battery automations are active
        BatteryServiceManager.startMonitoringIfNeeded(this)
        
        // Start ExtensionBridgeServer in background — no longer blocks first frame
        lifecycleScope.launch(Dispatchers.IO) {
            com.autonion.automationcompanion.features.semantic_automation.core.ExtensionBridgeServer.getInstance(this@MainActivity)
        }

        // Fetch age signals for compliance (Texas SB 2420, Brazil, etc.) — non-blocking
        // v0.0.4: Two-step flow — request access (triggers Play prompt) then fetch signals
        lifecycleScope.launch(Dispatchers.Main) {
            com.autonion.automationcompanion.core.age_signals.AgeSignalsRepository
                .getInstance(this@MainActivity)
                .requestAndFetchAgeSignals(this@MainActivity)
        }
        
        setContent {
            AppTheme {
                AppNavHost()
            }
        }

        // ── Check for updates after UI is ready ──
        inAppUpdateManager.checkForUpdate()
    }
}