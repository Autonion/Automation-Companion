package com.autonion.automationcompanion.core.backup

import kotlinx.serialization.json.*

/** Visits persisted capture assets (images and metadata), including JSON embedded in Flow nodes. */
internal object BackupImagePaths {
    private val imageKeys = setOf("captureImagePath", "captureMetadataPath", "templateImagePath", "templatePath", "sourceCapturePath")
    private val embeddedKeys = setOf("visionPresetJson", "automationStepsJson")
    private val json = Json { ignoreUnknownKeys = true }

    fun collect(text: String): Set<String> = buildSet {
        rewrite(text) { path -> add(path); path }
    }

    fun rewrite(text: String, replace: (String) -> String?): String {
        fun visit(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { (key, child) ->
                when {
                    key in imageKeys && child is JsonPrimitive && child.isString && child.content.isNotBlank() ->
                        replace(child.content)?.let(::JsonPrimitive) ?: JsonNull
                    key in embeddedKeys && child is JsonPrimitive && child.isString && child.content.isNotBlank() ->
                        JsonPrimitive(visit(json.parseToJsonElement(child.content)).toString())
                    else -> visit(child)
                }
            })
            is JsonArray -> JsonArray(value.map(::visit))
            else -> value
        }
        return visit(json.parseToJsonElement(text)).toString()
    }
}
