package com.autonion.automationcompanion.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.autonion.automationcompanion.ui.AutomationRoutes

val WhatsNewAccentPurple = Color(0xFF7C4DFF)
val WhatsNewAccentCyan = Color(0xFF00BCD4)
val WhatsNewAccentBlue = Color(0xFF2979FF)
val WhatsNewAccentGreen = Color(0xFF00C853)
val WhatsNewAccentOrange = Color(0xFFFF9100)
val WhatsNewAccentRed = Color(0xFFFF3D00)

data class WhatsNewItem(
    val title: String,
    val category: String,
    val description: String,
    val icon: ImageVector,
    val iconTint: Color,
    val route: String? = null,
    val tag: String? = "NEW"
)

data class WhatsNewRelease(
    val versionName: String = "1.1.1",
    val versionCode: Int = 10,
    val releaseDate: String = "August 2026",
    val headline: String = "Cross-Device Flows, SLM Inference & Enhanced Nodes",
    val description: String = "Experience faster local AI inference, seamless desktop pairing, remote desktop unlocks, and powerful new node modes in the Flow Builder.",
    val youtubeUrl: String = YouTubeTutorials.WHATS_NEW,
    val features: List<WhatsNewItem>
)

object WhatsNewRepository {
    fun getCurrentRelease(versionName: String = "1.1.1"): WhatsNewRelease {
        return WhatsNewRelease(
            versionName = versionName,
            versionCode = 10,
            releaseDate = "August 2026",
            headline = "Cross-Device Flows, SLM Inference & Enhanced Nodes",
            description = "Experience faster local AI inference, seamless desktop pairing, remote desktop unlocks, and powerful new node modes in the Flow Builder.",
            youtubeUrl = YouTubeTutorials.WHATS_NEW,
            features = listOf(
                WhatsNewItem(
                    title = "Screen Understanding Node (3 Modes)",
                    category = "Flow Builder",
                    description = "Choose Elements (YOLO + A11y), UI Attribute (A11y-only), or OCR (Text recognition) for precise on-screen target detection.",
                    icon = Icons.Default.AccountTree,
                    iconTint = WhatsNewAccentPurple,
                    route = AutomationRoutes.FLOW_BUILDER,
                    tag = "UPDATED"
                ),
                WhatsNewItem(
                    title = "OTP-Based Device Pairing",
                    category = "Cross-Device Sync",
                    description = "Secure 6-digit PIN verification for pairing phone and PC. Automatically discovers and reconnects once paired.",
                    icon = Icons.Default.VpnKey,
                    iconTint = WhatsNewAccentBlue,
                    route = AutomationRoutes.CROSS_DEVICE,
                    tag = "NEW"
                ),
                WhatsNewItem(
                    title = "Desktop Flows Tab",
                    category = "Cross-Device Sync",
                    description = "Browse, remotely trigger, and monitor step-by-step execution of Desktop Flows directly from your phone.",
                    icon = Icons.Default.PlayCircle,
                    iconTint = WhatsNewAccentCyan,
                    route = AutomationRoutes.CROSS_DEVICE,
                    tag = "NEW"
                ),
                WhatsNewItem(
                    title = "On-Device GGUF SLM Engine",
                    category = "Semantic AI",
                    description = "Run local small language models (Qwen 2.5, Phi-3.5, Llama 3.2, Gemma 4) on-device with zero cloud dependencies and total privacy.",
                    icon = Icons.Default.Memory,
                    iconTint = WhatsNewAccentOrange,
                    route = AutomationRoutes.SEMANTIC_AUTOMATION,
                    tag = "UPDATED"
                ),
                WhatsNewItem(
                    title = "Remote Desktop Unlock",
                    category = "Cross-Device Sync",
                    description = "Unlock your Windows PC remotely from your phone via the Flows tab, even when on the Windows lock screen.",
                    icon = Icons.Default.LockOpen,
                    iconTint = WhatsNewAccentGreen,
                    route = AutomationRoutes.CROSS_DEVICE,
                    tag = "NEW"
                )
            )
        )
    }
}
