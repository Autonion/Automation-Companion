# Autonion Android App Guide

## Overview

Autonion Automation Companion is an Android application that enables intelligent device automation through natural language commands, gesture recording, cross-device control, and AI-powered task execution. It runs entirely locally with no cloud dependencies - all AI inference happens on your local network via Ollama, and all cross-device communication stays on your local WiFi.

## Feature Summary

Autonion has 8 core features. Here is a complete list:

1. Omni-Chat (Unified Chatbot Interface) - The main interaction point. Accessible via the floating action button (FAB). Routes commands to the appropriate engine using on-device NLU.
2. Semantic Automation (AI-Powered Agent) - Autonomous multi-step task execution powered by LLM. Uses an agentic loop to interact with device UI.
3. Cross-Device Automation - Send commands from Android to a desktop computer. Requires the Autonion Desktop Agent app to be installed on the target device. Source code is available at github.com/Autonion/Autonion-Agent.
4. Gesture Recording and Playback - Record touch interactions (taps, swipes, long presses, drags) and replay them automatically. Coordinate-based replay.
5. Flow Builder - Visual drag-and-drop automation workflow builder. Create flows with triggers, actions, conditions, and connections.
6. System Context Automation - Automations that respond to system-level context changes including location (geofence), time schedules, battery percentage levels, WiFi connectivity changes, and app-specific automations that trigger when a specific app is opened or closed.
7. Visual Trigger Automation - Screen-pattern-based automations that use image matching to detect specific visual elements on screen and trigger actions automatically. This is the feature for triggering automation based on images.
8. Screen Context AI (Screen ML) - AI-powered screen understanding using YOLO object detection and OCR (Optical Character Recognition). Detects UI elements dynamically, handles UI changes, and can automate clicks and interactions based on recognized text and visual elements.

## Core Architecture

Autonion uses a layered architecture:
- NLU (Natural Language Understanding) layer classifies every user input into an intent type
- Each intent routes to a dedicated engine (direct actions, semantic agent, cross-device, FAQ, RAG knowledge)
- The Accessibility Service provides system-level control over the Android UI
- An optional Python Bridge on desktop handles system-level desktop actions
- A browser extension bridge enables web page DOM interaction for browser automation

## Features

### Omni-Chat (Unified Chatbot Interface)
The Omni-Chat is the main interaction point. It is accessible via the floating action button (FAB) on any screen in the app.

How Omni-Chat works:
1. Tap the glowing FAB button (bottom-right of any screen) to open the chat.
2. Type a command or question in the input field.
3. The on-device NLU Intent Classifier automatically routes your input to the right engine.
4. You see real-time status updates with mode indicator badges showing which engine handled the request.

Omni-Chat modes and what they mean:
- Direct (target icon): Instant key presses or text input, no AI needed. Example: "press enter", "type hello".
- Agent (robot icon): AI-powered automation via the Semantic Engine. Example: "search shoes on Flipkart".
- Desktop (link icon): Command sent to connected desktop computer. Example: "on my laptop open chrome".
- Timer (clock icon): Scheduled or recurring task with stop control. Example: "click next every 1 minute".
- FAQ (lightbulb icon): Instant answer from the built-in FAQ database, no LLM required. Example: "how do I connect devices?".
- Knowledge (book icon): Answer synthesized from documentation using RAG (Retrieval-Augmented Generation). Example: "explain how the agentic loop works".
- Chat (speech bubble icon): General conversational LLM response.
- System (gear icon): Error or system notification messages.

Forcing a specific device:
- Type /android followed by your command to force execution on the Android device.
- Type /desktop followed by your command to force execution on the desktop.
- Without a prefix, the NLU classifier decides automatically.

Contextual FAQ chips:
- When you open Omni-Chat, suggested question chips appear at the top based on which screen you are on.
- On the home screen you see general tips. On the Semantic Automation screen you see AI-related tips. On Cross-Device you see connection tips. On Flow Builder you see workflow tips.

FAQ Browser:
- Tap the book icon in the chat header to open the FAQ Browser.
- Browse all 150+ frequently asked questions organized with tags.
- Tap any question to see its answer instantly without needing an LLM.

LLM settings inside Omni-Chat:
- Tap the gear icon in the chat header to open in-chat settings.
- You can connect to your Ollama server by entering the IP address.
- You can switch between available models.
- You can toggle between Server LLM and Local SLM inference modes.
- The connection auto-reconnects when the chat is reopened.

