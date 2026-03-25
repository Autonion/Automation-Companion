package com.autonion.automationcompanion.features.semantic_automation.ml

import com.autonion.automationcompanion.features.semantic_automation.model.SemanticGoal
import com.autonion.automationcompanion.features.semantic_automation.model.ScreenUIState

object UIPromptFormatter {

    /**
     * Converts the current ScreenUIState and Goal into a strict text prompt 
     * optimized for Gemma 2B to output a JSON string.
     */
    fun buildPrompt(goal: SemanticGoal, uiState: ScreenUIState): String {
        val sb = StringBuilder()
        
        // Gemma Chat Template prefix
        sb.append("<start_of_turn>user\n")

        // System / Persona Configuration
        sb.append("You are an expert Android UI Automation Agent.\n")
        sb.append("Your goal is to fulfill the user's request by selecting the CORRECT action on the given screen.\n")
        sb.append("Analyze the screen layout and choose the single most logical element to interact with.\n\n")

        // User Context
        sb.append("=== USER GOAL ===\n")
        sb.append("Task: ${goal.task ?: "unknown"}\n")
        sb.append("Query: ${goal.query ?: "none"}\n")
        sb.append("Raw command: ${goal.rawCommand}\n\n")

        // UI Context
        sb.append("=== SCREEN ELEMENTS ===\n")
        if (uiState.elements.isEmpty()) {
            sb.append("No interactable elements found on the screen.\n")
        } else {
            uiState.elements.forEachIndexed { index, el ->
                sb.append("[$index] ")
                
                // Element attributes helping the LLM deduce function
                if (el.isEditable) sb.append("(Editable) ")
                if (el.isClickable) sb.append("(Clickable) ")
                if (el.isScrollable) sb.append("(Scrollable) ")
                
                // Type
                sb.append("[${el.type}] ")
                
                // Element Text content
                val contentText = el.text?.ifBlank { null } ?: "no text"
                sb.append("\"").append(contentText.replace("\n", " ").trim()).append("\"\n")
            }
        }

        // Schema Rules
        sb.append("\n=== OUTPUT RULES ===\n")
        sb.append("1. Write a brief REASONING paragraph assessing the current screen state. Only plan the immediate next step.\n")
        sb.append("2. Output EXACTLY ONE valid JSON object inside a ```json block.\n\n")
        sb.append("Allowed actions: CLICK, INPUT_TEXT, SCROLL_DOWN, SCROLL_UP, FINISH.\n\n")

        sb.append("EXAMPLE 1 - Clicking a button or search bar:\n")
        sb.append("REASONING: I need to click the search button to open the search bar.\n")
        sb.append("```json\n{\"action\": \"CLICK\", \"element_index\": 5, \"text_to_type\": null}\n```\n\n")
        
        sb.append("EXAMPLE 2 - Typing text:\n")
        sb.append("REASONING: The search box is focused. I will type the query.\n")
        sb.append("```json\n{\"action\": \"INPUT_TEXT\", \"element_index\": 2, \"text_to_type\": \"one piece intro\"}\n```\n\n")

        sb.append("EXAMPLE 3 - Task finished:\n")
        sb.append("REASONING: The video is playing. The goal is complete.\n")
        sb.append("```json\n{\"action\": \"FINISH\", \"element_index\": -1, \"text_to_type\": null}\n```\n")
        
        // Gemma Chat Template suffix
        sb.append("<end_of_turn>\n<start_of_turn>model\n")
        sb.append("REASONING:")

        return sb.toString()
    }
}
