package com.autonion.automationcompanion.features.semantic_automation.ml

/** Formats the single user turn used by both app features, based on GGUF template metadata. */
internal object GgufPromptFormatter {
    fun knownTemplate(template: String, prompt: String): String? = when {
        // The 0.1.7 runtime loads Gemma 4 but its legacy template API lacks these new tokens.
        // https://ai.google.dev/gemma/docs/core/prompt-formatting-gemma4
        template.contains("<|turn>") && template.contains("<turn|>") ->
            "<|turn>user\n$prompt<turn|>\n<|turn>model\n"
        template.contains("<|im_start|>") && template.contains("<|im_end|>") ->
            "<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"
        template.contains("<start_of_turn>") && template.contains("<end_of_turn>") ->
            "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"
        template.isBlank() -> prompt // Base models may have no chat template.
        else -> null
    }
}
