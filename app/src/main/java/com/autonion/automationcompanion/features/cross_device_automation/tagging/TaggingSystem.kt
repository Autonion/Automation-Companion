package com.autonion.automationcompanion.features.cross_device_automation.tagging

import com.autonion.automationcompanion.features.cross_device_automation.domain.EnrichedEvent

class TaggingSystem {
    // Semi-supervised deterministic system.
    // Priority: User rules > Built-in patterns > Heuristics.
    // For now, hardcoded built-in patterns.

    private val knownPatterns = mapOf(
        // Meeting
        "meet.google.com" to "meeting",
        "zoom.us" to "meeting",
        "teams.microsoft.com" to "meeting",
        "webex.com" to "meeting",
        
        // Video
        "youtube.com" to "video",
        "netflix.com" to "video",
        "twitch.tv" to "video",
        "vimeo.com" to "video",
        
        // Coding
        "github.com" to "coding",
        "gitlab.com" to "coding",
        "stackoverflow.com" to "coding",
        "jira.com" to "coding",
        
        // Works
        "linkedin.com" to "work",
        "slack.com" to "work",
        "notion.so" to "work",
        
        // Social
        "facebook.com" to "social",
        "instagram.com" to "social",
        "twitter.com" to "social",
        "x.com" to "social",
        "tiktok.com" to "social",
        "reddit.com" to "social"
    )

    fun tag(event: EnrichedEvent): EnrichedEvent {
        val tags = mutableSetOf<String>()
        val metadata = event.metadata

        // 1. Check Domain
        val domain = metadata["domain"]
        if (domain != null) {
            knownPatterns.entries.forEach { (key, tag) ->
                 if (domain.contains(key)) {
                     tags.add(tag)
                 }
            }
        }

        // 2. Check Payload content
        event.originalEvent.payload.forEach { (_, value) ->
            if (value.contains("meeting", ignoreCase = true)) tags.add("meeting")
            if (value.contains("video", ignoreCase = true)) tags.add("video")
        }

        return event.copy(detectedTags = tags)
    }
}
