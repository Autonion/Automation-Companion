@file:OptIn(ExperimentalMaterial3Api::class)
package com.autonion.automationcompanion.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import com.autonion.automationcompanion.BuildConfig
import com.autonion.automationcompanion.ui.components.*

private val ScreenBgDark = Color(0xFF0F111E)
private val CardBgDark = Color(0xFF16192E)
private val CardBgLight = Color.White
private val AccentPurple = Color(0xFF7C4DFF)
private val AccentBlue = Color(0xFF2979FF)

/**
 * Dedicated "What's New" screen accessible from Settings.
 * Unifies the release highlights and video overview into a single top hero banner,
 * followed by distinct cards for all feature updates.
 */
@Composable
fun WhatsNewScreen(
    versionName: String = BuildConfig.VERSION_NAME,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val uriHandler = LocalUriHandler.current
    val release = remember(versionName) {
        WhatsNewRepository.getCurrentRelease(versionName)
    }

    val cardBg = if (isDark) CardBgDark else CardBgLight
    val textColor = if (isDark) Color.White else Color(0xFF181A2A)
    val subtextColor = if (isDark) Color(0xFFA5A9C2) else Color(0xFF5E6278)
    val borderColor = if (isDark) Color(0xFF252A4A) else Color(0xFFE4E7F2)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "What's New",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isDark) ScreenBgDark else MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = if (isDark) ScreenBgDark else MaterialTheme.colorScheme.surfaceContainerLowest
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Unified Hero Release Banner (Highlights + Integrated Video Action) ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = if (isDark) Color(0xFF161A34) else Color(0xFFF3F1FD),
                border = BorderStroke(
                    1.2.dp,
                    Brush.horizontalGradient(
                        listOf(
                            AccentPurple.copy(alpha = if (isDark) 0.6f else 0.4f),
                            AccentBlue.copy(alpha = if (isDark) 0.45f else 0.3f)
                        )
                    )
                ),
                shadowElevation = if (isDark) 0.dp else 2.dp
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Badge + Date Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AccentPurple.copy(alpha = if (isDark) 0.25f else 0.15f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = AccentPurple,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "VERSION ${release.versionName}",
                                    color = AccentPurple,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                            }
                        }

                        Text(
                            text = release.releaseDate,
                            color = subtextColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = release.headline,
                        color = textColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 24.sp
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = release.description,
                        color = subtextColor,
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp
                    )

                    // Integrated Video Action Button inside the Hero Banner
                    if (release.youtubeUrl.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                try {
                                    uriHandler.openUri(release.youtubeUrl)
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentPurple
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Watch Walkthrough on YouTube",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // ── Section Title: Detailed Features ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Layers,
                    contentDescription = null,
                    tint = AccentPurple,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "What's Included (${release.features.size} Updates)",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                )
            }

            // ── Features List ──
            release.features.forEach { item ->
                FeatureDetailCard(
                    item = item,
                    cardBg = cardBg,
                    textColor = textColor,
                    subtextColor = subtextColor,
                    borderColor = borderColor,
                    isDark = isDark,
                    onOpenRoute = { route -> onNavigate(route) }
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FeatureDetailCard(
    item: WhatsNewItem,
    cardBg: Color,
    textColor: Color,
    subtextColor: Color,
    borderColor: Color,
    isDark: Boolean,
    onOpenRoute: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = if (isDark) 0.dp else 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(item.iconTint.copy(alpha = if (isDark) 0.2f else 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        tint = item.iconTint,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.category.uppercase(),
                            color = item.iconTint,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp
                        )
                        if (item.tag != null) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = item.iconTint.copy(alpha = if (isDark) 0.25f else 0.12f)
                            ) {
                                Text(
                                    text = item.tag,
                                    color = item.iconTint,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(2.dp))

                    Text(
                        text = item.title,
                        color = textColor,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 19.sp
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = item.description,
                color = subtextColor,
                fontSize = 12.5.sp,
                lineHeight = 17.5.sp
            )

            if (item.route != null) {
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = { onOpenRoute(item.route) },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = item.iconTint.copy(alpha = if (isDark) 0.16f else 0.1f),
                        contentColor = item.iconTint
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(
                        text = "Open Feature",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