### Semantic Automation (AI-Powered Agent)
The AI-powered automation engine that understands natural language commands and executes complex multi-step tasks on your device autonomously.

How the Semantic Automation Engine works in detail:
1. Goal Parsing: Your natural language command (e.g., "search for shoes under 2000 on Flipkart") is sent to the LLM. The LLM extracts structured data: the task type (search, open, enable, etc.), the target app (Flipkart), the search query (shoes under 2000), and the domain (flipkart.com).
2. Pre-Actions: The engine launches the target app or opens system settings. If the target app is not installed, a dialog asks you to choose between Play Store, Browser fallback, or Cancel.
3. Screen Loop (the Agentic Loop): This is the core automation cycle that repeats up to 50 iterations:
   - Step A: Capture the current screen via screenshot.
   - Step B: Build a ScreenUIState from the accessibility tree. Elements include buttons, text fields, labels with their names, types, and bounding boxes. When a browser with the extension is active, DOM elements are used instead.
   - Step C: Compare with the previous UI state for post-action verification. If the screen has not changed after an action, it counts as a failure.
   - Step D: Predict the next action using a multi-tier fallback system (see Action Prediction below).
   - Step E: Execute the predicted action (click, type, scroll, press key, finish).
   - Step F: Wait 2.5 seconds for the screen to settle, then repeat.
4. Completion: The loop stops when the LLM predicts a FINISH action, when the user cancels, or when max iterations (50) are reached.

Action prediction (multi-tier fallback):
The engine tries these prediction tiers in order until one succeeds:
- Tier 0: Deterministic Task Planner - handles standard flows (search, play) without the LLM. Reserved for common patterns.
- Tier 1 (Server LLM mode): Ollama server via REST API. Sends a system prompt, user prompt with screen elements, and step history. Receives structured JSON with the action type, target element index, and input text.
- Tier 1 (Local SLM mode): On-device Gemma 2B model. Same logic but runs directly on phone. Slower and less accurate than server LLM.
- Tier 2: TFLite ML Model. A lightweight classification model for fast predictions. Biased toward common actions.
- Tier 3: Rule-based heuristic fallback. Looks for keyword matches in element names (e.g., "search" -> click on search bar).

Safety features:
- Anti-loop detection prevents the engine from typing the same text twice. Instead it will press Enter to submit.
- Wrong-app detection checks if the foreground app matches the target. If not, it presses Back to return.
- Maximum consecutive failure limit (3 failures) triggers an escape scroll to try to recover.
- Maximum iteration limit (50) prevents infinite loops.
- Users can stop automation anytime via the Stop button in Omni-Chat or the notification.

Supported command types:
- Open apps: "open settings", "launch YouTube"
- Search in apps: "search for shoes under 2000 on Flipkart", "find restaurants on Zomato"
- Toggle settings: "turn off wifi", "enable bluetooth", "disable do not disturb"
- Play media: "play music on Spotify"
- Navigate within apps: "go to Instagram"
- Multi-step tasks: "open WhatsApp and send message to Mom"
- Web tasks: "search shoes on Amazon" (uses browser + extension)

Requirements for Semantic Automation:
- The Accessibility Service must be enabled in Android Settings > Accessibility > Autonion
- An Ollama server must be running on your local network for AI-powered commands
- A compatible browser with the Autonion extension is needed for web automation tasks (Kiwi Browser, Lemur Browser, or Firefox Nightly are supported)

### NLU Intent Classifier (How Commands Are Routed)
The Intent Classifier is the brain that decides what to do with every user message. It runs entirely on-device with no network calls.

Classification tiers:
1. Fast Heuristic Check (about 1ms): Uses regex patterns to detect obvious commands like key presses ("press enter"), toggles ("turn on wifi"), schedules ("click next every 1 minute"), cross-device commands ("on my laptop open chrome"), and questions ("how do I...").
2. Semantic Embedding Match (about 10ms): If heuristics don't match, the classifier uses MiniLM sentence embeddings to compare the user input against canonical example phrases for each intent type. The intent with the highest cosine similarity above 0.55 threshold wins.
3. Validation Gate: For DEVICE_AUTOMATION and DIRECT_TOGGLE intents, a validation gate checks whether the prompt has actionable content (a target app, a toggle target, or a task verb in command form). If the prompt is phrased as a question rather than a command, it reclassifies to Q_AND_A. This prevents questions like "how does bluetooth work" from triggering system toggles.

