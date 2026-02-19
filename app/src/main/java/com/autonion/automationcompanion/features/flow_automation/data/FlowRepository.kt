package com.autonion.automationcompanion.features.flow_automation.data

import android.content.Context
import com.autonion.automationcompanion.features.flow_automation.model.FlowGraph
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-based persistence for flow graphs.
 * Flows are stored as individual JSON files under `flows/` in internal storage.
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
}
