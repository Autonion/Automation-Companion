package com.autonion.automationcompanion.features.semantic_automation.ml

import org.junit.Assert.*
import org.junit.Test

class GgufPromptFormatterTest {
    private val prompt = "Find café\nReturn {\"action\":\"FINISH\"}"

    @Test fun gemma4UsesItsNewTurnTokens() {
        assertEquals(
            "<|turn>user\n$prompt<turn|>\n<|turn>model\n",
            GgufPromptFormatter.knownTemplate("{{ '<|turn>' + role + content + '<turn|>' }}", prompt)
        )
    }

    @Test fun qwenPreservesJsonUnicodeAndNewlines() {
        assertEquals(
            "<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n",
            GgufPromptFormatter.knownTemplate("<|im_start|>...<|im_end|>", prompt)
        )
    }

    @Test fun olderGemmaKeepsItsOriginalTurnTokens() {
        assertEquals(
            "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n",
            GgufPromptFormatter.knownTemplate("<start_of_turn>...<end_of_turn>", prompt)
        )
    }

    @Test fun unknownTemplatesDelegateAndBaseModelsKeepRawPrompt() {
        assertNull(GgufPromptFormatter.knownTemplate("some other model template", prompt))
        assertEquals(prompt, GgufPromptFormatter.knownTemplate("", prompt))
    }
}