Intent types:
- DIRECT_KEY_ACTION: Press a specific key or type text. Examples: "press enter", "type hello world"
- DIRECT_TOGGLE: Turn a system setting on or off. Examples: "turn off wifi", "enable bluetooth"
- SCHEDULED_ACTION: Repeat an action on a timer. Examples: "click next every 1 minute", "press space 5 times"
- DEVICE_AUTOMATION: Complex on-device task requiring the AI agent. Examples: "search shoes on Flipkart", "open settings and enable dark mode"
- CROSS_DEVICE: Command targeted at the desktop computer. Examples: "on my laptop open chrome", "on desktop open notepad"
- FAQ: Question that matches the FAQ database. Detected via semantic similarity.
- Q_AND_A: General knowledge question answered via RAG or LLM. Examples: "what features does this app have?", "how to set up Ollama?"

### Cross-Device Automation
Send commands from your Android device to your desktop computer and receive real-time responses. Cross-device automation requires a separate companion app called the Autonion Desktop Agent (also known as Autonion-Agent) to be installed and running on the target computer. You must download and install the Autonion Desktop Agent from github.com/Autonion/Autonion-Agent. Without the Desktop Agent running on the target computer, cross-device automation will not work.

How cross-device communication works:
1. Discovery: The Desktop Agent broadcasts its presence on the local network using mDNS (Bonjour service type: _autonion._tcp). The Android app scans for this service automatically.
2. Connection: A WebSocket connection is established between the devices on port 4545 (or a dynamic fallback port). The WebSocket endpoint is ws://<IP>:4545/automation.
3. Command routing: When you send a command, the Android NLU classifies the intent. If it detects cross-device keywords ("on my laptop", "on desktop", "on my pc"), the command is sent to the Desktop Agent via WebSocket.
4. Two-way feedback: The Desktop Agent sends status updates back to Android in real-time: started, in_progress (with step descriptions), completed, failed, scheduled, or cancelled. These appear as messages in Omni-Chat.
5. Transaction tracking: Every command gets a unique transaction ID. This ensures status updates are matched to the right command and duplicate responses are filtered out.

Cross-device features:
- Natural language commands sent to the desktop and executed by the Desktop Agent
- Clipboard sync between devices. Text copied on one device automatically appears on the other. Only text is supported, not images or files. Works only over local WiFi.
- Rule-based automation triggers. You can register trigger rules from Android that execute on the Desktop when conditions are met.
- Scheduled/recurring actions. Example: "press next every 30 seconds on desktop" starts a timer on the Desktop that executes the key press repeatedly.
- Structured commands. For simple key presses, the command is sent with structured data (key name, key code) so the Desktop can execute it directly without LLM involvement. This is faster and more reliable.

Setup steps:
1. Download and install the Autonion Desktop Agent from github.com/Autonion/Autonion-Agent on your Windows PC (Flutter-based app, also called Autonion-Agent).
2. Run the Desktop Agent on your PC. Make sure both your Android device and PC are on the same WiFi network.
3. Open Cross-Device Automation in the Android app.
4. The desktop should appear automatically via mDNS discovery within a few seconds.
5. Tap the device name to connect. You will see a "Connected" status.
6. If auto-discovery fails, tap manual entry and type the IP address and port shown in the Desktop Agent window.

### Gesture Recording and Playback
Record touch interactions on your screen and replay them automatically.

How it works:
1. Open Gesture Recording from the home screen.
2. Tap "Record" to start. A transparent overlay appears on your screen.
3. Perform your touch gestures: taps, swipes, long presses, drags.
4. Tap the overlay stop button when done.
5. Name your recording and save it.
6. To replay, select the saved recording and tap "Play".
7. The recorded gestures are replayed at the same coordinates and timing.

Important notes:
- Requires the Accessibility Service to be enabled
- Gestures are coordinate-based, meaning they will tap at the exact X,Y pixel positions. This works best if the screen layout has not changed since recording.
- Does not support screen rotation changes during replay
- Great for repetitive tasks like farming in games, filling out forms, or clicking through sequences

### Flow Builder
Create visual automation workflows using a drag-and-drop interface.

Components:
- Triggers: Events that start the flow (time-based, app launch, notification received, system event)
- Actions: Tasks to perform (open app, type text, click specific element, press key, run automation)
- Conditions: Logic gates that branch the flow (if/else based on screen content, system state, or variable values)
- Connections: Visual links between triggers, conditions, and actions that define the execution order

