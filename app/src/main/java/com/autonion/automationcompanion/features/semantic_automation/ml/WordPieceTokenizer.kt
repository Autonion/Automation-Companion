package com.autonion.automationcompanion.features.semantic_automation.ml

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Pure-Kotlin WordPiece tokenizer compatible with BERT / MiniLM models.
 * Loads `vocab.txt` from assets and converts text → token IDs.
 */
class WordPieceTokenizer(context: Context, vocabAsset: String = "vocab.txt") {

    companion object {
        private const val TAG = "WordPieceTok"
        private const val UNK_TOKEN = "[UNK]"
        private const val CLS_TOKEN = "[CLS]"
        private const val SEP_TOKEN = "[SEP]"
        private const val PAD_TOKEN = "[PAD]"
        private const val MAX_WORD_LEN = 200
    }

    private val vocab: Map<String, Int>
    private val unkId: Int
    private val clsId: Int
    private val sepId: Int
    private val padId: Int

    init {
        val map = LinkedHashMap<String, Int>()
        BufferedReader(InputStreamReader(context.assets.open(vocabAsset))).use { reader ->
            var idx = 0
            reader.forEachLine { line ->
                map[line] = idx
                idx++
            }
        }
        vocab = map
        unkId = vocab[UNK_TOKEN] ?: 0
        clsId = vocab[CLS_TOKEN] ?: 101
        sepId = vocab[SEP_TOKEN] ?: 102
        padId = vocab[PAD_TOKEN] ?: 0
        Log.d(TAG, "Loaded ${vocab.size} tokens from $vocabAsset")
    }

    /**
     * Tokenize text into token IDs with [CLS] and [SEP] markers.
     * Returns a Triple of (inputIds, attentionMask, tokenTypeIds).
     * All arrays are padded/truncated to [maxLen].
     */
    fun encode(text: String, maxLen: Int = 128): Triple<LongArray, LongArray, LongArray> {
        val tokens = tokenize(text)

        // Truncate to maxLen - 2 (for CLS + SEP)
        val truncated = if (tokens.size > maxLen - 2) tokens.subList(0, maxLen - 2) else tokens

        val inputIds = LongArray(maxLen)
        val attentionMask = LongArray(maxLen)
        val tokenTypeIds = LongArray(maxLen) // all zeros for single-sentence

        inputIds[0] = clsId.toLong()
        attentionMask[0] = 1L

        for (i in truncated.indices) {
            inputIds[i + 1] = (vocab[truncated[i]] ?: unkId).toLong()
            attentionMask[i + 1] = 1L
        }

        inputIds[truncated.size + 1] = sepId.toLong()
        attentionMask[truncated.size + 1] = 1L

        // Rest stays 0 (PAD)
        return Triple(inputIds, attentionMask, tokenTypeIds)
    }

    /**
     * Perform basic tokenization + WordPiece splitting.
     */
    private fun tokenize(text: String): List<String> {
        val result = mutableListOf<String>()
        val basicTokens = basicTokenize(text)

        for (token in basicTokens) {
            if (token.length > MAX_WORD_LEN) {
                result.add(UNK_TOKEN)
                continue
            }
            val subTokens = wordPieceSplit(token)
            result.addAll(subTokens)
        }
        return result
    }

    /**
     * Basic tokenization: lowercase, strip accents, split on whitespace + punctuation.
     */
    private fun basicTokenize(text: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()

        for (c in text.lowercase().trim()) {
            when {
                c.isWhitespace() -> {
                    if (sb.isNotEmpty()) {
                        result.add(sb.toString())
                        sb.clear()
                    }
                }
                isPunctuation(c) -> {
                    if (sb.isNotEmpty()) {
                        result.add(sb.toString())
                        sb.clear()
                    }
                    result.add(c.toString())
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result
    }

    /**
     * WordPiece: greedily match longest subword from vocab.
     */
    private fun wordPieceSplit(token: String): List<String> {
        val subTokens = mutableListOf<String>()
        var start = 0

        while (start < token.length) {
            var end = token.length
            var found = false

            while (start < end) {
                val sub = if (start == 0) {
                    token.substring(start, end)
                } else {
                    "##" + token.substring(start, end)
                }

                if (vocab.containsKey(sub)) {
                    subTokens.add(sub)
                    found = true
                    break
                }
                end--
            }

            if (!found) {
                subTokens.add(UNK_TOKEN)
                break
            }
            start = end
        }
        return subTokens
    }

    private fun isPunctuation(c: Char): Boolean {
        val cp = c.code
        if (c in '!'..'/' || c in ':'..'@' || c in '['..'`' || c in '{'..'~') return true
        if (Character.getType(c).toByte() == Character.OTHER_PUNCTUATION.toByte()) return true
        return false
    }
}
