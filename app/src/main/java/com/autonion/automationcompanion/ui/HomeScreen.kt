@file:OptIn(ExperimentalMaterial3Api::class)
package com.autonion.automationcompanion.ui


import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.autonion.automationcompanion.ui.components.FeatureCard


data class FeatureEntry(val id: String, val title:String, val description: String, val route:String)

@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    val features = listOf(
        FeatureEntry("gesture","Gesture Recording & Playback", "Record gestures across apps and replay as macros.", AutomationRoutes.GESTURE),
        FeatureEntry("dynamic_ui","Dynamic UI Path Recording","Record resilient UI paths instead of coordinates.",AutomationRoutes.DYN_UI),
        FeatureEntry("screen_ml", "Screen Understanding (On-device ML)", "Detect UI elements, OCR & contextualize screen content.", AutomationRoutes.SCREEN_UNDERSTAND),
        FeatureEntry("semantic", "Semantic Automation", "Create automations via natural language.", AutomationRoutes.SEMANTIC),
        FeatureEntry("conditional", "Conditional Macros", "Add conditions and guards to macros.", AutomationRoutes.CONDITIONAL),
        FeatureEntry("multi_app", "Multi-App Workflow Pipeline", "Create multi-step automations across apps.", AutomationRoutes.MULTI_APP),
        FeatureEntry("app_specific", "App-Specific Automation", "Per-app actions and optimized flows.", AutomationRoutes.APP_SPECIFIC),
        FeatureEntry("system_context", "System Context Automation", "Triggers based on location, time, battery, etc.", AutomationRoutes.SYSTEM_CONTEXT),
        FeatureEntry("emergency", "Emergency Trigger", "Panic gestures and emergency automations.", AutomationRoutes.EMERGENCY),
        FeatureEntry("debugger", "Automation Debugger", "Inspect and step through automation runs.", AutomationRoutes.DEBUGGER),
        FeatureEntry("cross_device", "Cross-Device Automation", "Coordinate automations across devices.", AutomationRoutes.CROSS_DEVICE),
        FeatureEntry("profile_learning", "Automation Profile Learning", "On-device model to personalize suggestions.", AutomationRoutes.PROFILE_LEARNING)
    )

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Automation Companion")})
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(features) { feature ->
                FeatureCard(
                    title = feature.title,
                    description = feature.description,
                    onClick = { onOpen(feature.route)}
                )
            }
        }
    }
}

@Preview
@Composable
fun HomeScreenPreview() {
    HomeScreen(onOpen = {})
}