How to create a flow:
1. Open Flow Builder from the home screen.
2. Tap "+" to create a new flow.
3. Add a trigger block (defines when the flow starts).
4. Add action blocks (defines what happens).
5. Optionally add condition blocks for branching logic.
6. Connect blocks by dragging between their connection points.
7. Tap "Save" to save the flow.
8. Tap "Run" to execute immediately, or "Schedule" for automatic execution when the trigger fires.

Tip: Enable the Accessibility Service before running flows that interact with UI elements.

### System Context Automation
Automations that respond to system-level context changes. This feature allows you to set automations based on battery percentage, location, time, WiFi connectivity, and specific apps. If you want to automate something based on battery percentage, location, time of day, WiFi, or a specific app, System Context Automation is the feature to use.

Supported contexts and triggers:
- Battery level triggers: Run automations when battery reaches a certain percentage level. For example, enable power saving mode when battery drops to 20%, or send a notification when battery reaches 100%. You can set any battery percentage as a trigger threshold. This is how you automate actions based on battery percentage.
- Location-based triggers (geofence): Enter or exit a geographic area to start an automation. Example: Turn on WiFi when arriving home, turn off WiFi when leaving home. This is how you automate actions based on a specific location.
- Time-based triggers: Schedule automations to run at specific times or intervals. Example: Toggle DND at 10 PM every night.
- WiFi connectivity changes: Trigger automations when WiFi connects or disconnects. Example: Open a specific app when connected to office WiFi.
- App Specific Automation: Set automations that trigger when a specific app is opened or closed. You can configure actions to run automatically when you open a particular app. For example, enable Do Not Disturb when you open a gaming app, or start screen recording when you open a streaming app. This is how you set automation for a specific app.

### Visual Trigger Automation
Screen-pattern-based automations that watch for specific visual elements on screen. This is the feature for triggering automation based on images. Visual Triggers use image template matching to detect when a specific image pattern appears on screen and then automatically execute a configured action.

How it works:
1. Capture a screen region as a trigger pattern (take a screenshot and crop the area of interest).
2. The system continuously monitors the screen using template matching (image comparison).
3. When the matching pattern is detected on screen, the configured action executes automatically.
4. The matching is image-based, meaning it compares pixel patterns to find visual elements.

Use cases:
- Detecting notification badges on app icons and auto-opening the app
- Waiting for a specific loading screen to finish before proceeding
- Auto-clicking when a specific button or dialog appears on screen
- Monitoring for visual changes like new messages, alerts, or status changes
- Game automation: detecting specific game states or UI elements and responding

Important notes:
- Requires the Accessibility Service to be enabled for action execution
- The trigger pattern must be a clear, distinctive image region for reliable matching
- Works with any app or screen on the device
- Can be combined with other automations like Flow Builder for complex workflows

### Screen Context AI (Screen ML)
AI-powered screen understanding using machine learning models. This feature uses YOLO (You Only Look Once) object detection model and OCR (Optical Character Recognition) to understand and interact with screen content dynamically.

Key capabilities:
- YOLO object detection: Detects and classifies UI elements on screen in real-time using a trained YOLO model. Can identify buttons, text fields, images, icons, and other UI components even when the accessibility tree is unavailable or incomplete.
- OCR text recognition: Reads and recognizes text displayed on screen. Can automate clicks and interactions based on recognized text content. Useful for interacting with elements that lack accessibility labels.
- Dynamic UI handling: Handles UI changes in real-time. Unlike coordinate-based approaches (gesture recording), Screen ML adapts to layout changes because it visually detects elements rather than relying on fixed positions.
- Element detection: Identifies interactive elements visually, providing an alternative to the accessibility tree for element detection.

How Screen Context AI helps with automation:
- Can automate clicks on elements identified by OCR text recognition
- Provides visual element detection as a fallback when the accessibility tree does not expose certain elements
- Handles dynamic and changing UIs because it re-detects elements each time rather than using stored coordinates
- Works across all apps including those with custom or non-standard UI frameworks

Use cases:
- Automating clicks on text identified by OCR (e.g., click on a specific label or price)
- Detecting and interacting with UI elements in apps that have poor accessibility support
- Handling dynamic UIs that change layout frequently
- Supplementing the Semantic Automation engine with visual element detection

### Browser Automation (Extension Bridge)
For web browsing tasks, Autonion supports an extension-based approach:

