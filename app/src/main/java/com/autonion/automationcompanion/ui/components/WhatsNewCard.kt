@file:OptIn(ExperimentalMaterial3Api::class)
package com.autonion.automationcompanion.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.ui.AutomationRoutes

private val AccentPurple = Color(0xFF7C4DFF)
private val AccentBlue = Color(0xFF2979FF)
private val CardBgDark = Color(0xFF14172B)
private val CardBgLight = Color(0xFFF7F5FD)

/**
 * A modern, polished "What's New" card displaying latest features and upgrades.
 * Clean, compact layout that looks stunning in both light and dark themes.
 */
@Composable
fun WhatsNewCard(
    versionName: String = "1.1.0",
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) CardBgDark else CardBgLight
    val textColor = if (isDark) Color.White else Color(0xFF1B1B2F)
    val subtextColor = if (isDark) Color(0xFFB0B4CE) else Color(0xFF555770)
    val rowBg = if (isDark) Color(0xFF1D213B) else Color.White
    val uriHandler = LocalUriHandler.current

    var isExpanded by remember { mutableStateOf(false) }

    val release = remember(versionName) {
        WhatsNewRepository.getCurrentRelease(versionName)
    }
    val features = release.features

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        color = cardBg,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            1.2.dp,
            Brush.horizontalGradient(
                listOf(
                    AccentPurple.copy(alpha = if (isDark) 0.5f else 0.4f),
                    AccentBlue.copy(alpha = if (isDark) 0.4f else 0.35f)
                )
            )
        ),
        shadowElevation = if (isDark) 0.dp else 2.dp,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header: Sparkle + Title + Version Tag + Video Icon + Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(AccentPurple.copy(alpha = if (isDark) 0.2f else 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = AccentPurple,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "What's New",
                            color = textColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AccentPurple.copy(alpha = if (isDark) 0.25f else 0.12f)
                        ) {
                            Text(
                                text = "v${release.versionName}",
                                color = AccentPurple,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "Latest upgrades & features",
                        color = subtextColor,
                        fontSize = 12.sp
                    )
                }

                // Video Icon beside Close Icon
                if (release.youtubeUrl.isNotBlank()) {
                    IconButton(
                        onClick = {
                            try {
                                uriHandler.openUri(release.youtubeUrl)
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.PlayCircle,
                            contentDescription = "Watch Video",
                            tint = AccentPurple,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = subtextColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Items List
            val displayedFeatures = if (isExpanded) features else features.take(2)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                displayedFeatures.forEach { item ->
                    WhatsNewRow(
                        item = item,
                        isDark = isDark,
                        rowBg = rowBg,
                        textColor = textColor,
                        subtextColor = subtextColor,
                        onClick = {
                            item.route?.let { onNavigate(it) }
                        }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Footer Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { isExpanded = !isExpanded },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isExpanded) "Show Less" else "View All (${features.size})",
                        color = AccentPurple,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = AccentPurple,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentPurple
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(
                        "Got It",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun WhatsNewRow(
    item: WhatsNewItem,
    isDark: Boolean,
    rowBg: Color,
    textColor: Color,
    subtextColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(rowBg)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(item.iconTint.copy(alpha = if (isDark) 0.18f else 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                item.icon,
                contentDescription = null,
                tint = item.iconTint,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            // Category Tag
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.category.uppercase(),
                    color = item.iconTint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                if (item.tag != null) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = item.iconTint.copy(alpha = if (isDark) 0.22f else 0.12f)
                    ) {
                        Text(
                            text = item.tag,
                            color = item.iconTint,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(1.dp))

            // Title
            Text(
                text = item.title,
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 17.sp
            )

            Spacer(Modifier.height(2.dp))

            // Description
            Text(
                text = item.description,
                color = subtextColor,
                fontSize = 11.5.sp,
                lineHeight = 15.sp
            )
        }

        if (item.route != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Open",
                tint = subtextColor.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
