package com.autonion.automationcompanion.ui.components

import android.text.format.DateFormat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.omni_chatbot.data.db.OmniChatSessionEntity
import java.util.*

// ─── Shared color tokens (matches Omni-Chat theme) ─────────
private val SheetBg = Color(0xFF0D0F1A)
private val AccentPurple = Color(0xFF7C4DFF)
private val CardGlass = Color(0xFF1A1D2E).copy(alpha = 0.55f)

/**
 * Reusable chat history panel with slide-in animation support.
 * Used by Omni-Chat, Semantic Automation, and Cross-Device Automation.
 */
@Composable
fun ChatHistoryPanel(
    sessions: List<OmniChatSessionEntity>,
    onSessionClick: (OmniChatSessionEntity) -> Unit,
    onDeleteSession: (OmniChatSessionEntity) -> Unit,
    onClose: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val sheetBg = if (isDark) SheetBg else Color(0xFFF3F6FD)
    val textColor = if (isDark) Color.White else Color(0xFF1A1C1E)
    val secondaryTextColor = if (isDark) Color.White.copy(alpha = 0.4f) else Color(0xFF1A1C1E).copy(alpha = 0.5f)
    val iconCloseTint = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF1A1C1E).copy(alpha = 0.6f)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = sheetBg.copy(alpha = 0.97f)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    tint = AccentPurple,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Chat History",
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = iconCloseTint)
                }
            }

            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.ChatBubbleOutline,
                            contentDescription = null,
                            tint = textColor.copy(alpha = 0.2f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "No saved conversations",
                            color = secondaryTextColor,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(sessions, key = { it.sessionId }) { session ->
                        HistorySessionCard(
                            session = session,
                            onClick = { onSessionClick(session) },
                            onDelete = { onDeleteSession(session) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySessionCard(
    session: OmniChatSessionEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val cardGlass = if (isDark) CardGlass else Color.White.copy(alpha = 0.75f)
    val cardBorderColor = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.08f)
    val textColor = if (isDark) Color.White else Color(0xFF1A1C1E)
    val secondaryTextColor = if (isDark) Color.White.copy(alpha = 0.4f) else Color(0xFF1A1C1E).copy(alpha = 0.5f)
    val iconDeleteTint = if (isDark) Color.White.copy(alpha = 0.35f) else Color(0xFF1A1C1E).copy(alpha = 0.45f)

    val context = LocalContext.current
    val dateStr = DateFormat.getDateFormat(context).format(Date(session.timestamp))
    val timeStr = DateFormat.getTimeFormat(context).format(Date(session.timestamp))

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = cardGlass,
        border = BorderStroke(1.dp, cardBorderColor)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.previewText.take(60).ifBlank { "Untitled chat" },
                    color = textColor,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "$dateStr • $timeStr",
                    color = secondaryTextColor,
                    fontSize = 11.sp
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = iconDeleteTint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