How it works on Android:
1. A WebSocket server runs inside the Android app (ExtensionBridgeServer on port 54321).
2. A compatible browser with the Autonion extension connects to this server.
3. When the Semantic Engine detects a browser task, it launches the browser with the target URL.
4. The extension captures the webpage DOM (interactive elements, text, links, buttons).
5. The DOM elements are sent back to the Android app and presented to the LLM as numbered elements.
6. The LLM predicts actions (click element, type text, scroll) using element IDs from the DOM snapshot.
7. The action commands are sent back to the extension which executes them in the webpage.

Supported browsers with extensions: Kiwi Browser (recommended), Lemur Browser, Firefox Nightly.

If no supported browser is installed, the engine will prompt you to install one and offer options.

### Automation Debugger
View detailed, categorized logs of all automation activities.

Log categories:
- Accessibility Events: Raw accessibility service events (node clicks, text changes, window transitions)
- Cross-Device Sync: WebSocket communication logs (messages sent, received, connection status)
- Semantic Actions: AI agent action predictions and executions (LLM prompts, predicted actions, execution results, step history)
- System Events: App-level events and errors

How to use:
1. Go to Automation Debugger from the home screen.
2. Select a log category tab.
3. View entries with timestamps, severity levels (info, warning, error, success).
4. Tap any entry to see full details.
5. Use this to diagnose why an automation failed or behaved unexpectedly.

Tip: When reporting bugs or issues, check the Semantic Actions logs to see exactly what the LLM predicted and why the automation took unexpected steps.

### Knowledge Base and RAG System
Omni-Chat can answer questions about the app using a built-in knowledge system.

How it works:
1. Knowledge documents (like this guide) are loaded from the app assets at startup.
2. Documents are split into chunks and each chunk is embedded using MiniLM sentence embeddings.
3. When you ask a question, the system finds the most semantically similar knowledge chunks via cosine similarity search (vector search).
4. If an LLM is connected, the chunks are used as context for the LLM to generate a natural language answer.
5. If no LLM is connected, the raw knowledge chunk is shown directly as a fallback.

The FAQ Browser provides instant static answers for 150+ common questions. You can browse FAQs by tapping the book icon in the chat header. FAQ answers are returned instantly without needing an LLM.

## Setup Guide

### Initial Setup
1. Install the app from your preferred source (APK or build from source).
2. Grant the necessary permissions when prompted (Storage, Overlay).
3. Enable the Accessibility Service: Go to Android Settings > Accessibility > Autonion and toggle it on. This is required for screen reading, gesture automation, and action execution.
4. The Accessibility Service may disconnect due to battery optimization. See the Troubleshooting section below.

### Ollama Setup (for AI features)
Ollama is a local LLM server that powers all AI features. You need this for Semantic Automation and enhanced Q&A answers.

1. Install Ollama from https://ollama.ai on your PC (Windows, Mac, or Linux).
2. Open a terminal and pull a model: ollama pull qwen3.5:4b (fast, about 3GB) or ollama pull qwen3.5:7b (more accurate, about 4GB).
3. Ollama starts a REST API server automatically at http://localhost:11434.
4. In the Autonion Android app, open Omni-Chat and tap the gear icon.
5. Enter your PC's local IP address (e.g., 192.168.1.100). The app will auto-add the port and URL format.
6. Test the connection. You should see "Connected" and a list of available models.
7. Select the model you want to use from the dropdown.

Finding your PC's IP address:
- Windows: Open Command Prompt and type "ipconfig". Look for "IPv4 Address" under your WiFi adapter.
- Mac: Open System Settings > Network > WiFi > Details. Your IP is shown there.
- Linux: Run "hostname -I" in a terminal.

Important: Use your PC's local network IP (starts with 192.168.x.x or 10.x.x.x), not "localhost" or "127.0.0.1" because those refer to the phone itself.

### Recommended Model Settings
- For speed: qwen3.5:4b or qwen3:4b - responds in 2-5 seconds, handles most tasks
- For accuracy: qwen3.5:7b - responds in 5-15 seconds, better at complex multi-step tasks
- For vision: models with vision support can use screenshots alongside the accessibility tree
- Inference Mode: SERVER_LLM (uses Ollama) is recommended over LOCAL_SLM (on-device) for better quality

### Connecting to Desktop Agent
To use cross-device automation, you must install the Autonion Desktop Agent app on your computer. Download it from github.com/Autonion/Autonion-Agent.

