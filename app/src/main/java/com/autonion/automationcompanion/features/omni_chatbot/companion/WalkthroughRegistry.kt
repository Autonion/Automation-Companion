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
     * Aliases so LLM-generated IDs always resolve to the canonical key.
     * E.g. the LLM might emit "screen_understanding" but our key is "screen_ml".
     */
    private val aliases: Map<String, String> = mapOf(
        "screen_understanding"  to "screen_ml",
        "screen_context"        to "screen_ml",
        "UI_RECOGNITION_AI"     to "screen_ml",
        "image_checker"         to "visual_trigger",
        "vision_trigger"        to "visual_trigger",
        "cross_device_sync"     to "cross_device",
        "cross_device_automation" to "cross_device",
        "desktop_agent"         to "cross_device",
        "ai_agent"              to "semantic_automation",
        "gesture_playback"      to "gesture_recording",
        "automation_debugger"   to "debugger"
    )

    /**
     * Retrieve a walkthrough script by its feature ID.
     * Normalises the ID via [aliases] first, so slight LLM variations still resolve.
     */
    fun getScript(featureId: String): WalkthroughScript? {
        val canonicalId = aliases[featureId] ?: featureId
        return scripts[canonicalId]
    }

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
        //  UI Recognition AI (Screen ML)
        // ───────────────────────────────────────────────
        "screen_ml" to WalkthroughScript(
            featureId = "screen_ml",
            featureName = "UI Recognition AI",
            description = "AI-powered screen understanding using YOLO object detection and OCR.",
            steps = listOf(
                WalkthroughStep(
                    instruction = "Opening UI Recognition AI for you…",
                    targetRoute = AutomationRoutes.SCREEN_UNDERSTAND,
                    stepType = StepType.NAVIGATE
                ),
                WalkthroughStep(
                    instruction = "This is UI Recognition AI — it uses YOLO object detection " +
                            "and OCR to understand what's on your screen in real time. " +
                            "It can identify buttons, text fields, icons, and read text from any app.",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "Tap \"Start Scan\" to capture your current screen. " +
                            "The AI will analyze it and highlight all detected UI elements " +
                            "with bounding boxes and labels.",
                    stepType = StepType.ACTION,
                    highlightHint = "Start Scan button"
                ),
                WalkthroughStep(
                    instruction = "Each detected element shows its type (button, text, icon) and " +
                            "any text content found via OCR. You can tap an element to see " +
                            "its details and coordinates.",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "UI Recognition AI works with any app — it doesn't rely on " +
                            "the accessibility tree, so it's perfect for apps that block " +
                            "standard automation. It's also available as a node in Flow Builder! 🧠",
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
        //  Semantic Automation
        // ───────────────────────────────────────────────
        "semantic_automation" to WalkthroughScript(
            featureId = "semantic_automation",
            featureName = "Semantic Automation",
            description = "Natural language automation that carries out multi-step tasks on your device.",
            steps = listOf(
                WalkthroughStep(
                    instruction = "Taking you to the Semantic Automation screen…",
                    targetRoute = AutomationRoutes.SEMANTIC_AUTOMATION,
                    stepType = StepType.NAVIGATE
                ),
                WalkthroughStep(
                    instruction = "This is the Semantic Automation interface. Here you describe a task in natural language, " +
                            "and Autonion will carry out the steps to complete your task.",
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
                            "the process in real time: parsing your goal, inspecting the screen, " +
                            "and performing the requested steps. You can toggle this card with the 👁 icon.",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "If clarification is needed, a prompt will appear with " +
                            "clickable options. You can also stop the process at any time using the ■ Stop button " +
                            "in the status card.",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "That's Semantic Automation! It's a powerful natural language tool — " +
                            "helping you complete multi-step tasks using screen understanding. 💡",
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
            description = "Control your desktop from your phone with secure OTP pairing, remote flows, and cross-device automation rules.",
            steps = listOf(
                WalkthroughStep(
                    instruction = "Opening Cross-Device Automation…",
                    targetRoute = AutomationRoutes.CROSS_DEVICE,
                    stepType = StepType.NAVIGATE
                ),
                WalkthroughStep(
                    instruction = "First, make sure you have the Autonion Desktop Agent installed on your PC. " +
                            "Download it from: github.com/Autonion/Autonion-Agent/releases — " +
                            "extract and run the installer.",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "For browser automation on desktop, also install the Autonion Extension " +
                            "from: github.com/Autonion/Autonion-Extension/releases — " +
                            "download the ZIP, extract it, open Chrome → chrome://extensions → " +
                            "enable Developer mode → Load unpacked.",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "This screen has four tabs: \"Devices\" for pairing and managing connections, " +
                            "\"Rules\" for creating automation triggers, " +
                            "\"Flows\" for remotely triggering Desktop flows, " +
                            "and \"Ask\" for sending natural language commands to your desktop.",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "Go to the \"Devices\" tab. Make sure the Desktop Agent is running " +
                            "and both devices are on the same WiFi network. " +
                            "Your desktop should appear automatically via mDNS discovery.",
                    stepType = StepType.ACTION,
                    highlightHint = "Devices tab"
                ),
                WalkthroughStep(
                    instruction = "Tap your desktop's name to start pairing. A 6-digit PIN will appear " +
                            "on the Desktop Agent screen. Enter this PIN here within 120 seconds " +
                            "to securely pair the devices. Future connections will be automatic! 🔐",
                    stepType = StepType.ACTION,
                    highlightHint = "Device card"
                ),
                WalkthroughStep(
                    instruction = "Once paired, switch to the \"Ask\" tab. Type a command like " +
                            "\"open Chrome\" or \"search for flights on Google\" and tap Send. " +
                            "The Desktop Agent will execute it on your PC!",
                    stepType = StepType.ACTION,
                    highlightHint = "Ask tab + input bar"
                ),
                WalkthroughStep(
                    instruction = "The \"Rules\" tab lets you create automation trigger rules. " +
                            "Tap \"New Rule\" to create rules like: " +
                            "\"When a meeting URL is detected → enable DND on phone.\"",
                    stepType = StepType.ACTION,
                    highlightHint = "New Rule button"
                ),
                WalkthroughStep(
                    instruction = "The \"Flows\" tab shows automation workflows saved on your Desktop Agent. " +
                            "You can trigger, monitor, and stop these flows remotely from your phone! " +
                            "Real-time progress updates show you which step is executing. 🔀",
                    stepType = StepType.ACTION,
                    highlightHint = "Flows tab"
                ),
                WalkthroughStep(
                    instruction = "You can even unlock your PC remotely! If the Desktop Agent has an " +
                            "Unlock flow, you can trigger it from the Flows tab — it works even " +
                            "when your PC is on the lock screen (pre-login mode). 🔓",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "Clipboard sync is automatic — any text you copy on one device " +
                            "appears on the other! Only text is synced over your local WiFi. 📋",
                    stepType = StepType.OBSERVE
                ),
                WalkthroughStep(
                    instruction = "Tap the remote icon in the top bar to open Hardware Remote. " +
                            "Map your phone's volume buttons to send keyboard commands to your PC — " +
                            "single tap, double tap, and long press each get their own mapping. " +
                            "It even works with your screen off! 🎮",
                    stepType = StepType.ACTION,
                    highlightHint = "Remote icon"
                ),
                WalkthroughStep(
                    instruction = "That covers Cross-Device Automation! Your phone and desktop " +
                            "work together as one unified workspace with secure OTP pairing, " +
                            "remote flows, desktop unlock, hardware remote, and clipboard sync. 🔗",
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
                            "Gesture, Visual Trigger, Flow Builder, Semantic Automation, and more.",
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
