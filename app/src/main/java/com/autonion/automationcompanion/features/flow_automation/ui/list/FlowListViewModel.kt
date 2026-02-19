package com.autonion.automationcompanion.features.flow_automation.ui.list

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.autonion.automationcompanion.features.flow_automation.data.FlowRepository
import com.autonion.automationcompanion.features.flow_automation.model.FlowGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
}
