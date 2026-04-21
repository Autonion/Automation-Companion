package com.autonion.automationcompanion.features.flow_automation.model

import java.util.concurrent.ConcurrentHashMap

/**
 * Blackboard-pattern key-value store for runtime data passing between nodes.
 *
 * Nodes write results here (e.g., detected coordinates, OCR text) and downstream
 * nodes read from it to parameterize their behavior.
 *
 * Bug #13 fix: Backed by ConcurrentHashMap for thread safety, since the engine
 * runs on Dispatchers.Default and executors may access context concurrently.
 */
class FlowContext {

    private val store = ConcurrentHashMap<String, Any>()

    /** Put a value into the context under the given key. */
    fun put(key: String, value: Any) {
        store[key] = value
    }

    /** Get a typed value, or null if missing / wrong type. */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = store[key] as? T

    fun getString(key: String): String? = store[key] as? String

    fun getFloat(key: String): Float? = (store[key] as? Number)?.toFloat()

    fun getInt(key: String): Int? = (store[key] as? Number)?.toInt()

    fun getBoolean(key: String): Boolean? = store[key] as? Boolean

    fun contains(key: String): Boolean = store.containsKey(key)

    fun remove(key: String) { store.remove(key) }

    fun clear() = store.clear()

    /** Snapshot of all keys for debugging. */
    fun keys(): Set<String> = store.keys.toSet()

    override fun toString(): String = "FlowContext(${store.entries.joinToString { "${it.key}=${it.value}" }})"
}
