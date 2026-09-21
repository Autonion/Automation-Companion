package com.autonion.automationcompanion.features.screen_understanding_ml.logic

import android.graphics.BitmapFactory
import android.util.AtomicFile
import com.autonion.automationcompanion.features.screen_understanding_ml.model.*
import kotlinx.serialization.json.Json
import java.io.File

/** One metadata asset per image; steps share its path, including in exported Flow nodes. */
object CaptureMetadataStore {
    const val EXTRA_METADATA_PATH = "CAPTURE_METADATA_PATH"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun saveForImage(image: File, elements: List<UIElement>, text: List<CapturedTextNode>, accessibilityOnly: Boolean): String {
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.absolutePath, size)
        require(size.outWidth > 0 && size.outHeight > 0) { "Cannot read capture dimensions" }
        return write(image, CaptureMetadata(width = size.outWidth, height = size.outHeight,
            accessibilityElements = elements, textNodes = text, accessibilityOnly = accessibilityOnly))
    }

    fun write(image: File, metadata: CaptureMetadata): String {
        val destination = File(image.parentFile, "${image.nameWithoutExtension}.capture.json")
        val atomic = AtomicFile(destination)
        val output = atomic.startWrite()
        try {
            output.write(json.encodeToString(metadata).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
        return destination.absolutePath
    }

    /** Missing, corrupt, unsupported or mismatched metadata must not prevent legacy editing. */
    fun read(path: String?, width: Int, height: Int): CaptureMetadata? = runCatching {
        val file = path?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        if (!file.isFile || file.length() > 16L * 1024 * 1024) return null
        json.decodeFromString<CaptureMetadata>(file.readText()).takeIf {
            it.version == 1 && it.captureId.isNotBlank() && it.width == width && it.height == height &&
                width > 0 && height > 0
        }
    }.getOrNull()
}
