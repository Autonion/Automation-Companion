package com.autonion.automationcompanion.features.automation_debugger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExecutionLogDao {

    @Insert
    suspend fun insert(log: ExecutionLog)

    /** All logs for a category, newest first */
    @Query("SELECT * FROM execution_logs WHERE category = :category ORDER BY timestamp DESC")
    fun getByCategory(category: String): Flow<List<ExecutionLog>>

    /** All logs for a category filtered by level, newest first */
    @Query("SELECT * FROM execution_logs WHERE category = :category AND level = :level ORDER BY timestamp DESC")
    fun getByCategoryAndLevel(category: String, level: String): Flow<List<ExecutionLog>>

    /** Count of logs per category */
    @Query("SELECT COUNT(*) FROM execution_logs WHERE category = :category")
    fun getCountByCategory(category: String): Flow<Int>

    /** Most recent log for a category */
    @Query("SELECT * FROM execution_logs WHERE category = :category ORDER BY timestamp DESC LIMIT 1")
    fun getLatestByCategory(category: String): Flow<ExecutionLog?>

    /** Total log count */
    @Query("SELECT COUNT(*) FROM execution_logs")
    fun getTotalCount(): Flow<Int>

    /** Delete all logs for a specific category */
    @Query("DELETE FROM execution_logs WHERE category = :category")
    suspend fun deleteByCategory(category: String)

    /** Delete all logs */
    @Query("DELETE FROM execution_logs")
    suspend fun deleteAll()

    /** Delete logs older than the given timestamp (auto-cleanup) */
    @Query("DELETE FROM execution_logs WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long)
}
