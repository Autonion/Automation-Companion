package com.autonion.automationcompanion

import android.graphics.Rect
import android.graphics.RectF
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autonion.automationcompanion.features.screen_understanding_ml.core.*
import com.autonion.automationcompanion.features.screen_understanding_ml.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenMlVisualTextTest {
    private val hint = "Search Google or type URL"
    private val lens = "Search with your camera using Google Lens"
    private val savedText = "$hint $lens"
    private val bar = UIElement("bar", "button", 0.8f,
        RectF(60.36515f, 501.81448f, 1032.2452f, 695.04865f))
    private val textNodes get() = listOf(
        CapturedTextNode(hint, 90f, 550f, 800f, 650f),
        // The icon starts higher than the hint. Top/left sorting would change saved text.
        CapturedTextNode(lens, 900f, 530f, 1000f, 630f)
    )

    @Test fun savedSearchBarAnchorMatchesJoinedLiveTextThroughHybridMatcher() {
        val snapshot = textNodes.map { it.textElement() }
        val captured = OcrMatching.enrichWithText(listOf(bar), snapshot).single()
        assertEquals(savedText, captured.text)
        // The old best-overlap enrichment supplied only this hint and failed the gate.
        assertFalse(HybridElementMatcher.isTextMatching(hint, savedText))

        val live = AccessibilityAugmenter.enrichWithAccessibilityText(listOf(bar), snapshot).single()
        assertEquals(savedText, live.text)
        val match = match(live, savedText)
        assertNotNull(match)
        assertEquals(bar.id, match!!.element.id)
        assertEquals(bar.bounds, match.element.bounds)
        assertEquals(bar.confidence, match.element.confidence, 0f)
        assertEquals("yolo", match.source)
    }

    @Test fun partialCompositeTextStillCannotMatchSavedAnchor() {
        val live = AccessibilityAugmenter.enrichWithAccessibilityText(
            listOf(bar), textNodes.take(1).map { it.textElement() }).single()
        assertEquals(hint, live.text)
        assertNull(match(live, savedText))
    }

    @Test fun smallContainedLabelWorksEvenWhenIoUIsBelowOldThreshold() {
        val label = UIElement("label", "Text", 1f, RectF(490f, 590f, 520f, 610f), "Go")
        val live = AccessibilityAugmenter.enrichWithAccessibilityText(listOf(bar), listOf(label)).single()
        assertEquals("Go", live.text)
        assertNotNull(match(live, "Go"))
    }

    @Test fun adjacentAndInvalidTextDoesNotPolluteTheSelectedElement() {
        val button = UIElement("button", "button", 1f, RectF(0f, 0f, 100f, 100f))
        val nodes = listOf(
            UIElement("inside", "Text", 1f, RectF(10f, 20f, 50f, 50f), "Allow"),
            UIElement("adjacent", "Text", 1f, RectF(101f, 20f, 190f, 50f), "Don't allow"),
            UIElement("sliver", "Text", 1f, RectF(95f, 20f, 150f, 50f), "Cancel"),
            UIElement("empty", "Text", 1f, RectF(20f, 20f, 20f, 30f), "Invalid")
        )
        val live = AccessibilityAugmenter.enrichWithAccessibilityText(listOf(button), nodes).single()
        assertEquals("Allow", live.text)
        assertFalse(HybridElementMatcher.isTextMatching(live.text, "Don't allow"))
        assertFalse(HybridElementMatcher.isTextMatching("Don't allow", "Allow"))
    }

    @Test fun existingElementTextAndTextlessIconsArePreserved() {
        val named = bar.copy(text = "Existing name", source = "accessibility")
        assertSame(named, AccessibilityAugmenter.enrichWithAccessibilityText(
            listOf(named), textNodes.map { it.textElement() }).single())
        assertSame(bar, AccessibilityAugmenter.enrichWithAccessibilityText(listOf(bar), emptyList()).single())
    }

    @Test fun ocrAndAccessibilityUseTheSameContainmentAndSourceOrder() {
        val ocr = OcrResult(savedText, textNodes.map { node ->
            val bounds = node.textElement().bounds
            OcrBlock(node.text, bounds, listOf(OcrLine(node.text, bounds, 1f)), 1f)
        })
        assertEquals(savedText, OcrMatching.enrich(listOf(bar), ocr).single().text)
        assertEquals(savedText, AccessibilityAugmenter.enrichWithAccessibilityText(
            listOf(bar), textNodes.map { it.textElement() }).single().text)
    }

    @Suppress("DEPRECATION")
    @Test fun descriptionOnNonInteractiveSmallContainerRemainsAvailableToBothPaths() {
        // Interactive-element collection excludes ViewGroups and nodes smaller than 10px.
        // The editor has always used the full text traversal, so playback must do so too.
        val node = AccessibilityNodeInfo.obtain().apply {
            className = "android.view.ViewGroup"
            contentDescription = lens
            isVisibleToUser = true
            setBoundsInScreen(Rect(900, 550, 909, 559))
        }
        try {
            val collected = AccessibilityAugmenter.textNodesFrom(node)
            assertEquals(listOf(lens), collected.map { it.text })
            assertEquals(lens, AccessibilityAugmenter.enrichWithAccessibilityText(
                listOf(bar), collected.map { it.textElement() }).single().text)
        } finally { node.recycle() }
    }

    private fun match(live: UIElement, text: String) = HybridElementMatcher.findBestMatch(
        anchorLabel = bar.label, anchorBounds = bar.bounds, anchorText = text,
        yoloCandidates = listOf(live), normalizedAnchor = null,
        currentScreenWidth = 1080f, currentScreenHeight = 2340f
    )
}
