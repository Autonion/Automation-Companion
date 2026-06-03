@file:OptIn(ExperimentalMaterial3Api::class)
package com.autonion.automationcompanion.features.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.core.age_signals.AgeSignalResult
import com.autonion.automationcompanion.core.age_signals.AgeSignalsRepository
import com.autonion.automationcompanion.core.age_signals.UserStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Informational settings screen showing the current age verification status.
 *
 * Purely for transparency — no blocking logic here. Shows the user what
 * age signals (if any) have been received from Google Play, the
 * verification method, and a legal disclaimer about data usage.
 */
@Composable
fun AgeComplianceScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ageRepo = remember { AgeSignalsRepository.getInstance(context) }
    val ageState by ageRepo.ageSignalState.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Age & Compliance") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            isRefreshing = true
                            scope.launch(Dispatchers.IO) {
                                ageRepo.fetchAgeSignals(context)
                                isRefreshing = false
                            }
                        },
                        enabled = !isRefreshing
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status Card
            StatusBanner(ageState)

            // Details Section
            when (ageState) {
                is AgeSignalResult.Available -> {
                    val result = ageState as AgeSignalResult.Available
                    DetailCard(title = "Age Verification Details") {
                        DetailRow(
                            icon = Icons.Default.Info,
                            label = "Age Range",
                            value = formatAgeRange(result.ageLower, result.ageUpper)
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        DetailRow(
                            icon = Icons.Default.Lock,
                            label = "Verification Method",
                            value = formatUserStatus(result.userStatus)
                        )
                        if (result.userStatus == UserStatus.SUPERVISED ||
                            result.userStatus == UserStatus.SUPERVISED_APPROVAL_PENDING ||
                            result.userStatus == UserStatus.SUPERVISED_APPROVAL_DENIED
                        ) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            DetailRow(
                                icon = Icons.Default.Lock,
                                label = "Supervision Status",
                                value = when (result.userStatus) {
                                    UserStatus.SUPERVISED -> "Parent/guardian approved"
                                    UserStatus.SUPERVISED_APPROVAL_PENDING -> "Awaiting parent/guardian decision"
                                    else -> "Parent/guardian denied"
                                }
                            )
                        }
                    }
                }
                is AgeSignalResult.Unavailable -> {
                    DetailCard(title = "Status") {
                        Text(
                            "Age verification is not required in your region. " +
                                    "This feature is currently active only in U.S. states " +
                                    "with age verification laws (e.g., Texas).",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 22.sp
                            )
                        )
                    }
                }
                is AgeSignalResult.Error -> {
                    DetailCard(title = "Status") {
                        Text(
                            "Unable to retrieve age verification data. " +
                                    "This may be because the service is not available in your region " +
                                    "or there was a temporary issue. Tap the refresh button to try again.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 22.sp
                            )
                        )
                    }
                }
            }

            // Legal Notice
            DetailCard(title = "Data Usage Notice") {
                Text(
                    "Age verification data is provided by Google Play and is used " +
                            "solely to comply with applicable age verification laws. " +
                            "This data is not used for advertising, analytics, profiling, " +
                            "or any purpose other than providing age-appropriate experiences " +
                            "as required by law.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        lineHeight = 20.sp
                    )
                )
            }

            // About Section
            DetailCard(title = "About Age Verification") {
                Text(
                    "Some U.S. states (such as Texas under SB 2420) require app stores " +
                            "to verify users' ages. Google Play handles this verification process " +
                            "and shares age-range signals with app developers to help comply " +
                            "with these laws. If you're not in a regulated region, no age data " +
                            "is collected or used.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        lineHeight = 20.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusBanner(ageState: AgeSignalResult) {
    val isDark = isSystemInDarkTheme()
    val (icon, title, subtitle, containerColor) = when (ageState) {
        is AgeSignalResult.Available -> {
            if (ageState.userStatus == UserStatus.SUPERVISED_APPROVAL_DENIED) {
                StatusInfo(
                    Icons.Default.Block,
                    "Access Restricted",
                    "Parental controls are blocking this app",
                    if (isDark) Color(0xFF5A1A1A) else Color(0xFFFFEBEE)
                )
            } else {
                StatusInfo(
                    Icons.Default.CheckCircle,
                    "Verified",
                    "Age verification is active for your region",
                    if (isDark) Color(0xFF1A3A1A) else Color(0xFFE8F5E9)
                )
            }
        }
        is AgeSignalResult.Unavailable -> StatusInfo(
            Icons.Default.Info,
            "Not Applicable",
            "Age verification is not required in your region",
            if (isDark) Color(0xFF1A2A3A) else Color(0xFFE1F5FE)
        )
        is AgeSignalResult.Error -> StatusInfo(
            Icons.Default.Warning,
            "Unavailable",
            "Could not retrieve age verification status",
            if (isDark) Color(0xFF3A3A1A) else Color(0xFFFFF8E1)
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}

@Composable
private fun DetailCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}

private data class StatusInfo(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val containerColor: Color
)

private fun formatAgeRange(lower: Int, upper: Int): String {
    return when {
        lower == upper -> "$lower years old"
        upper >= 100 -> "$lower+"
        else -> "$lower – $upper years old"
    }
}

private fun formatUserStatus(status: UserStatus): String {
    return when (status) {
        UserStatus.VERIFIED -> "Verified by Google Play"
        UserStatus.DECLARED -> "Self-declared"
        UserStatus.SUPERVISED -> "Supervised (parent approved)"
        UserStatus.SUPERVISED_APPROVAL_PENDING -> "Supervised (approval pending)"
        UserStatus.SUPERVISED_APPROVAL_DENIED -> "Supervised (parent denied)"
        UserStatus.UNKNOWN -> "Unknown"
    }
}
