package com.autonion.automationcompanion.features.semantic_automation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * Mandatory privacy disclaimer dialog shown when a user first enables Cloud API mode.
 *
 * This dialog:
 * - Cannot be dismissed by tapping outside
 * - Clearly explains data exposure risks
 * - Provides a comparison table between Local SLM and Cloud LLM
 * - Requires explicit acceptance before Cloud API can be used
 */
@Composable
fun CloudApiDisclaimerDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* Cannot dismiss — must choose */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color(0xFFF57C00),
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    "Cloud API — Privacy Notice",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── Warning Banner ──
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFF3E0)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Security,
                            contentDescription = null,
                            tint = Color(0xFFE65100),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFE65100))) {
                                    append("Important: ")
                                }
                                append("When using Cloud API, your data will be sent to third-party servers operated by the API provider you choose (e.g., OpenAI, Google, Groq).")
                            },
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = Color(0xFF4E342E)
                        )
                    }
                }

                // ── What is shared ──
                Text("What data is sent to the cloud:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Column(
                    modifier = Modifier.padding(start = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    BulletPoint("Your automation prompts and commands")
                    BulletPoint("Screen element descriptions (accessibility tree text)")
                    BulletPoint("Chat messages sent in Omni-Chat")
                    BulletPoint("Goal parsing requests in Semantic Automation")
                }

                // ── Responsibility ──
                Text("Your responsibility:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Column(
                    modifier = Modifier.padding(start = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    BulletPoint("API key usage and billing are entirely your responsibility")
                    BulletPoint("Autonion does NOT collect, store, or forward any of your data")
                    BulletPoint("Data goes directly from your device to the API provider")
                    BulletPoint("Review your chosen provider's privacy policy")
                }

                Divider(modifier = Modifier.padding(vertical = 4.dp))

                // ── Comparison Table ──
                Text("Local SLM vs Cloud LLM:", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                ComparisonTable()

                // ── Recommendation ──
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            "💡 Recommendation: Use Local SLM or Local Server LLM for sensitive workflows. Reserve Cloud API for tasks requiring higher intelligence.",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE65100)
                )
            ) {
                Text("I Understand & Accept", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDecline) {
                Icon(
                    Icons.Filled.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Stay Offline")
            }
        }
    )
}

@Composable
private fun BulletPoint(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•  ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
    }
}

@Composable
private fun ComparisonTable() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Aspect", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("Local SLM", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("Cloud LLM", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
            Divider(modifier = Modifier.padding(vertical = 6.dp))

            ComparisonRow("Privacy", "✅ 100% on-device", "⚠️ Data sent to cloud")
            ComparisonRow("Speed", "Moderate", "⚡ Fast")
            ComparisonRow("Quality", "Limited (2-7B)", "🧠 High (100B+)")
            ComparisonRow("Cost", "Free", "💰 Pay-per-token")
            ComparisonRow("Internet", "Not required", "📶 Required")
            ComparisonRow("Models", "Gemma, Phi-2", "GPT-4o, Gemini, etc.")
        }
    }
}

@Composable
private fun ComparisonRow(aspect: String, local: String, cloud: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(aspect, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(local, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(cloud, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
    }
}
