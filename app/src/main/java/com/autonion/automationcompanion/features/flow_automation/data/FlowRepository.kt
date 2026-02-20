package com.autonion.automationcompanion.features.flow_automation.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.autonion.automationcompanion.features.flow_automation.model.FlowGraph
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "FlowRepository"

/**
 * File-based persistence for flow graphs.
 * Flows are stored as individual JSON files under `flows/` in internal storage.
 *
 * Also supports import/export to external URIs for sharing.
 */
class FlowRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val flowsDir: File
        get() = File(context.filesDir, "flows").also { it.mkdirs() }

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
     * Export a flow graph as JSON to a content URI (e.g. from SAF picker).
     * Returns true if successful.
     */
    fun exportToUri(flowId: String, uri: Uri): Boolean {
        val graph = load(flowId) ?: return false
        return try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.encodeToString(graph).toByteArray())
            }
            Log.d(TAG, "Exported flow '${graph.name}' to $uri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            false
        }
    }

    /**
     * Import a flow graph from a content URI.
     * The imported flow is assigned a new ID to avoid collisions.
     * Returns the imported graph, or null on failure.
     */
    fun importFromUri(uri: Uri): FlowGraph? {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                ?: return null
            val imported = json.decodeFromString<FlowGraph>(text)
            // Assign a new ID and mark as updated
            val newGraph = imported.copy(
                id = java.util.UUID.randomUUID().toString(),
                name = "${imported.name} (imported)",
                updatedAt = System.currentTimeMillis()
            )
            save(newGraph)
            Log.d(TAG, "Imported flow '${newGraph.name}' from $uri → id=${newGraph.id}")
            newGraph
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            null
        }
    }
}
