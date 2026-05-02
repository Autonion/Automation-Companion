package com.autonion.automationcompanion.features.automation_debugger

import android.content.Context
import com.autonion.automationcompanion.features.automation_debugger.data.ExecutionLog
import com.autonion.automationcompanion.features.automation_debugger.data.LogLevel
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Centralized logger that any feature can call to persist execution logs.
 * Writes to Room on IO dispatcher — fire-and-forget from the caller's perspective.
 * Also runs auto-cleanup on each write (removes logs older than 7 days).
 */
object DebugLogger {

    private const val RETENTION_DAYS = 7L
    private val scope = CoroutineScope(Dispatchers.IO)

    fun log(
        context: Context,
        category: String,
        level: String,
        title: String,
        message: String,
        source: String,
        metadata: String? = null
    ) {
        scope.launch {
            try {
                val dao = AppDatabase.get(context).executionLogDao()
                dao.insert(
                    ExecutionLog(
                        category = category,
                        level = level,
                        title = title,
                        message = message,
                        source = source,
                        metadata = metadata
                    )
                )
                // Auto-cleanup: remove entries older than retention period
                val cutoff = System.currentTimeMillis() - (RETENTION_DAYS * 24 * 60 * 60 * 1000)
                dao.deleteOlderThan(cutoff)
            } catch (_: Exception) {
                // Swallow — logging must never crash the app
            }
        }
    }

    fun info(context: Context, category: String, title: String, message: String, source: String, metadata: String? = null) {
        log(context, category, LogLevel.INFO, title, message, source, metadata)
    }

    fun success(context: Context, category: String, title: String, message: String, source: String, metadata: String? = null) {
        log(context, category, LogLevel.SUCCESS, title, message, source, metadata)
    }

    fun warning(context: Context, category: String, title: String, message: String, source: String, metadata: String? = null) {
        log(context, category, LogLevel.WARNING, title, message, source, metadata)
    }

    fun error(context: Context, category: String, title: String, message: String, source: String, metadata: String? = null) {
        log(context, category, LogLevel.ERROR, title, message, source, metadata)
    }
}
