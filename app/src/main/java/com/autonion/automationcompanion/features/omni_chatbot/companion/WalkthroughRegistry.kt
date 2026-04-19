package com.autonion.automationcompanion.features.omni_chatbot.companion

import com.autonion.automationcompanion.ui.AutomationRoutes

/**
 * Registry of predefined walkthrough scripts for every major feature.
 *
 * Each script contains ordered steps that the Companion uses to guide
 * the user through the feature.  Steps can navigate to a route,
 * describe what the user is seeing, or ask the user to perform an action.
 */
object WalkthroughRegistry {

    /**
     * Retrieve a walkthrough script by its feature ID.
     * Returns null if no script exists for the given ID.
     */
    fun getScript(featureId: String): WalkthroughScript? =
        scripts[featureId]

    /** All registered scripts, keyed by feature ID. */
    private val scripts: Map<String, WalkthroughScript> = mapOf(

        // ───────────────────────────────────────────────
        //  Flow Builder
        // ───────────────────────────────────────────────
        "flow_builder" to WalkthroughScript(
            featureId = "flow_builder",
            featureName = "Flow Builder",
            description = "Build visual automation flows with drag-and-drop nodes.",
            steps = listOf(
                WalkthroughStep(
                    instruction = "Let me take you to the Flow Builder. Opening it now…",
                    targetRoute = AutomationRoutes.FLOW_BUILDER,
                    stepType = StepType.NAVIGATE
                ),
                WalkthroughStep(
                    instruction = "Welcome to the Flow Builder! Here you can see all your saved flows. " +
                            "You can tap any flow card to edit it, or use the ▶ button to run it.",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "To create a new flow, tap the \"+\" button at the bottom right, " +
                            "or if this is your first flow, tap \"Create Flow\".",
                    stepType = StepType.ACTION,
                    highlightHint = "Create Flow / + FAB"
                ),
                WalkthroughStep(
                    instruction = "You're now in the Flow Editor — an infinite canvas where you design your automation. " +
                            "You can pinch to zoom and drag to pan around the canvas.",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "Tap the purple \"+\" button at the bottom right to open the Node Palette. " +
                            "This is where you pick the type of action your flow will perform.",
                    stepType = StepType.ACTION,
                    highlightHint = "+ (Add Node) FAB"
                ),
                WalkthroughStep(
                    instruction = "Choose a node type: Gesture (tap/swipe), Launch App, Visual Trigger (image match), " +
                            "Screen ML (AI-based), Delay, or Condition. Tap one to add it to the canvas.",
                    stepType = StepType.ACTION,
                    highlightHint = "Node Palette"
                ),
                WalkthroughStep(
                    instruction = "Tap a node on the canvas to configure it. " +
                            "You can connect nodes by tapping the output port (right side) of one node, " +
                            "then tapping the target node to create an edge.",
                    stepType = StepType.ACTION,
                    highlightHint = "Node output port"
                ),
                WalkthroughStep(
                    instruction = "When your flow is ready, tap the green ▶ button to execute it! " +
                            "The editor will show which node is currently running. " +
                            "You can tap the red ■ button to stop at any time.",
                    stepType = StepType.ACTION,
                    highlightHint = "Play / Stop FAB"
                ),
                WalkthroughStep(
                    instruction = "That's the basics of Flow Builder! You can also import/export flows " +
                            "using the file icon in the top bar. Happy automating! 🎉",
                    stepType = StepType.OBSERVE
                )
            )
        ),

        // ───────────────────────────────────────────────
        //  Gesture Recording & Playback
        // ───────────────────────────────────────────────
        "gesture_recording" to WalkthroughScript(
            featureId = "gesture_recording",
            featureName = "Gesture Recording",
            description = "Record taps, swipes, and complex gestures for automated playback.",
            steps = listOf(
                WalkthroughStep(
                    instruction = "Opening Gesture Recording & Playback for you…",
                    targetRoute = AutomationRoutes.GESTURE,
                    stepType = StepType.NAVIGATE
                ),
                WalkthroughStep(
                    instruction = "This is the Presets screen. Each preset is a saved sequence of recorded gestures " +
                            "(taps, swipes, scrolls) that you can replay on demand.",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "Tap \"+ New Preset\" to create a new gesture recording. " +
                            "Give it a descriptive name like \"Scroll Instagram\" or \"Like Posts\".",
                    stepType = StepType.ACTION,
                    highlightHint = "New Preset button"
                ),
                WalkthroughStep(
                    instruction = "After naming your preset, a floating overlay will appear on your screen. " +
                            "Use the overlay controls to start recording — every tap and swipe you make will be captured!",
                    stepType = StepType.ACTION,
                    highlightHint = "Overlay Record button"
                ),
                WalkthroughStep(
                    instruction = "When you're done recording, tap the stop button on the overlay. " +
                            "Your gesture sequence is saved automatically. To play it back, just tap ▶ on the preset card.",
                    stepType = StepType.ACTION,
                    highlightHint = "Play button"
                ),
                WalkthroughStep(
                    instruction = "That's it! You can create multiple presets for different tasks. " +
                            "Presets can also be used as nodes inside Flow Builder for complex automations. 🎯",
                    stepType = StepType.OBSERVE
                )
            )
        ),

        // ───────────────────────────────────────────────
        //  Semantic AI Agent
        // ───────────────────────────────────────────────
        "semantic_automation" to WalkthroughScript(
            featureId = "semantic_automation",
            featureName = "Semantic AI Agent",
            description = "An AI-powered agent that understands and executes complex on-device tasks.",
            steps = listOf(
                WalkthroughStep(
                    instruction = "Taking you to the Semantic AI Agent screen…",
                    targetRoute = AutomationRoutes.SEMANTIC_AUTOMATION,
                    stepType = StepType.NAVIGATE
                ),
                WalkthroughStep(
                    instruction = "This is the AI Agent chat interface. Here you describe a task in natural language, " +
                            "and the AI will autonomously navigate your phone to complete it.",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "First, make sure you have an AI model connected. " +
                            "Tap the ⚙ (Settings) icon in the top right to open the Model Manager " +
                            "and connect to your Ollama server or local SLM.",
                    stepType = StepType.ACTION,
                    highlightHint = "Settings icon"
                ),
                WalkthroughStep(
                    instruction = "Now type a command in the input bar at the bottom. For example: " +
                            "\"search for shoes on Flipkart\" or \"open YouTube and play music\". " +
                            "The more specific your command, the better the results!",
                    stepType = StepType.ACTION,
                    highlightHint = "Input bar"
                ),
                WalkthroughStep(
                    instruction = "After you send a command, watch the Live Status card — it shows " +
                            "what the agent is doing in real time: parsing your goal, capturing the screen, " +
                            "deciding the next action, and executing it. You can toggle this card with the 👁 icon.",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "If the agent needs clarification, it will ask you a question with " +
                            "clickable options. You can also stop the agent at any time using the ■ Stop button " +
                            "in the status card.",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "That's the Semantic AI Agent! It's the most powerful automation tool — " +
                            "it can handle multi-step, cross-app tasks using AI-driven screen understanding. 🤖",
                    stepType = StepType.OBSERVE
                )
            )
        ),

        // ───────────────────────────────────────────────
        //  Cross-Device Automation
        // ───────────────────────────────────────────────
        "cross_device" to WalkthroughScript(
            featureId = "cross_device",
            featureName = "Cross-Device Automation",
            description = "Control your desktop from your phone and create cross-device automation rules.",
            steps = listOf(
                WalkthroughStep(
                    instruction = "Opening Cross-Device Automation…",
                    targetRoute = AutomationRoutes.CROSS_DEVICE,
                    stepType = StepType.NAVIGATE
                ),
                WalkthroughStep(
                    instruction = "This screen has multiple tabs. The \"Chat\" tab lets you send direct commands " +
                            "to your desktop. The \"Desktop\" tab lets you create URL-based automation rules.",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "First, go to the \"Devices\" tab and make sure your desktop is connected. " +
                            "Your desktop needs to be running the Autonion Desktop Agent and connected to the same network.",
                    stepType = StepType.ACTION,
                    highlightHint = "Devices tab"
                ),
                WalkthroughStep(
                    instruction = "Once connected, switch to the \"Chat\" tab. Type a command like " +
                            "\"open Chrome\" or \"search for flights on Google\" and tap Send. " +
                            "The desktop agent will execute it!",
                    stepType = StepType.ACTION,
                    highlightHint = "Chat tab + input bar"
                ),
                WalkthroughStep(
                    instruction = "The \"Desktop\" tab lets you create automation rules. " +
                            "Tap \"New Rule\" to create rules like: " +
                            "\"When a meeting URL is detected → enable DND on phone.\"",
                    stepType = StepType.ACTION,
                    highlightHint = "New Rule button"
                ),
                WalkthroughStep(
                    instruction = "Clipboard sync is automatic — anything you copy on one device " +
                            "appears on the other! Text and images are synced seamlessly. 📋",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "That covers Cross-Device Automation! Your phone and desktop " +
                            "work together as one unified workspace. 🔗",
                    stepType = StepType.OBSERVE
                )
            )
        ),

        // ───────────────────────────────────────────────
        //  Visual Trigger
        // ───────────────────────────────────────────────
        "visual_trigger" to WalkthroughScript(
            featureId = "visual_trigger",
            featureName = "Visual Trigger",
            description = "Trigger automations when a specific image or UI element appears on screen.",
            steps = listOf(
                WalkthroughStep(
                    instruction = "Opening Visual Trigger Automation…",
                    targetRoute = AutomationRoutes.VISUAL_TRIGGER,
                    stepType = StepType.NAVIGATE
                ),
                WalkthroughStep(
                    instruction = "Visual Triggers let you automate actions based on what appears on your screen. " +
                            "For example: \"When a specific pop-up appears, tap Close automatically.\"",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "Tap \"Create Trigger\" to set up a new visual trigger. " +
                            "You'll first capture a screenshot of the target element you want to detect.",
                    stepType = StepType.ACTION,
                    highlightHint = "Create Trigger button"
                ),
                WalkthroughStep(
                    instruction = "Draw a selection box around the element you want to monitor. " +
                            "This creates a template image that the system will continuously scan for.",
                    stepType = StepType.ACTION,
                    highlightHint = "Selection area"
                ),
                WalkthroughStep(
                    instruction = "Next, configure what action to take when the trigger fires — " +
                            "you can tap, swipe, launch an app, or trigger a Flow. " +
                            "Set the confidence threshold and scanning interval as needed.",
                    stepType = StepType.ACTION,
                    highlightHint = "Action configuration"
                ),
                WalkthroughStep(
                    instruction = "Visual Triggers are great for handling repetitive pop-ups, " +
                            "auto-accepting prompts, or reacting to specific screen states. " +
                            "They're also available as nodes in Flow Builder! 👁",
                    stepType = StepType.OBSERVE
                )
            )
        ),

        // ───────────────────────────────────────────────
        //  System Context Automation
        // ───────────────────────────────────────────────
        "system_context" to WalkthroughScript(
            featureId = "system_context",
            featureName = "System Context",
            description = "Automate actions based on battery, WiFi, time of day, or location.",
            steps = listOf(
                WalkthroughStep(
                    instruction = "Opening System Context Automation…",
                    targetRoute = AutomationRoutes.SYSTEM_CONTEXT,
                    stepType = StepType.NAVIGATE
                ),
                WalkthroughStep(
                    instruction = "System Context automations trigger based on your phone's state: " +
                            "battery level, WiFi connection, time of day, or location.",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "Each card shows a different context category. " +
                            "Tap one to configure rules — for example, " +
                            "\"When battery drops below 20%, enable power saver mode.\"",
                    stepType = StepType.ACTION,
                    highlightHint = "Context category cards"
                ),
                WalkthroughStep(
                    instruction = "You can combine multiple conditions and assign actions from the action picker. " +
                            "Rules run automatically in the background once enabled.",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "System Context automations run silently in the background, " +
                            "keeping your phone optimized without manual intervention. ⚙️",
                    stepType = StepType.OBSERVE
                )
            )
        ),

        // ───────────────────────────────────────────────
        //  Automation Debugger
        // ───────────────────────────────────────────────
        "debugger" to WalkthroughScript(
            featureId = "debugger",
            featureName = "Automation Debugger",
            description = "View logs, diagnose errors, and debug your automations.",
            steps = listOf(
                WalkthroughStep(
                    instruction = "Opening the Automation Debugger…",
                    targetRoute = AutomationRoutes.DEBUGGER,
                    stepType = StepType.NAVIGATE
                ),
                WalkthroughStep(
                    instruction = "The Debugger shows logs from all your automation modules — " +
                            "Gesture, Visual Trigger, Flow Builder, AI Agent, and more.",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "Tap a category to see detailed logs for that module. " +
                            "Errors are highlighted in red, warnings in orange, and successes in green.",
                    stepType = StepType.ACTION,
                    highlightHint = "Category cards"
                ),
                WalkthroughStep(
                    instruction = "Use the debugger when an automation isn't working as expected — " +
                            "you can see exactly what went wrong and at which step. " +
                            "This is your go-to tool for troubleshooting! 🔍",
                    stepType = StepType.OBSERVE
                )
            )
        )
    )
}
