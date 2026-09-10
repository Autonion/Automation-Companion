package com.autonion.automationcompanion.serialization

import com.autonion.automationcompanion.features.agent_core.models.AgentRequest
import com.autonion.automationcompanion.features.agent_core.models.AgentRequestContext
import com.autonion.automationcompanion.features.cross_device_automation.domain.RawEvent
import com.autonion.automationcompanion.features.flow_automation.data.ListFlowsRequest
import com.autonion.automationcompanion.features.flow_automation.data.StopFlowRequest
import com.autonion.automationcompanion.features.flow_automation.data.TriggerFlowRequest
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationPreset
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ExecutionMode
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ScopeType
import com.autonion.automationcompanion.features.semantic_automation.ml.OllamaChatMessage
import com.autonion.automationcompanion.features.semantic_automation.ml.OllamaChatRequest
import com.autonion.automationcompanion.features.semantic_automation.ml.OllamaChatResponse
import com.autonion.automationcompanion.features.semantic_automation.ml.OllamaTagsResponse
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Golden JSON contracts from the unminified app, shared with existing files and desktop peers. */
class GsonCompatibilityTest {
    private val gson = Gson()

    @Test
    fun legacyScreenMlPresetAndEnumNamesRemainReadable() {
        val legacy = """{
            "id":"preset-1","name":"Login","scope":"APP_SPECIFIC",
            "targetPackageName":"com.example.target","executionMode":"STRICT",
            "steps":[],"createdAt":1700000000000
        }"""
        val preset = gson.fromJson(legacy, AutomationPreset::class.java)

        assertEquals("preset-1", preset.id)
        assertEquals("Login", preset.name)
        assertEquals(ScopeType.APP_SPECIFIC, preset.scope)
        assertEquals("com.example.target", preset.targetPackageName)
        assertEquals(ExecutionMode.STRICT, preset.executionMode)
        assertEquals(1700000000000L, preset.createdAt)
        assertTrue(preset.steps.isEmpty())
        assertJsonEquals(legacy, preset)

        assertEquals("\"GLOBAL\"", gson.toJson(ScopeType.GLOBAL))
        assertEquals("\"FLEXIBLE\"", gson.toJson(ExecutionMode.FLEXIBLE))
        val actionNames = listOf("CLICK", "SCROLL_UP", "SCROLL_DOWN", "INPUT_TEXT", "WAIT", "FINISH", "FAIL")
        actionNames.zip(ActionType.entries).forEach { (wireName, actionType) ->
            assertEquals("\"$wireName\"", gson.toJson(actionType))
            assertEquals(actionType, gson.fromJson("\"$wireName\"", ActionType::class.java))
        }
    }

    @Test
    fun desktopFlowCommandsKeepExistingProtocolKeys() {
        assertJsonEquals(
            """{"type":"list_flows","transactionId":"txn-1"}""",
            ListFlowsRequest(transactionId = "txn-1")
        )
        assertJsonEquals(
            """{"type":"trigger_flow","transactionId":"txn-2","flowId":"flow-1"}""",
            TriggerFlowRequest(transactionId = "txn-2", flowId = "flow-1")
        )
        assertJsonEquals(
            """{"type":"stop_flow","transactionId":"txn-3","flowId":"flow-1"}""",
            StopFlowRequest(transactionId = "txn-3", flowId = "flow-1")
        )
    }

    @Test
    fun clipboardEventsKeepExistingProtocolKeys() {
        assertJsonEquals(
            """{"id":"event-1","timestamp":1234,"type":"clipboard.text_copied",
                "sourceDeviceId":"local","payload":{"text":"hello"}}""",
            RawEvent("event-1", 1234L, "clipboard.text_copied", "local", mapOf("text" to "hello"))
        )
    }

    @Test
    fun agentRequestKeepsNestedContextLimitsAndSafetyKeys() {
        assertJsonEquals(
            """{
                "type":"agent_request","schemaVersion":1,"transactionId":"txn-1",
                "prompt":"Open calculator","timestamp":1234,"sourceDeviceId":"android_controller",
                "target":"desktop","context":"previous request",
                "agentContext":{"conversationSummary":"previous request",
                    "preferredModelMode":"desktop_default","origin":"cross_device_chat"},
                "conversationHistory":[{"role":"user","content":"hello"}],
                "limits":{"maxSteps":15,"stepTimeoutMs":45000,"overallTimeoutMs":300000},
                "safety":{"allowDestructive":false,"requireConfirmationForRisky":true,
                    "redactSensitiveTextForCloud":true},
                "capabilitiesRequired":["ui_observation","click","type","hotkey","scroll","verification"]
            }""",
            AgentRequest(
                transactionId = "txn-1", prompt = "Open calculator", timestamp = 1234L,
                context = "previous request",
                agentContext = AgentRequestContext("previous request", "desktop_default", "cross_device_chat"),
                conversationHistory = listOf(mapOf("role" to "user", "content" to "hello"))
            )
        )
    }

    @Test
    fun ollamaRequestsKeepSchemaOptionsAndMessageKeys() {
        assertJsonEquals(
            """{
                "model":"example-model","messages":[{"role":"user","content":"hello"}],
                "stream":false,"format":{"type":"object"},
                "options":{"temperature":0.1,"num_ctx":2048},"think":false
            }""",
            OllamaChatRequest(
                model = "example-model", messages = listOf(OllamaChatMessage("user", "hello")),
                format = mapOf("type" to "object"),
                options = mapOf("temperature" to 0.1, "num_ctx" to 2048), think = false
            )
        )
    }

    @Test
    fun ollamaResponsesRestoreGenericModelListsAndNestedMessages() {
        val tags = gson.fromJson(
            """{"models":[{"name":"example-model","size":12345,"digest":"sha256:abc",
                "modified_at":"2026-09-09T00:00:00Z"}]}""",
            OllamaTagsResponse::class.java
        )
        assertEquals(1, tags.models.size)
        val model = tags.models.single()
        assertEquals("example-model", model.name)
        assertEquals(12345L, model.size)
        assertEquals("sha256:abc", model.digest)
        assertEquals("2026-09-09T00:00:00Z", model.modified_at)

        val response = gson.fromJson(
            """{"model":"example-model","message":{"role":"assistant","content":"hello"},
                "done":true,"total_duration":4567,"eval_count":8}""",
            OllamaChatResponse::class.java
        )
        assertEquals("example-model", response.model)
        assertEquals(OllamaChatMessage("assistant", "hello"), response.message)
        assertTrue(response.done)
        assertEquals(4567L, response.total_duration)
        assertEquals(8, response.eval_count)
    }

    private fun assertJsonEquals(expected: String, actual: Any) {
        assertEquals(JsonParser.parseString(expected), gson.toJsonTree(actual))
    }
}
