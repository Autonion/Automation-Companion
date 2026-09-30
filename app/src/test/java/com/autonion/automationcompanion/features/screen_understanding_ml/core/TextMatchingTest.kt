package com.autonion.automationcompanion.features.screen_understanding_ml.core

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class TextMatchingTest {
    @Test fun shortLabelsDoNotMatchInsideOtherWords() {
        for ((candidate, target) in listOf("Continue" to "on", "Payment" to "Pay",
            "A" to "B", "Allow" to "Allow all", "Don't allow" to "Allow", "Order 12" to "Order 13")) {
            assertEquals("$candidate must not match $target", 0f, TextMatching.score(candidate, target), 0f)
        }
    }

    @Test fun completePhraseOutranksContainmentAndRejectsPartialMultilineText() {
        assertTrue(TextMatching.score("Sign in", "Sign in") > TextMatching.score("Sign in with email", "Sign in"))
        assertEquals(0f, TextMatching.score("Sign", "Sign in with email"), 0f)
        assertEquals(1f, TextMatching.score("Sign in\nwith email", "SIGN IN with email"), 0f)
    }

    @Test fun normalizationHandlesUnicodeWhitespaceAndDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(1f, TextMatching.score("ＳＩＧＮ　ＩＮ", "sign in"), 0f)
            assertEquals(1f, TextMatching.score("Sign\n in…", "SIGN IN"), 0f)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun differentSingleCharactersHaveNoFuzzySimilarity() {
        assertEquals(0f, TextMatching.similarity("A", "B"), 0f)
        assertEquals(1f, TextMatching.similarity("A", "a"), 0f)
        assertEquals(0f, TextMatching.score("", "!!!"), 0f)
    }
}
