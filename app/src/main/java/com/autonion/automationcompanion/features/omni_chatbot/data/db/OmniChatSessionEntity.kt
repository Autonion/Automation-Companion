package com.autonion.automationcompanion.features.omni_chatbot.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "omni_chat_sessions")
data class OmniChatSessionEntity(
    @PrimaryKey
    val sessionId: String,
    val title: String,
    val timestamp: Long,
    val previewText: String
)
