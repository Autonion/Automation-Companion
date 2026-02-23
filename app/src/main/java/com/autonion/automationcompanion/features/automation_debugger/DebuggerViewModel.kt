package com.autonion.automationcompanion.features.automation_debugger

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonion.automationcompanion.features.automation_debugger.data.ExecutionLog
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Summary data for a single category card on the main debugger screen */
data class CategorySummary(
    val category: String,
    val displayName: String,
    val description: String,
    val icon: ImageVector,
    val accentColor: Color,
    val logCount: Int = 0,
    val latestLog: ExecutionLog? = null
)

/** All 7 feature categories with their visual identity matching the HomeScreen */
val ALL_CATEGORIES = listOf(
    CategorySummary(
        category = LogCategory.GESTURE_RECORDING,
        displayName = "Gesture Recording",
        description = "Record & replay macros",
        icon = Icons.Default.TouchApp,
        accentColor = AccentPurple
    ),
    CategorySummary(
        category = LogCategory.SCREEN_CONTEXT_AI,
        displayName = "Screen Context AI",
        description = "UI detection & OCR",
        icon = Icons.AutoMirrored.Filled.ViewQuilt,
        accentColor = AccentBlue
    ),
    CategorySummary(
        category = LogCategory.VISUAL_TRIGGER,
        displayName = "Visual Trigger",
        description = "Image matching & actions",
        icon = Icons.Default.Visibility,
        accentColor = AccentGreen
    ),
    CategorySummary(
        category = LogCategory.FLOW_BUILDER,
        displayName = "Flow Builder",
        description = "Visual automation flows",
        icon = Icons.Default.AccountTree,
        accentColor = AccentPurple
    ),
    CategorySummary(
        category = LogCategory.SYSTEM_CONTEXT,
        displayName = "System Context",
        description = "Location, time & battery",
        icon = Icons.Default.SettingsSystemDaydream,
        accentColor = AccentBlue
    ),
    CategorySummary(
        category = LogCategory.EMERGENCY_TRIGGER,
        displayName = "Emergency Trigger",
        description = "Panic gestures & alerts",
        icon = Icons.Default.Warning,
        accentColor = AccentRed
    ),
    CategorySummary(
        category = LogCategory.CROSS_DEVICE_SYNC,
        displayName = "Cross-Device Sync",
        description = "Multi-device coordination",
        icon = Icons.Default.Devices,
        accentColor = Color(0xFF3F51B5)
    )
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DebuggerViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.get(application).executionLogDao()

    /** Category summaries with live counts and latest log */
    val categorySummaries: StateFlow<List<CategorySummary>> = combine(
        ALL_CATEGORIES.map { cat ->
            combine(
                dao.getCountByCategory(cat.category),
                dao.getLatestByCategory(cat.category)
            ) { count, latest ->
                cat.copy(logCount = count, latestLog = latest)
            }
        }
    ) { summaries -> summaries.toList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ALL_CATEGORIES)

    /** Total log count */
    val totalLogCount: StateFlow<Int> = dao.getTotalCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // --- Detail screen state ---

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory

    private val _selectedLevel = MutableStateFlow<String?>(null)
    val selectedLevel: StateFlow<String?> = _selectedLevel

    /** Logs for the selected category, optionally filtered by level */
    val logsForCategory: StateFlow<List<ExecutionLog>> = combine(
        _selectedCategory,
        _selectedLevel
    ) { category, level ->
        category to level
    }.flatMapLatest { (category, level) ->
        if (category == null) flowOf(emptyList())
        else if (level == null) dao.getByCategory(category)
        else dao.getByCategoryAndLevel(category, level)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectCategory(category: String) {
        _selectedCategory.value = category
        _selectedLevel.value = null
    }

    fun filterByLevel(level: String?) {
        _selectedLevel.value = level
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteAll()
        }
    }

    fun clearCategory(category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteByCategory(category)
        }
    }
}
