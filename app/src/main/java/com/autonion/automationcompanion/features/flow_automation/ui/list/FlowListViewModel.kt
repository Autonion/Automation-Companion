package com.autonion.automationcompanion.features.flow_automation.ui.list

import android.app.Application
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import com.autonion.automationcompanion.features.flow_automation.data.FlowRepository
import com.autonion.automationcompanion.features.flow_automation.model.FlowGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "FlowListViewModel"

class FlowListViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FlowRepository(application)

    private val _flows = MutableStateFlow<List<FlowGraph>>(emptyList())
    val flows: StateFlow<List<FlowGraph>> = _flows.asStateFlow()

    fun refresh() {
        _flows.value = repository.listAll()
    }

    fun deleteFlow(flowId: String) {
        repository.delete(flowId)
        refresh()
    }

    fun exportFlow(flowId: String, uri: Uri) {
        val success = repository.exportToUri(flowId, uri)
        val msg = if (success) "Flow exported successfully" else "Export failed"
        Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Export result: $success for flow $flowId")
    }

    fun importFlow(uri: Uri) {
        val imported = repository.importFromUri(uri)
        if (imported != null) {
            Toast.makeText(getApplication(), "Imported: ${imported.name}", Toast.LENGTH_SHORT).show()
            refresh()
        } else {
            Toast.makeText(getApplication(), "Import failed — invalid flow file", Toast.LENGTH_SHORT).show()
        }
    }
}
