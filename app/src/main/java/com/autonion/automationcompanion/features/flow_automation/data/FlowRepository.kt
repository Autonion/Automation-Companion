package com.autonion.automationcompanion.features.flow_automation.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.autonion.automationcompanion.features.flow_automation.model.FlowGraph
import com.autonion.automationcompanion.features.flow_automation.model.FlowNode
import com.autonion.automationcompanion.features.flow_automation.model.ScreenMLNode
import com.autonion.automationcompanion.features.flow_automation.model.VisualTriggerNode
import com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset
import com.autonion.automationcompanion.features.visual_trigger.models.VisionRegion
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "FlowRepository"
private const val FLOW_JSON_ENTRY = "flow.json"
private const val ASSETS_DIR_PREFIX = "assets/"

/**
 * File-based persistence for flow graphs.
 * Flows are stored as individual JSON files under `flows/` in internal storage.
 *
 * Supports import/export as `.zip` archives that bundle the JSON metadata
 * together with all referenced image assets (template images, capture images,
 * vision region templates). This ensures flows remain functional when shared
 * across devices.
 *
 * Legacy plain-JSON imports are also supported for backward compatibility.
 */
class FlowRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val flowsDir: File
        get() = File(context.filesDir, "flows").also { it.mkdirs() }

    /** Directory for extracted flow image assets. */
    private val flowAssetsDir: File
        get() = File(context.filesDir, "flow_assets").also { it.mkdirs() }

    /** Save or update a flow graph. */
    fun save(graph: FlowGraph) {
        val file = File(flowsDir, "${graph.id}.json")
        file.writeText(json.encodeToString(graph))
    }

    /** Load a flow graph by ID. Returns null if not found or corrupt. */
    fun load(id: String): FlowGraph? {
        val file = File(flowsDir, "$id.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString<FlowGraph>(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** List all saved flow graphs (metadata only — full deserialize). */
    fun listAll(): List<FlowGraph> {
        return flowsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString<FlowGraph>(file.readText())
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    /** Delete a flow graph by ID. Returns true if deleted. */
    fun delete(id: String): Boolean {
        val file = File(flowsDir, "$id.json")
        return file.delete()
    }

    /** Check if a flow exists. */
    fun exists(id: String): Boolean = File(flowsDir, "$id.json").exists()

    // ─── Import / Export ──────────────────────────────────────────────────

    /**
     * Export a flow graph as a `.zip` archive to a content URI (e.g. from SAF picker).
     *
     * The ZIP contains:
     * - `flow.json` — the serialized [FlowGraph]
     * - `assets/<filename>` — all image files referenced by [VisualTriggerNode]
     *   and [ScreenMLNode] nodes (template images, capture images, vision region
     *   templates).
     *
     * Image paths inside the JSON are rewritten to use relative `assets/` prefixes
     * so they can be correctly resolved on import.
     *
     * Returns true if successful.
     */
    fun exportToUri(flowId: String, uri: Uri): Boolean {
        val graph = load(flowId) ?: return false
        return try {
            // 1. Collect all image file paths from the graph
            val imagePaths = collectImagePaths(graph)
            Log.d(TAG, "Export: found ${imagePaths.size} image assets to bundle")

            // 2. Build a map of absolute path → relative archive name
            //    e.g. "/data/.../viz_capture_abc.png" → "assets/viz_capture_abc.png"
            val pathToArchiveName = mutableMapOf<String, String>()
            for (path in imagePaths) {
                val file = File(path)
                if (file.exists()) {
                    val archiveName = "$ASSETS_DIR_PREFIX${file.name}"
                    pathToArchiveName[path] = archiveName
                } else {
                    Log.w(TAG, "Export: image file missing, skipping: $path")
                }
            }

            // 3. Rewrite the graph so all image paths use relative archive names
            val exportGraph = remapImagePaths(graph, pathToArchiveName)

            // 4. Write the ZIP archive
            context.contentResolver.openOutputStream(uri)?.use { outStream ->
                ZipOutputStream(outStream).use { zip ->
                    // Write flow.json
                    zip.putNextEntry(ZipEntry(FLOW_JSON_ENTRY))
                    zip.write(json.encodeToString(exportGraph).toByteArray())
                    zip.closeEntry()

                    // Write each image asset
                    for ((absolutePath, archiveName) in pathToArchiveName) {
                        val file = File(absolutePath)
                        if (file.exists()) {
                            zip.putNextEntry(ZipEntry(archiveName))
                            FileInputStream(file).use { fis ->
                                fis.copyTo(zip)
                            }
                            zip.closeEntry()
                            Log.d(TAG, "Export: packed $archiveName (${file.length()} bytes)")
                        }
                    }
                }
            }

            Log.d(TAG, "Exported flow '${graph.name}' to $uri (ZIP with ${pathToArchiveName.size} assets)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            false
        }
    }

    /**
     * Import a flow graph from a content URI.
     *
     * Supports two formats:
     * 1. **ZIP archive** (new) — Contains `flow.json` + `assets/` images.
     *    Images are extracted to [flowAssetsDir] and paths are rewritten.
     * 2. **Plain JSON** (legacy) — Read directly as a [FlowGraph].
     *    Image paths will remain as-is (likely broken if from another device).
     *
     * The imported flow is assigned a new ID to avoid collisions.
     * Returns the imported graph, or null on failure.
     */
    fun importFromUri(uri: Uri): FlowGraph? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val buffered = BufferedInputStream(inputStream)

            // Peek at the first bytes to detect ZIP magic number (PK\x03\x04)
            buffered.mark(4)
            val header = ByteArray(4)
            val bytesRead = buffered.read(header)
            buffered.reset()

            if (bytesRead >= 4 && isZipMagic(header)) {
                importFromZip(buffered)
            } else {
                // Legacy: plain JSON
                val text = buffered.bufferedReader().readText()
                buffered.close()
                importFromJson(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            null
        }
    }

    // ─── ZIP Import ───────────────────────────────────────────────────────

    private fun importFromZip(inputStream: BufferedInputStream): FlowGraph? {
        var flowJsonText: String? = null
        val extractedAssets = mutableMapOf<String, String>() // archiveName → local absolute path

        // Create a unique subdirectory for this import to avoid filename collisions
        val importId = UUID.randomUUID().toString().take(8)
        val importDir = File(flowAssetsDir, importId).also { it.mkdirs() }

        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == FLOW_JSON_ENTRY -> {
                        flowJsonText = zip.bufferedReader().readText()
                    }
                    entry.name.startsWith(ASSETS_DIR_PREFIX) && !entry.isDirectory -> {
                        val fileName = entry.name.removePrefix(ASSETS_DIR_PREFIX)
                        val outFile = File(importDir, fileName)
                        outFile.outputStream().use { out ->
                            zip.copyTo(out)
                        }
                        extractedAssets[entry.name] = outFile.absolutePath
                        Log.d(TAG, "Import: extracted ${entry.name} → ${outFile.absolutePath}")
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (flowJsonText == null) {
            Log.e(TAG, "Import: ZIP does not contain $FLOW_JSON_ENTRY")
            return null
        }

        val imported = json.decodeFromString<FlowGraph>(flowJsonText!!)

        // Remap the relative archive paths back to the extracted local paths
        val remapped = remapImagePaths(imported, extractedAssets)

        // Regenerate IDs to avoid collisions (same logic as before)
        return finalizeImport(remapped)
    }

    // ─── Legacy JSON Import ──────────────────────────────────────────────

    private fun importFromJson(text: String): FlowGraph? {
        val imported = json.decodeFromString<FlowGraph>(text)
        return finalizeImport(imported)
    }

    // ─── Shared import finalization ──────────────────────────────────────

    /**
     * Assigns new IDs to the imported graph's nodes, edges, and the graph
     * itself, then saves and returns it.
     */
    private fun finalizeImport(imported: FlowGraph): FlowGraph {
        // Bug #6 fix: Regenerate ALL IDs (graph, nodes, edges) to prevent
        // collision when the same flow is imported twice.
        val nodeIdMap = mutableMapOf<String, String>() // old ID → new ID
        imported.nodes.forEach { node ->
            nodeIdMap[node.id] = UUID.randomUUID().toString()
        }

        val edgeIdMap = mutableMapOf<String, String>() // old edge ID → new edge ID
        imported.edges.forEach { edge ->
            edgeIdMap[edge.id] = UUID.randomUUID().toString()
        }

        // Remap nodes with new IDs and updated onFailureEdgeId references
        val remappedNodes = imported.nodes.map { node ->
            val newId = nodeIdMap[node.id]!!
            val newFailureEdgeId = node.onFailureEdgeId?.let { edgeIdMap[it] }
            remapNode(node, newId, newFailureEdgeId)
        }

        // Remap edges with new IDs and updated from/to node references
        val remappedEdges = imported.edges.map { edge ->
            edge.copy(
                id = edgeIdMap[edge.id]!!,
                fromNodeId = nodeIdMap[edge.fromNodeId] ?: edge.fromNodeId,
                toNodeId = nodeIdMap[edge.toNodeId] ?: edge.toNodeId
            )
        }

        val newGraph = imported.copy(
            id = UUID.randomUUID().toString(),
            name = "${imported.name} (imported)",
            nodes = remappedNodes,
            edges = remappedEdges,
            updatedAt = System.currentTimeMillis()
        )
        save(newGraph)
        Log.d(TAG, "Imported flow '${newGraph.name}' → id=${newGraph.id}")
        return newGraph
    }

    // ─── Image path helpers ──────────────────────────────────────────────

    /**
     * Collects all unique file paths referenced by image-dependent nodes
     * in the flow graph: [VisualTriggerNode.templateImagePath],
     * [ScreenMLNode.captureImagePath], and template paths inside
     * [VisualTriggerNode.visionPresetJson] (VisionPreset regions).
     */
    private fun collectImagePaths(graph: FlowGraph): Set<String> {
        val paths = mutableSetOf<String>()

        for (node in graph.nodes) {
            when (node) {
                is VisualTriggerNode -> {
                    // Simple template image
                    if (node.templateImagePath.isNotBlank()) {
                        paths.add(node.templateImagePath)
                    }
                    // Vision preset embedded JSON — contains captureImagePath + region templatePaths
                    if (node.visionPresetJson.isNotBlank()) {
                        try {
                            val preset = json.decodeFromString<VisionPreset>(node.visionPresetJson)
                            preset.captureImagePath?.let { if (it.isNotBlank()) paths.add(it) }
                            for (region in preset.regions) {
                                if (region.templatePath.isNotBlank()) {
                                    paths.add(region.templatePath)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse visionPresetJson for image paths", e)
                        }
                    }
                }
                is ScreenMLNode -> {
                    if (node.captureImagePath.isNotBlank()) {
                        paths.add(node.captureImagePath)
                    }
                }
                else -> { /* No image paths for other node types */ }
            }
        }

        return paths
    }

    /**
     * Returns a copy of [graph] with all image paths replaced according to
     * [pathMap]. Works on:
     * - [VisualTriggerNode.templateImagePath]
     * - [VisualTriggerNode.visionPresetJson] (internal captureImagePath + region templatePaths)
     * - [ScreenMLNode.captureImagePath]
     */
    private fun remapImagePaths(graph: FlowGraph, pathMap: Map<String, String>): FlowGraph {
        if (pathMap.isEmpty()) return graph

        val remappedNodes = graph.nodes.map { node ->
            when (node) {
                is VisualTriggerNode -> {
                    var newTemplatePath = pathMap[node.templateImagePath] ?: node.templateImagePath
                    var newPresetJson = node.visionPresetJson

                    // Remap paths inside visionPresetJson
                    if (node.visionPresetJson.isNotBlank()) {
                        try {
                            val preset = json.decodeFromString<VisionPreset>(node.visionPresetJson)
                            val newCapturePath = preset.captureImagePath?.let { pathMap[it] ?: it }
                            val newRegions = preset.regions.map { region ->
                                val newRegionPath = pathMap[region.templatePath] ?: region.templatePath
                                region.copy(templatePath = newRegionPath)
                            }
                            val newPreset = preset.copy(
                                captureImagePath = newCapturePath,
                                regions = newRegions
                            )
                            newPresetJson = json.encodeToString(newPreset)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to remap visionPresetJson paths", e)
                        }
                    }

                    node.copy(
                        templateImagePath = newTemplatePath,
                        visionPresetJson = newPresetJson
                    )
                }
                is ScreenMLNode -> {
                    val newCapturePath = pathMap[node.captureImagePath] ?: node.captureImagePath
                    node.copy(captureImagePath = newCapturePath)
                }
                else -> node
            }
        }

        return graph.copy(nodes = remappedNodes)
    }

    // ─── Utility ─────────────────────────────────────────────────────────

    /** Create a copy of a node with a new ID and optionally updated onFailureEdgeId. */
    private fun remapNode(node: FlowNode, newId: String, newFailureEdgeId: String?): FlowNode {
        return when (node) {
            is com.autonion.automationcompanion.features.flow_automation.model.StartNode -> node.copy(id = newId, onFailureEdgeId = newFailureEdgeId)
            is com.autonion.automationcompanion.features.flow_automation.model.GestureNode -> node.copy(id = newId, onFailureEdgeId = newFailureEdgeId)
            is VisualTriggerNode -> node.copy(id = newId, onFailureEdgeId = newFailureEdgeId)
            is ScreenMLNode -> node.copy(id = newId, onFailureEdgeId = newFailureEdgeId)
            is com.autonion.automationcompanion.features.flow_automation.model.DelayNode -> node.copy(id = newId, onFailureEdgeId = newFailureEdgeId)
            is com.autonion.automationcompanion.features.flow_automation.model.LaunchAppNode -> node.copy(id = newId, onFailureEdgeId = newFailureEdgeId)
            is com.autonion.automationcompanion.features.flow_automation.model.RepeatNode -> node.copy(id = newId, onFailureEdgeId = newFailureEdgeId)
        }
    }

    /** Check if the first 4 bytes match the ZIP local file header magic number. */
    private fun isZipMagic(header: ByteArray): Boolean {
        return header[0] == 0x50.toByte() && // P
               header[1] == 0x4B.toByte() && // K
               header[2] == 0x03.toByte() &&
               header[3] == 0x04.toByte()
    }
}
