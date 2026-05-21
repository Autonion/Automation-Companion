package com.autonion.automationcompanion.ui.components
import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.ui.theme.DarkBannerBackground
import com.autonion.automationcompanion.ui.theme.LightBannerBackground
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.LocalIndication
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PrimaryButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        shape = RoundedCornerShape(25.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
fun SecondaryButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onBackground
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(25.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
fun DashboardHeader(
    title: String,
    subtitle: String? = null,
    onNotificationClick: (() -> Unit)? = null,
    onExclusionClick: () -> Unit = {},
    onBackupClick: () -> Unit = {}
) {
    var showSettingsMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp), // Increased top/bottom padding slightly
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
            }
        }
        
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Settings Gear with Dropdown
            Box {
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(targetValue = if (isPressed) 0.85f else 1f, label = "scale")

                Surface(
                    onClick = { showSettingsMenu = true },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .size(40.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    interactionSource = interactionSource,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = showSettingsMenu,
                    onDismissRequest = { showSettingsMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Excluded Apps") },
                        onClick = {
                            showSettingsMenu = false
                            onExclusionClick()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Security, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Backup & Restore") },
                        onClick = {
                            showSettingsMenu = false
                            onBackupClick()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.CloudUpload, contentDescription = null)
                        }
                    )
                }
            }

            if (onNotificationClick != null) {
                Surface(
                    onClick = onNotificationClick,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 2.dp,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "Notifications",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        // Red dot
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                                .size(8.dp)
                                .background(Color.Red, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HeroCard(
    title: String,
    description: String,
    icon: ImageVector,
    iconColor: Color,
    iconContainerColor: Color,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.95f else 1f, label = "scale")

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = if (isDark) BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)) else null,
        interactionSource = interactionSource
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(iconContainerColor, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = iconColor)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }
    }
}

@Composable
fun GridCard(
    title: String,
    description: String,
    icon: ImageVector,
    iconColor: Color,
    iconContainerColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    titleFontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    descFontSize: androidx.compose.ui.unit.TextUnit = 13.sp
) {
    val isDark = isSystemInDarkTheme()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.95f else 1f, label = "scale")

    Card(
        onClick = onClick,
        modifier = modifier
            .height(190.dp) // Fixed height ensures uniform rows in grid layouts
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = if (isDark) BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)) else null,
        interactionSource = interactionSource
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(iconContainerColor, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                title, 
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold, 
                    fontSize = titleFontSize 
                ), 
                maxLines = 2, 
                minLines = 2,
                lineHeight = if (titleFontSize > 16.sp) 24.sp else 20.sp,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant, 
                    fontSize = descFontSize,
                    lineHeight = if (descFontSize > 13.sp) 20.sp else 16.sp
                ),
                maxLines = 3, // Reduced max lines to ensure it fits comfortably within the fixed structure
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ListCard(
    title: String,
    description: String,
    icon: ImageVector,
    iconColor: Color,
    iconContainerColor: Color, // Gradient usually
    onClick: () -> Unit,
    titleFontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    descFontSize: androidx.compose.ui.unit.TextUnit = 14.sp
) {
    val isDark = isSystemInDarkTheme()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.95f else 1f, label = "scale")

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = if (isDark) BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)) else null,
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(iconContainerColor, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = titleFontSize))
                Text(description, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = descFontSize, lineHeight = if (descFontSize > 14.sp) 22.sp else 20.sp))
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
fun StatusCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    iconContainerColor: Color,
    backgroundColor: Color,
    titleColor: Color? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.95f else 1f, label = "scale")
    val resolvedTitleColor = titleColor ?: MaterialTheme.colorScheme.onSurface

    Card(
        onClick = onClick,
        modifier = modifier
            .height(110.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(20.dp),
        border = if (isSystemInDarkTheme()) BorderStroke(1.dp, iconContainerColor.copy(alpha = 0.3f)) else BorderStroke(1.dp, iconContainerColor.copy(alpha = 0.1f)),
        interactionSource = interactionSource
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(iconContainerColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = resolvedTitleColor,
                    fontSize = 13.sp
                ),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                ),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun BannerCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val bannerBg = if (isDark) DarkBannerBackground else LightBannerBackground
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.95f else 1f, label = "scale")

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        colors = CardDefaults.cardColors(containerColor = bannerBg),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Color.White))
                Text(description, style = MaterialTheme.typography.bodyMedium.copy(color = Color.White.copy(alpha = 0.7f)))
            }
        }
    }
}

@Composable
fun StaggeredEntry(
    index: Int,
    content: @Composable () -> Unit
) {
    val animAlpha = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val slide = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(50f) }
    val staggerMs = 50L
    val maxStaggerDelay = 200L
    val delay = (index * staggerMs).coerceAtMost(maxStaggerDelay)

    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delay)
        launch {
            animAlpha.animateTo(1f, androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
        launch {
            slide.animateTo(0f, androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = animAlpha.value
                this.translationY = slide.value
            }
    ) {
        content()
    }
}

@Composable
fun TabletBrandingPanel(
    onExclusionClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    var showSettingsMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(start = 32.dp, end = 24.dp, top = 48.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top section: Branding
        Column {
            Text(
                text = "Autonion",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "AI-Powered Automation",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                )
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Record gestures, build visual flows, and let AI agents automate tasks — all on-device with optional cloud power.",
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 24.sp
                )
            )
        }

        // Bottom section: Settings + Version
        Column {
            // Settings row
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showSettingsMenu = true }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 2.dp,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }

                DropdownMenu(
                    expanded = showSettingsMenu,
                    onDismissRequest = { showSettingsMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Excluded Apps") },
                        onClick = {
                            showSettingsMenu = false
                            onExclusionClick()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Security, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Backup & Restore") },
                        onClick = {
                            showSettingsMenu = false
                            onBackupClick()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.CloudUpload, contentDescription = null)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Version + GitHub + Privacy Policy
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "v1.0.6  ·  ",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                )
                Text(
                    text = "GitHub",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        textDecoration = TextDecoration.Underline
                    ),
                    modifier = Modifier.clickable { uriHandler.openUri("https://github.com/Autonion") }
                )
                Text(
                    text = "  ·  ",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                )
                Text(
                    text = "Privacy Policy",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        textDecoration = TextDecoration.Underline
                    ),
                    modifier = Modifier.clickable { uriHandler.openUri("https://autonion.github.io/autonion-policies/") }
                )
            }
        }
    }
}
