package com.autonion.automationcompanion.features.omni_chatbot.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.autonion.automationcompanion.features.omni_chatbot.model.ResponseMode

@Entity(
    tableName = "omni_chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = OmniChatSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class OmniChatMessageEntity(
    @PrimaryKey
    val messageId: String,
    val sessionId: String,
    val text: String,
    val isUser: Boolean,
    val mode: String, // name of ResponseMode enum
    val timestamp: Long,
    val actionWidgetJson: String?, // serialized ActionWidget or null
    val suggestedWalkthroughId: String?
)
