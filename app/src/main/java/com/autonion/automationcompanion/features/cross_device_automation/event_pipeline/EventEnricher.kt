package com.autonion.automationcompanion.features.cross_device_automation.event_pipeline

import com.autonion.automationcompanion.features.cross_device_automation.domain.EnrichedEvent
import com.autonion.automationcompanion.features.cross_device_automation.domain.RawEvent
import java.net.URI

class EventEnricher {

    fun enrich(event: RawEvent): EnrichedEvent {
        val metadata = mutableMapOf<String, String>()
        
        // Enrich based on event type
        when (event.type) {
            "browser.url_changed", "browser.domain_detected" -> {
                val url = event.payload["url"]
                if (url != null) {
                    try {
                        val domain = URI(url).host
                        if (domain != null) {
                            metadata["domain"] = domain.removePrefix("www.")
                        }
                    } catch (e: Exception) {
                        // ignore invalid URLs
                    }
                }
            }
            "clipboard.text_copied" -> {
                val text = event.payload["text"]
                 if (text != null && text.startsWith("http")) {
                      try {
                        val domain = URI(text).host
                        if (domain != null) {
                            metadata["contains_url"] = "true"
                            metadata["domain"] = domain.removePrefix("www.")
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                 }
            }
        }

        return EnrichedEvent(
            originalEvent = event,
            detectedTags = emptySet(), // Tagging happens next
            metadata = metadata
        )
    }
}
