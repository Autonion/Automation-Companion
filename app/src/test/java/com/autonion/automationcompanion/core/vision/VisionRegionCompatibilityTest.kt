package com.autonion.automationcompanion.core.vision

import com.autonion.automationcompanion.features.visual_trigger.models.VisionRegion
import com.autonion.automationcompanion.features.visual_trigger.models.VisionMatchMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class VisionRegionCompatibilityTest {
    @Test fun oldRegionsRemainStaticWithTheirOriginalThreshold() {
        val old = """{"id":1,"x":10,"y":20,"width":30,"height":40,"templatePath":"test.png","color":0,"matchThreshold":0.95}"""
        val region = Json.decodeFromString<VisionRegion>(old)
        assertEquals(VisionMatchMode.STATIC, region.matchMode)
        assertEquals(0.95f, region.effectiveThreshold, 0.001f)
        assertEquals(0.80f, region.copy(matchMode = VisionMatchMode.MOVING).effectiveThreshold, 0.001f)
    }

    @Test fun movingSettingsSurviveSerialization() {
        val region = VisionRegion(1, 10, 20, 30, 40, "test.png", color = 0,
            matchMode = VisionMatchMode.MOVING, movingMatchThreshold = 0.85f, tapLeadMs = 60,
            searchX = 1, searchY = 2, searchWidth = 300, searchHeight = 600)
        assertEquals(region, Json.decodeFromString<VisionRegion>(Json.encodeToString(region)))
    }
}
