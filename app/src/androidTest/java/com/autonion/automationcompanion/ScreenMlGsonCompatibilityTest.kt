package com.autonion.automationcompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationPreset
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ExecutionMode
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ScopeType
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Needs Android's real RectF constructor and fields; the local JVM Android stub cannot provide them. */
@RunWith(AndroidJUnit4::class)
class ScreenMlGsonCompatibilityTest {
    @Test
    fun legacyPresetRestoresNestedStepsAnchorsAndCoordinatesWithoutChangingTheFileFormat() {
        val legacy = """{
            "id":"preset-1","name":"Login","scope":"APP_SPECIFIC",
            "targetPackageName":"com.example.target","executionMode":"STRICT","createdAt":1234,
            "steps":[{
                "id":"step-1","orderIndex":0,"label":"Username","actionType":"INPUT_TEXT",
                "anchor":{
                    "id":"anchor-1","label":"EditText","confidence":0.95,
                    "bounds":{"left":10.0,"top":20.0,"right":300.0,"bottom":80.0},
                    "text":"Username","visualFingerprint":"fingerprint","lastSeenTimestamp":1200,
                    "source":"accessibility"
                },
                "inputText":"example","isOptional":false,
                "captureScreenWidth":1080.0,"captureScreenHeight":2400.0
            }]
        }"""
        val gson = Gson()
        val preset = gson.fromJson(legacy, AutomationPreset::class.java)

        assertEquals("preset-1", preset.id)
        assertEquals(ScopeType.APP_SPECIFIC, preset.scope)
        assertEquals(ExecutionMode.STRICT, preset.executionMode)
        assertEquals("com.example.target", preset.targetPackageName)
        val step = preset.steps.single()
        assertEquals("step-1", step.id)
        assertEquals(0, step.orderIndex)
        assertEquals("Username", step.label)
        assertEquals(ActionType.INPUT_TEXT, step.actionType)
        assertEquals("example", step.inputText)
        assertFalse(step.isOptional)
        assertEquals(1080f, step.captureScreenWidth, 0f)
        assertEquals(2400f, step.captureScreenHeight, 0f)
        val anchor = step.anchor
        assertEquals("anchor-1", anchor.id)
        assertEquals("EditText", anchor.label)
        assertEquals(0.95f, anchor.confidence, 0.0001f)
        assertEquals(10f, anchor.bounds.left, 0f)
        assertEquals(20f, anchor.bounds.top, 0f)
        assertEquals(300f, anchor.bounds.right, 0f)
        assertEquals(80f, anchor.bounds.bottom, 0f)
        assertEquals("Username", anchor.text)
        assertEquals("fingerprint", anchor.visualFingerprint)
        assertEquals(1200L, anchor.lastSeenTimestamp)
        assertEquals("accessibility", anchor.source)
        // Compare the on-disk JSON. toJsonTree retains Float values whose binary
        // precision differs from the decimal numbers parsed out of the fixture.
        assertEquals(JsonParser.parseString(legacy), JsonParser.parseString(gson.toJson(preset)))
    }
}
