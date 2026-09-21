package com.autonion.automationcompanion.features.screen_understanding_ml.core

import java.text.Normalizer
import java.util.Locale

/** Shared text identity rules for OCR and accessibility targeting. */
object TextMatching {
    private val punctuation = Regex("[^\\p{L}\\p{M}\\p{N}\\s]")
    private val whitespace = Regex("\\s+")

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(punctuation, " ").replace(whitespace, " ").trim()

    fun score(candidate: String?, target: String): Float =
        scoreNormalized(normalize(candidate.orEmpty()), normalize(target))

    internal fun scoreNormalized(candidate: String, target: String): Float {
        if (candidate.isEmpty() || target.isEmpty()) return 0f
        if (candidate == target) return 1f
        // Containment is not identity: "Allow" must not match "Don't allow".
        // OCR callers supply individual lines as well as legacy multiline blocks.
        // Short labels and different numbers must be exact; fuzzy matching is for OCR noise.
        if (minOf(candidate.length, target.length) < 5) return 0f
        if (candidate.filter(Char::isDigit) != target.filter(Char::isDigit)) return 0f
        if (minOf(candidate.length, target.length).toFloat() / maxOf(candidate.length, target.length) < 0.8f) return 0f
        val similarity = bigramSimilarity(candidate, target)
        return if (similarity >= 0.8f) similarity * 0.85f else 0f
    }

    fun similarity(a: String, b: String): Float = bigramSimilarity(normalize(a), normalize(b))

    private fun bigramSimilarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b) return 1f
        if (a.length < 2 || b.length < 2) return 0f
        val left = a.windowed(2).toSet()
        val right = b.windowed(2).toSet()
        val intersection = left.count { it in right }
        return intersection.toFloat() / (left.size + right.size - intersection)
    }
}