1. Install the Autonion Desktop Agent on your Windows PC (download from github.com/Autonion/Autonion-Agent or build from Flutter source).
2. Run the Desktop Agent. It will show its WebSocket server port and local IP addresses.
3. Make sure both devices are on the same WiFi network.
4. In the Android app, go to Cross-Device Automation. The desktop should appear automatically.
5. If it does not appear, use manual IP entry with the address shown in the Desktop Agent window.

## Troubleshooting

### Automation does random or unexpected things
This is usually caused by the LLM making poor predictions:
- Try a larger model. Models with 7B parameters or more produce much better results than 3B models.
- Break complex commands into simpler steps. Instead of "open WhatsApp, find Mom's chat, and send her a birthday wish", try "open WhatsApp" first, then "search for Mom" separately.
- Close unnecessary apps to reduce screen UI noise. The fewer elements on screen, the easier it is for the LLM to identify the right target.
- Check the Automation Debugger > Semantic Actions to see exactly what the LLM predicted. This helps identify whether the issue is with element detection or action selection.
- Use Omni-Chat for simple commands (like "press next" or "turn off wifi") which bypass the AI agent entirely and execute instantly.

### Cannot connect to desktop
- Make sure the Autonion Desktop Agent (from github.com/Autonion/Autonion-Agent) is installed and running on your PC.
- Ensure both devices are on the same WiFi network. Mobile data or different networks will not work.
- Check if the Desktop Agent is running and shows a port number and IP address in its window.
- Try manual IP entry if mDNS auto-discovery does not find the desktop.
- Check Windows Firewall settings. The Desktop Agent needs to accept incoming connections.
- Make sure the WebSocket port (default 4545) is not blocked by your router or firewall.
- Try restarting both the Android app and the Desktop Agent.

### LLM server connection fails
- Verify Ollama is running: open http://localhost:11434 in your PC's web browser. You should see "Ollama is running."
- Use your PC's local IP address (192.168.x.x), not localhost, because localhost refers to the phone itself.
- Ensure no firewall is blocking port 11434 on your PC.
- Try restarting Ollama (close and reopen).
- Check that you pulled at least one model: run "ollama list" in a terminal.

### Accessibility Service keeps disconnecting or stopping
Android aggressively kills background services to save battery. To prevent this:
- Disable battery optimization for Autonion: Go to Android Settings > Apps > Autonion > Battery > Unrestricted.
- Enable "Auto-start" if your phone manufacturer provides it (common on Xiaomi, Oppo, Vivo, Samsung).
- Lock the app in recent apps: Open the app switcher, long-press the Autonion card, and tap "Lock" or the lock icon.
- On MIUI (Xiaomi): Also go to Settings > Additional Settings > Developer Options > MIUI Optimization and turn it off.

### Web automation does not work
- You need a compatible browser with the Autonion extension installed: Kiwi Browser (recommended), Lemur Browser, or Firefox Nightly.
- Make sure the extension is enabled in the browser's extension settings.
- If the extension cannot connect, check that the ExtensionBridgeServer port (54321) is not being used by another app.
- Try restarting the browser and reopening the page.

### Omni-Chat answers are not helpful
- Make sure your Ollama server is connected. Without an LLM, answers come from raw knowledge chunks which may not be as clear.
- Try rephrasing your question. The RAG system matches your question against knowledge documents using semantic similarity.
- For specific feature questions, use the FAQ Browser (book icon in chat header) to browse all available questions and answers instantly.
- If you get "I don't have information about that", the topic may not be covered in the knowledge base.

### Scheduled tasks do not run
- Make sure you include time information in your command. Example: "click next every 1 minute" or "press space every 30 seconds".
- For desktop scheduled tasks, both devices must stay connected via WebSocket.
- On Android, scheduled tasks run as coroutines in the app. If the app is killed, the schedule stops. Use the lock-in-recents technique.

### Clipboard sync is not working
- Both devices must be connected via the Cross-Device Automation feature.
- Clipboard sync only works for text content. Images and files are not supported.
- There can be a 1-2 second delay between copying and syncing.
- Check that the Desktop Agent is running and connected.

## Privacy and Security

Autonion is designed with privacy as a core principle:
- 100% Local Processing: All AI inference happens on your local network via Ollama. No data is sent to cloud servers.
- No Telemetry: The app does not collect or send any usage data, analytics, or crash reports to external servers.
- Local Network Only: Cross-device communication uses your local WiFi. No internet relay servers.
- No Account Required: No sign-up, login, or cloud account is needed.
- Accessibility Service Warning: The Accessibility Service has broad permissions and can read all screen content. Only enable it when actively using automations.
