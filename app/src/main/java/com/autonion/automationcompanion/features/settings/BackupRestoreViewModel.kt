package com.autonion.automationcompanion.features.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonion.automationcompanion.core.backup.BackupManager
import com.autonion.automationcompanion.core.backup.BackupManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the Backup & Restore screen.
 */
data class BackupRestoreUiState(
    // Feature toggles for export
    val gesturePresets: Boolean = true,
    val visionPresets: Boolean = true,
    val visionImages: Boolean = true,
    val mlPresets: Boolean = true,
    val flows: Boolean = true,
    val flowAssets: Boolean = true,
    val systemContextDb: Boolean = true,
    val excludedApps: Boolean = true,

    // Password
    val usePassword: Boolean = false,
    val password: String = "",
    val confirmPassword: String = "",

    // Import password prompt
    val importNeedsPassword: Boolean = false,
    val importPassword: String = "",
    val importUri: Uri? = null,

    // Progress & status
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val progress: Int = 0,
    val estimatedSize: Long = 0,

    // Results
    val successMessage: String? = null,
    val errorMessage: String? = null
)

class BackupRestoreViewModel(application: Application) : AndroidViewModel(application) {

    private val backupManager = BackupManager(application)

    private val _uiState = MutableStateFlow(BackupRestoreUiState())
    val uiState: StateFlow<BackupRestoreUiState> = _uiState.asStateFlow()

    init {
        updateEstimatedSize()
    }

    // ─── Feature toggles ────────────────────────────────────────────────

    fun toggleGesturePresets(enabled: Boolean) {
        _uiState.update { it.copy(gesturePresets = enabled) }
        updateEstimatedSize()
    }

    fun toggleVisionPresets(enabled: Boolean) {
        _uiState.update { it.copy(visionPresets = enabled) }
        updateEstimatedSize()
    }

    fun toggleVisionImages(enabled: Boolean) {
        _uiState.update { it.copy(visionImages = enabled) }
        updateEstimatedSize()
    }

    fun toggleMlPresets(enabled: Boolean) {
        _uiState.update { it.copy(mlPresets = enabled) }
        updateEstimatedSize()
    }

    fun toggleFlows(enabled: Boolean) {
        _uiState.update { it.copy(flows = enabled) }
        updateEstimatedSize()
    }

    fun toggleFlowAssets(enabled: Boolean) {
        _uiState.update { it.copy(flowAssets = enabled) }
        updateEstimatedSize()
    }

    fun toggleSystemContextDb(enabled: Boolean) {
        _uiState.update { it.copy(systemContextDb = enabled) }
        updateEstimatedSize()
    }

    fun toggleExcludedApps(enabled: Boolean) {
        _uiState.update { it.copy(excludedApps = enabled) }
        updateEstimatedSize()
    }

    // ─── Password ───────────────────────────────────────────────────────

    fun toggleUsePassword(enabled: Boolean) {
        _uiState.update { it.copy(usePassword = enabled, password = "", confirmPassword = "") }
    }

    fun setPassword(password: String) {
        _uiState.update { it.copy(password = password) }
    }

    fun setConfirmPassword(password: String) {
        _uiState.update { it.copy(confirmPassword = password) }
    }

    fun setImportPassword(password: String) {
        _uiState.update { it.copy(importPassword = password) }
    }

    // ─── Export ──────────────────────────────────────────────────────────

    fun canExport(): Boolean {
        val state = _uiState.value
        val anyFeatureSelected = state.gesturePresets || state.visionPresets ||
                state.visionImages || state.mlPresets || state.flows ||
                state.flowAssets || state.systemContextDb || state.excludedApps
        val passwordValid = !state.usePassword ||
                (state.password.isNotBlank() && state.password == state.confirmPassword)
        return anyFeatureSelected && passwordValid && !state.isExporting && !state.isImporting
    }

    fun export(uri: Uri) {
        val state = _uiState.value
        val features = buildFeatureList(state)
        val password = if (state.usePassword && state.password.isNotBlank()) state.password else null

        _uiState.update { it.copy(isExporting = true, progress = 0, successMessage = null, errorMessage = null) }

        viewModelScope.launch(Dispatchers.IO) {
            val success = backupManager.export(
                uri = uri,
                features = features,
                password = password,
                onProgress = { progress ->
                    _uiState.update { it.copy(progress = progress) }
                }
            )

            _uiState.update {
                it.copy(
                    isExporting = false,
                    progress = if (success) 100 else 0,
                    successMessage = if (success) "Backup exported successfully!" else null,
                    errorMessage = if (!success) "Export failed. Please try again." else null
                )
            }
        }
    }

    // ─── Import ─────────────────────────────────────────────────────────

    fun import(uri: Uri) {
        _uiState.update {
            it.copy(
                isImporting = true, progress = 0,
                successMessage = null, errorMessage = null,
                importNeedsPassword = false, importPassword = "", importUri = uri
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            performImport(uri, null)
        }
    }

    fun submitImportPassword() {
        val state = _uiState.value
        val uri = state.importUri ?: return
        val password = state.importPassword

        _uiState.update { it.copy(isImporting = true, importNeedsPassword = false, progress = 0) }

        viewModelScope.launch(Dispatchers.IO) {
            performImport(uri, password)
        }
    }

    fun cancelImportPassword() {
        _uiState.update { it.copy(importNeedsPassword = false, importUri = null, importPassword = "") }
    }

    private fun performImport(uri: Uri, password: String?) {
        val result = backupManager.import(
            uri = uri,
            password = password,
            onProgress = { progress ->
                _uiState.update { it.copy(progress = progress) }
            }
        )

        when (result) {
            is BackupManager.ImportResult.Success -> {
                val featureCount = result.manifest.includedFeatures.size
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        progress = 100,
                        successMessage = "Backup restored! ($featureCount data sets imported)",
                        importUri = null
                    )
                }
            }
            is BackupManager.ImportResult.NeedsPassword -> {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importNeedsPassword = true,
                        progress = 0
                    )
                }
            }
            is BackupManager.ImportResult.WrongPassword -> {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importNeedsPassword = true,
                        errorMessage = "Wrong password. Please try again.",
                        progress = 0
                    )
                }
            }
            is BackupManager.ImportResult.Error -> {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        errorMessage = result.message,
                        progress = 0,
                        importUri = null
                    )
                }
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    fun clearMessages() {
        _uiState.update { it.copy(successMessage = null, errorMessage = null) }
    }

    private fun buildFeatureList(state: BackupRestoreUiState): List<String> {
        return buildList {
            if (state.gesturePresets) add(BackupManifest.FEATURE_GESTURE_PRESETS)
            if (state.visionPresets) add(BackupManifest.FEATURE_VISION_PRESETS)
            if (state.visionImages) add(BackupManifest.FEATURE_VISION_IMAGES)
            if (state.mlPresets) add(BackupManifest.FEATURE_ML_PRESETS)
            if (state.flows) add(BackupManifest.FEATURE_FLOWS)
            if (state.flowAssets) add(BackupManifest.FEATURE_FLOW_ASSETS)
            if (state.systemContextDb) add(BackupManifest.FEATURE_SYSTEM_CONTEXT_DB)
            if (state.excludedApps) add(BackupManifest.FEATURE_EXCLUDED_APPS)
        }
    }

    private fun updateEstimatedSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val features = buildFeatureList(_uiState.value)
            val size = backupManager.estimateBackupSize(features)
            _uiState.update { it.copy(estimatedSize = size) }
        }
    }
}
