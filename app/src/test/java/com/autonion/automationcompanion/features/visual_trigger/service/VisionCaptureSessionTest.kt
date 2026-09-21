package com.autonion.automationcompanion.features.visual_trigger.service

import org.junit.Assert.*
import org.junit.Test

class VisionCaptureSessionTest {
    @Test fun newCaptureCannotInheritPreviousPresetOrFlowSettings() {
        val old = VisionCaptureSession(presetName = "sample")
        val saved = old.afterEditorResult(old.id, "sample-id")
        assertEquals("sample-id", saved.activePresetId)
        val fresh = VisionCaptureSession(presetName = "new preset")
        assertNotEquals(saved.id, fresh.id)
        assertNull(fresh.activePresetId)
        assertFalse(fresh.isFlowMode)
        assertNull(fresh.flowNodeId)
        assertNull(fresh.flowVisionJson)
        assertFalse(fresh.clearOnStart)
    }

    @Test fun standaloneEditAndStaleEditorCannotRetargetCurrentCapture() {
        val session = VisionCaptureSession(presetName = "new preset")
        assertEquals(session, session.afterEditorResult(null, "sample-id"))
        assertEquals(session, session.afterEditorResult("old-session", "sample-id"))
    }

    @Test fun sameSessionCanAppendMoreCapturesButCancelKeepsDestination() {
        val session = VisionCaptureSession()
        val saved = session.afterEditorResult(session.id, "saved-id")
        assertEquals("saved-id", saved.activePresetId)
        assertEquals(saved, saved.afterEditorResult(session.id, null))
    }

    @Test fun flowResultCannotBecomeStandaloneAppendDestination() {
        val flow = VisionCaptureSession(isFlowMode = true, flowNodeId = "node", clearOnStart = true)
        assertEquals(flow, flow.afterEditorResult(flow.id, "flow-file.json"))
    }
}
