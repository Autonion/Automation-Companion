# Autonion Android App Guide

## Overview

Autonion Automation Companion is an Android application that enables intelligent device automation through natural language commands, gesture recording, cross-device control, and AI-powered task execution. It supports three AI inference modes: Server LLM (via Ollama on your local network), On-Device SLM (GGUF models running locally on your phone), and Cloud API (OpenAI, Gemini, Groq, DeepSeek, Mistral, Together AI, OpenRouter, Ollama Cloud, or any OpenAI-compatible endpoint). Cross-device communication stays on your local WiFi with secure OTP-based device pairing.

## Feature Summary

Autonion has 8 core features. Here is a complete list:

1. Omni-Chat (Unified Chatbot Interface) - The main interaction point. Accessible via the floating action button (FAB). Routes commands to the appropriate engine using on-device NLU.
2. Semantic Automation (AI-Powered Agent) - Autonomous multi-step task execution powered by LLM. Uses an agentic loop to interact with device UI. Supports Server LLM (Ollama), On-Device SLM (GGUF), and Cloud API inference modes.
3. Cross-Device Automation - Send commands from Android to a desktop computer with secure OTP-based pairing. Includes remote Desktop Flow triggering, clipboard sync, and rule-based automation. Requires the Autonion Desktop Agent (github.com/Autonion/Autonion-Agent).
4. Gesture Recording and Playback - Record touch interactions (taps, swipes, long presses, drags) and replay them automatically. Coordinate-based replay.
5. Flow Builder - Visual drag-and-drop automation workflow builder. Create flows with triggers, actions, conditions, and connections. Includes Screen Understanding nodes with YOLO+UI attribute, UI attribute-only, and OCR modes.
6. System Context Automation - Automations that respond to system-level context changes including location (geofence), time schedules, battery percentage levels, WiFi connectivity changes, and app-specific automations that trigger when a specific app is opened or closed.
7. Visual Trigger Automation - Screen-pattern-based automations that use image matching to detect specific visual elements on screen and trigger actions automatically. This is the feature for triggering automation based on images.
8. Screen Understanding AI - AI-powered screen understanding with three modes: Elements mode (YOLO object detection + UI attributes), UI Attribute mode (accessibility-tree-only matching), and OCR mode (text recognition). Detects UI elements dynamically, handles UI changes, and can automate clicks and interactions based on recognized text and visual elements.

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
- Tier 1 (Cloud API mode): Cloud LLM via OpenAI-compatible API. Supports OpenAI (GPT-4o), Google Gemini, Groq, DeepSeek, Mistral, Together AI, OpenRouter, Ollama Cloud, or any custom OpenAI-compatible endpoint. Same prompt format as Server LLM.
- Tier 1 (Local SLM mode): On-device GGUF model (Qwen 2.5, Phi-3.5, Llama 3.2, Gemma 4, etc.) or legacy MediaPipe model. Runs directly on phone with no network. Slower than server/cloud but fully offline.
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
- At least one AI inference source must be configured: Server LLM (Ollama on your local network), Cloud API (any supported provider with an API key), or On-Device SLM (a downloaded GGUF model on your phone)
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
Send commands from your Android device to your desktop computer and receive real-time responses. Cross-device automation requires a separate companion app called the Autonion Desktop Agent (also known as Autonion-Agent) to be installed and running on the target computer. You must download and install the Autonion Desktop Agent from github.com/Autonion/Autonion-Agent/releases. Without the Desktop Agent running on the target computer, cross-device automation will not work.

Important: For browser-based cross-device tasks (such as web searches, opening websites, or interacting with web pages), the Autonion Extension is also essential. The Desktop Agent alone handles system-level actions (key presses, app launching, file operations), but web page DOM interaction requires the Autonion Extension installed in Chrome or Edge on the desktop. Without it, the system can only use the less-reliable Windows accessibility tree for web content. See the "Browser Automation" section below for installation steps.

How cross-device communication works:
1. Discovery: The Desktop Agent broadcasts its presence on the local network using mDNS (Bonjour service type: _autonion._tcp). The Android app scans for this service automatically.
2. OTP Pairing: When you tap a discovered device for the first time, a 6-digit PIN is displayed on the Desktop Agent screen. You must enter this PIN on your Android device to establish a trusted connection. The PIN expires after 120 seconds. This OTP-based pairing ensures only authorized devices can connect.
3. Trusted Connection: Once paired, the device is saved as a trusted companion. Future connections are automatic — no PIN is required again unless the pairing is revoked. Paired devices are stored with encrypted secrets.
4. WebSocket Link: A secure WebSocket connection is established between the devices on port 4545 (or a dynamic fallback port). The WebSocket endpoint is ws://<IP>:4545/automation.
5. Command routing: When you send a command, the Android NLU classifies the intent. If it detects cross-device keywords ("on my laptop", "on desktop", "on my pc"), the command is sent to the Desktop Agent via WebSocket.
6. Two-way feedback: The Desktop Agent sends status updates back to Android in real-time: started, in_progress (with step descriptions), completed, failed, scheduled, or cancelled. These appear as messages in Omni-Chat.
7. Transaction tracking: Every command gets a unique transaction ID. This ensures status updates are matched to the right command and duplicate responses are filtered out.

Cross-device screen tabs:
The Cross-Device Automation screen on Android has four tabs:
- Devices tab: Discover, pair, and manage connected desktop devices. Shows connection status and allows OTP pairing.
- Rules tab: Create and manage automation trigger rules that execute on the Desktop when conditions are met. Requires a full agent connection.
- Flows tab: Browse and remotely trigger Desktop Flows created on the Autonion Desktop Agent. See the "Desktop Flows" section below.
- Ask tab: Send natural language commands to the desktop and receive real-time responses.

Cross-device features:
- Natural language commands sent to the desktop and executed by the Desktop Agent
- Secure OTP-based device pairing with 6-digit PIN verification
- Paired device management: View paired devices, revoke pairings, toggle "Allow New Pairings" on/off from the Desktop Agent
- Remote Desktop Flow triggering from the Flows tab (list, trigger, stop, real-time progress)
- Desktop unlock from phone — unlock your Windows PC remotely, even before login (pre-login service mode)
- Clipboard sync between devices. Text copied on one device automatically appears on the other. Only text is supported, not images or files. Works only over local WiFi.
- Rule-based automation triggers. You can register trigger rules from Android that execute on the Desktop when conditions are met.
- Scheduled/recurring actions. Example: "press next every 30 seconds on desktop" starts a timer on the Desktop that executes the key press repeatedly.
- Structured commands. For simple key presses, the command is sent with structured data (key name, key code) so the Desktop can execute it directly without LLM involvement. This is faster and more reliable.\r
- Hardware Remote. Map your phone's physical volume buttons to send keyboard commands to the desktop (e.g., Volume Up → Next slide, Volume Down → Previous slide). Supports single tap, double tap, and long press gestures — each can be mapped to different desktop key actions (Enter, Space, Arrow keys, Escape, Backspace). Runs as a foreground service so it works even with the phone screen off. Ideal for presentations, media control, or hands-free desktop navigation. Access it via the remote icon in the Cross-Device top bar.

Setup steps:
1. Download and install the Autonion Desktop Agent from github.com/Autonion/Autonion-Agent/releases on your Windows PC (Flutter-based app, also called Autonion-Agent).
2. For browser tasks on the desktop: also install the Autonion Extension from github.com/Autonion/Autonion-Extension/releases. Download the ZIP, extract it, open Chrome, go to chrome://extensions, enable Developer mode, and click "Load unpacked" to load the extracted folder. Without the Autonion Extension, web automation on the desktop falls back to the less-reliable Windows accessibility tree.
3. Run the Desktop Agent on your PC. Make sure both your Android device and PC are on the same WiFi network.
4. Open Cross-Device Automation in the Android app.
5. The desktop should appear automatically via mDNS discovery within a few seconds.
6. Tap the device name. A 6-digit PIN will appear on the Desktop Agent screen.
7. Enter the PIN on your Android device to complete pairing. You will see a "Connected" status.
8. If auto-discovery fails, tap manual entry and type the IP address and port shown in the Desktop Agent window.

Managing paired devices:
- On the Desktop Agent, go to the Connect tab to see all paired companion devices.
- Toggle "Allow New Pairings" on/off to control whether new devices can pair.
- Revoke a device's pairing to disconnect it permanently.
- Paired devices reconnect automatically when both are on the same network.

#### Desktop Flows (Remote Flow Triggering)
The Flows tab in the Cross-Device screen lets you browse and control automation flows created on the Desktop Agent.

How it works:
1. The Android app requests the list of saved desktop flows via WebSocket.
2. Each flow shows its name, description, node count, and trigger type.
3. Tap a flow card to trigger it on the Desktop Agent remotely.
4. Real-time progress updates show step-by-step execution: which node is running, current step, total steps.
5. You can stop a running flow at any time.

Desktop Flows support a special "Pre-login Mode" — when your PC is on the lock screen (not yet logged in), the Android app can still connect to the Desktop Agent's background service and trigger Unlock flows to unlock your PC remotely.

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
Create visual automation workflows using a drag-and-drop interface. Available on both Android (Automation Companion app) and Desktop (Autonion Desktop Agent).

#### Android Flow Builder
Node types available on Android:
- Start: Entry point for every flow
- Gesture: Tap, long press, swipe, or custom gesture at specific coordinates or from context
- Screen Understanding: AI-powered screen analysis with three modes (see below)
- Visual Trigger: Image template matching to detect and act on visual patterns
- Delay: Wait for a specified duration before continuing
- Launch App: Open a specific application
- Repeat: Loop a section of the flow a specified number of times
- Clipboard: Read or write clipboard content
- Input: Type text from a static string or dynamic context variable

Screen Understanding node (formerly "Screen ML") has three modes:
- Elements mode (YOLO + UI Attributes): Uses YOLO object detection combined with accessibility tree augmentation. Best for complex UIs where visual detection and accessibility data complement each other. Can identify buttons, text fields, icons, and other UI components.
- UI Attribute mode (Accessibility-only): Uses only the Android accessibility tree to find and interact with elements. No screenshot or ML model required. Lightweight and fast, works well for apps with good accessibility support.
- OCR mode (Text Recognition): Uses ML Kit text recognition to read text on screen. Can search for specific text, extract all visible text, and interact with elements identified by their text content.

Other components:
- Edge conditions: Conditional branching between nodes based on context variable values
- Connections: Visual links between nodes that define the execution order

How to create a flow on Android:
1. Open Flow Builder from the home screen.
2. Tap "+" to create a new flow.
3. Add a Start node (entry point).
4. Add action nodes from the palette (gesture, screen understanding, delay, etc.).
5. Connect nodes by dragging between their connection points.
6. Configure each node (e.g., set tap coordinates, choose Screen Understanding mode, enter text).
7. Tap "Save" to save the flow.
8. Tap "Run" to execute immediately.

#### Desktop Flow Builder (Autonion Desktop Agent)
The Autonion Desktop Agent has its own Flows tab with a full visual node-based workflow editor. Desktop flows support 18+ node types:
- Start, Click, Double Click, Right Click, Type Text, Keyboard (hotkey combos like Ctrl+S, Alt+Tab)
- Launch App, Delay, Screenshot, Scroll, Swipe
- Repeat, Conditional (if/else branching based on UI state)
- Visual Trigger (image template matching on desktop screen)
- UI Detect (Windows UIA attribute matching by automation ID, class name, role)
- Unlock (remotely unlock the Windows desktop)
- Data Iterator (iterate through lists/grids/tables using the accessibility tree)
- Done (marks flow completion)

Desktop flow triggers:
- Manual: User clicks "Run" in the UI
- Hotkey: A global OS hotkey fires the flow (e.g., Ctrl+Shift+F1)
- Element Appears: A UIA element matching specific attributes appears on screen
- Scheduled: Timed/recurring execution at set intervals

Desktop flows can be triggered remotely from the Android app's Cross-Device > Flows tab.

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

### Screen Understanding AI (formerly UI Recognition / Screen ML)
AI-powered screen understanding using machine learning models. This feature has three operating modes that can be selected in both the Flow Builder and the standalone Screen Understanding interface.

Three operating modes:
1. Elements mode (YOLO + UI Attributes): Combines YOLO (You Only Look Once) object detection with accessibility tree augmentation. The YOLO model detects UI elements visually (buttons, text fields, images, icons) and the accessibility tree provides additional text, labels, and roles. This hybrid approach gives the most complete picture of the screen. Best for complex UIs or apps with incomplete accessibility support.
2. UI Attribute mode (Accessibility-only): Uses only the Android accessibility tree to discover interactive elements. No screenshot or ML model is needed. Lightweight and fast. Works well for standard Android apps with proper accessibility labels. Does not require screen capture permission.
3. OCR mode (Text Recognition): Uses ML Kit text recognition to read and locate text on screen. Can search for specific text strings, extract all visible text, and identify text position for interaction. Useful for interacting with custom-drawn text, game UIs, or elements that lack accessibility labels.

Key capabilities across all modes:
- Dynamic UI handling: Handles UI changes in real-time. Unlike coordinate-based approaches (gesture recording), Screen Understanding adapts to layout changes because it re-detects elements each time rather than relying on fixed positions.
- Multi-strategy element matching: When replaying automation steps, the engine uses a cascade of matching strategies: (1) Text + label exact match, (2) Label + IoU spatial match, (3) Label + closest distance fallback. This makes automations resilient to minor layout changes.
- Resolution-independent matching: Normalizes coordinates so automations recorded on one device can replay on different screen sizes.
- Accessibility tree fallback: OCR mode falls back to the accessibility tree if ML Kit cannot find the target text, ensuring reliability.

How Screen Understanding helps with automation:
- Can automate clicks on elements identified by OCR text recognition
- Provides visual element detection as a fallback when the accessibility tree does not expose certain elements
- Handles dynamic and changing UIs because it re-detects elements each time rather than using stored coordinates
- Works across all apps including those with custom or non-standard UI frameworks

Use cases:
- Automating clicks on text identified by OCR (e.g., click on a specific label or price)
- Detecting and interacting with UI elements in apps that have poor accessibility support
- Handling dynamic UIs that change layout frequently
- Supplementing the Semantic Automation engine with visual element detection
- Creating reliable automation steps in Flow Builder that work across app updates

### Browser Automation (Extension Bridge)
For web browsing tasks, Autonion supports an extension-based approach that provides direct access to web page content through the browser DOM. This is the primary method for browser-based automation and produces significantly better results than the accessibility tree fallback.

How it works on Android:
1. A WebSocket server runs inside the Android app (ExtensionBridgeServer on port 54321).
2. A compatible browser with the Autonion Android Extension connects to this server.
3. When the Semantic Engine detects a browser task, it launches the browser with the target URL.
4. The extension captures the webpage DOM (interactive elements, text, links, buttons).
5. The DOM elements are sent back to the Android app and presented to the LLM as numbered elements.
6. The LLM predicts actions (click element, type text, scroll) using element IDs from the DOM snapshot.
7. The action commands are sent back to the extension which executes them in the webpage.
8. After each action, the extension automatically captures a fresh DOM snapshot, enabling the agentic loop: DOM Snapshot → LLM Decision → Action Command → DOM Snapshot → repeat.

Supported browsers with extensions on Android: Kiwi Browser (recommended), Lemur Browser, Firefox Nightly.

If no supported browser is installed, the engine will prompt you to install one and offer options. If a browser is installed but the extension is not connected, the engine will detect this and prompt you to download the extension.

#### Installing the Autonion Extension (Desktop Chrome/Edge)
The Autonion Extension is a Chrome extension for desktop browsers that enables cross-device browser automation. When installed on your desktop Chrome or Edge browser, it allows the Autonion Desktop Agent to interact with web page content directly through the DOM instead of using the less-reliable Windows accessibility tree.

Step-by-step installation:
1. Go to github.com/Autonion/Autonion-Extension/releases and download the latest ZIP file.
2. Extract the downloaded ZIP file to a folder on your computer (e.g., Desktop or Documents).
3. Open Google Chrome (or Microsoft Edge).
4. Navigate to chrome://extensions in the address bar (or edge://extensions for Edge).
5. Enable "Developer mode" by toggling the switch in the top-right corner of the extensions page.
6. Click the "Load unpacked" button that appears after enabling Developer mode.
7. In the file dialog, navigate to and select the extracted folder (the one containing manifest.json).
8. The Autonion extension icon will appear in your browser toolbar. Click it to see connection status.
9. The extension automatically connects to the Autonion Desktop Agent when both are running.

Source code: github.com/Autonion/Autonion-Extension

#### Installing the Autonion Android Extension (Mobile Browsers)
The Autonion Android Extension (also called Autonion Semantic Bridge) is the mobile counterpart that runs inside Android browsers supporting extensions. It captures webpage DOM snapshots and relays them to the Autonion Android app via a local WebSocket connection on port 54321. This is essential for web automation tasks on your phone.

Step-by-step installation using Lemur Browser (example):
1. Go to github.com/Autonion/Autonion-Android-Extension/releases and download the latest ZIP file to your phone.
2. Extract the ZIP file using a file manager app (e.g., Files by Google, ZArchiver, or any file manager with ZIP support).
3. Open Lemur Browser on your phone.
4. Tap the three-dot menu (⋮) in the top-right corner.
5. Go to "Extensions" from the menu.
6. Enable "Developer mode" if prompted.
7. Tap "Load unpacked" or "Load from folder".
8. Navigate to the extracted folder and select it.
9. The Autonion Semantic Bridge extension will load and automatically connect to the Android app.

Step-by-step installation using Kiwi Browser:
1. Download the ZIP from github.com/Autonion/Autonion-Android-Extension/releases.
2. Extract the ZIP file on your phone.
3. Open Kiwi Browser.
4. Navigate to chrome://extensions in the address bar.
5. Enable "Developer mode" toggle.
6. Tap "Load unpacked" (or "+(from .zip/.crx/.user.js)").
7. Select the extracted extension folder.
8. The extension loads and connects to the Android app automatically.

How to verify the extension is connected:
- In the Autonion Android app, go to Automation Debugger > Semantic Actions. You should see a "Browser Extension Connected" log entry.
- The extension popup (tap the extension icon in the browser) shows the connection status to the local WebSocket server.

Source code: github.com/Autonion/Autonion-Android-Extension

#### Difference between the two extensions
- Autonion Extension (Desktop): Runs in Chrome/Edge on your PC. Connects to the Autonion Desktop Agent. Used for cross-device browser automation where you control the desktop browser from your phone.
- Autonion Android Extension (Mobile): Runs in Kiwi/Lemur/Firefox Nightly on your phone. Connects directly to the Autonion Android app. Used for on-device browser automation where the AI agent controls the mobile browser.

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
- Inference Mode: SERVER_LLM (uses Ollama) is recommended for best quality. CLOUD_API is a great alternative if you don't have a local PC. LOCAL_SLM (on-device GGUF) works fully offline.

### Cloud API Setup (Using Cloud LLMs)
If you don't have a local PC with Ollama or want to use premium cloud models, you can connect to any OpenAI-compatible Cloud API provider.

Supported Cloud API providers:
- OpenAI: GPT-4o-mini, GPT-4o, GPT-4.1-mini, GPT-4.1-nano, o4-mini. Most popular choice.
- Google Gemini: Gemini 2.0 Flash, Gemini 2.5 Flash, Gemini 2.5 Pro. Via OpenAI-compatible endpoint.
- Groq: Llama 3.3 70B, Llama 3.1 8B, Gemma2 9B, Mixtral 8x7B. Ultra-fast inference with free tier.
- DeepSeek: DeepSeek Chat, DeepSeek Reasoner. High-quality reasoning at very low cost.
- Mistral AI: Mistral Small, Medium, Large. Strong multilingual support.
- Together AI: Llama 3.3 70B Turbo, Qwen 2.5 72B Turbo, Gemma 2 27B. Run open-source models in the cloud.
- OpenRouter: Aggregator that provides access to any model with one key. Includes free tier models.
- Ollama Cloud: Your personal cloud LLM. Models fetched automatically.
- Custom: Any OpenAI-compatible endpoint. Enter your own base URL.

Step-by-step Cloud API setup:
1. Open the AI Engine Hub: From the home screen, go to Semantic Automation and tap the gear/SLM Hub icon.
2. Switch to Cloud API mode: In the "Inference Engine" section, tap "Cloud API".
3. A privacy disclaimer will appear since Cloud API sends screen data to external servers. Read and accept to proceed.
4. Select your provider: Choose from the list of supported providers.
5. Enter your API key: Paste the API key from your provider's dashboard.
6. Select a model: Choose from suggested models or enter a custom model name.
7. Tap "Save & Test Connection" to verify it works.
8. Once connected, a green "Connected" badge appears.

Important: Cloud API sends screen element data to external servers. This is the only mode that transmits data outside your local network. You must accept the privacy disclaimer before enabling it.

### On-Device SLM Setup (Installing SLM on Mobile)
To run AI models directly on your Android phone without any server, you can install an on-device Small Language Model (SLM). This is called Local SLM mode and it runs entirely on your phone with no network connection needed. The app supports two model formats: GGUF (via llama.cpp) and MediaPipe (.bin/.task).

Step-by-step guide to install an SLM model on your mobile device:
1. Open the AI Engine Hub: From the home screen, go to Semantic Automation and tap the gear/SLM Hub icon in the top-right corner. Alternatively, open Omni-Chat settings (gear icon in chat header) and switch to "On-Device SLM" mode to see instructions.
2. Check your device hardware: The AI Engine Hub shows your device's total RAM. Models require a minimum amount of RAM to run. 3B models need at least 4GB RAM. 7B models need at least 8GB RAM.
3. Browse the model catalog: The AI Engine Hub lists available models with their size, RAM requirements, quantization type, and format (GGUF or MediaPipe). Models marked as "Recommended for your device" are the best fit for your hardware.
4. Download the model file: Tap the "Download" button on a compatible model. For GGUF models, this opens HuggingFace where you can download the .gguf file. For MediaPipe models, this opens Kaggle. Download the model file to your phone's storage.
5. Import the model: Go back to the AI Engine Hub in the app. Scroll down to the "Installed Models" section and tap "Import .gguf / .bin". Use the file picker to select the downloaded file. The app validates the file (for GGUF, it checks the magic header) and copies it into its internal storage.
6. Set the model as active: After import, the model appears in the "Installed Models" list with a GGUF or TFLite format badge. Tap on it to set it as the active model. It will show an "ACTIVE" badge.
7. Switch to On-Device SLM mode: In the "Inference Engine" section at the top of the AI Engine Hub, tap "On-Device SLM" to switch from Server LLM or Cloud API to local inference.
8. Start using it: The SLM model is now ready. Go to Omni-Chat or Semantic Automation and give a command. The on-device model will process it locally.

Available GGUF models (recommended):
- Qwen 2.5 3B Instruct (Q4_K_M): Fast and capable. Best balance of speed and quality. About 2GB download from HuggingFace.
- Phi-3.5 Mini Instruct (Q4_K_M): Microsoft's compact model with strong reasoning. About 2.2GB download from HuggingFace.
- Llama 3.2 3B Instruct (Q4_K_M): Meta's latest compact model. Good general-purpose performance. About 2GB download from HuggingFace.
- Gemma 4 / Gemma 3n: Google's latest Gemma models. Available in GGUF format.
- Any compatible GGUF file: The engine supports any GGUF model using a supported architecture (LLaMA, Phi, Qwen, Gemma, etc.).

Available MediaPipe models (legacy):
- Gemma 2B IT (CPU/GPU): Lightweight, fast. Best for devices with 4-6GB RAM. About 1.35GB.
- Gemma 7B IT (CPU/GPU): Smarter reasoning. Needs 8GB+ RAM. About 3.8GB.
- Gemma 2 2B IT: Next-generation Gemma. Better reasoning at 2B size.

Important notes about on-device SLM:
- SLM runs entirely on your phone. No WiFi, no server, no PC needed.
- The GGUF engine uses llama.cpp with optimized settings: 1024 context window, adaptive thread limits for mobile big.LITTLE CPUs, and pre-allocation RAM guard to prevent crashes.
- GGUF models offer significantly better quality than legacy MediaPipe models at similar sizes.
- SLM inference is slower than Server LLM (Ollama) or Cloud API. Expect 10-30 seconds per response depending on your device and model.
- SLM is less accurate than larger server/cloud models for complex multi-step tasks. For best results, use simple commands.
- The model file stays in app storage. You can delete it from the AI Engine Hub if you need space.
- If your device has less than 4GB RAM, SLM models may cause crashes or extreme slowness.
- If a GGUF model file is corrupted or truncated, the app will detect this and show an error rather than crashing.

### Connecting to Desktop Agent
To use cross-device automation, you must install the Autonion Desktop Agent app on your computer. Download it from github.com/Autonion/Autonion-Agent/releases.

1. Install the Autonion Desktop Agent on your Windows PC (download the installer from github.com/Autonion/Autonion-Agent/releases).
2. Run the Desktop Agent. It will show its WebSocket server port and local IP addresses.
3. Make sure both devices are on the same WiFi network.
4. On the Desktop Agent, go to the Connect tab and make sure "Allow New Pairings" is turned on.
5. In the Android app, go to Cross-Device Automation > Devices tab. The desktop should appear automatically via mDNS.
6. Tap the device name. A 6-digit PIN will be displayed on the Desktop Agent screen in a pairing dialog.
7. Enter the PIN on your Android device within 120 seconds. If the PIN expires, tap the device again to generate a new one.
8. Once the PIN is verified, the devices are paired and connected. Future connections are automatic.
9. If the device does not appear via mDNS, use manual IP entry with the address shown in the Desktop Agent window.
10. For browser-based tasks on the desktop, also install the Autonion Extension in Chrome (see the Browser Automation section above).

The Desktop Agent has 7 navigation tabs: Dashboard, Connect, Automate, Flows, AI, Logs, and Settings.

## Troubleshooting

### Automation does random or unexpected things
This is usually caused by the LLM making poor predictions:
- Try a larger model. Models with 7B parameters or more produce much better results than 3B models.
- Break complex commands into simpler steps. Instead of "open WhatsApp, find Mom's chat, and send her a birthday wish", try "open WhatsApp" first, then "search for Mom" separately.
- Close unnecessary apps to reduce screen UI noise. The fewer elements on screen, the easier it is for the LLM to identify the right target.
- Check the Automation Debugger > Semantic Actions to see exactly what the LLM predicted. This helps identify whether the issue is with element detection or action selection.
- Use Omni-Chat for simple commands (like "press next" or "turn off wifi") which bypass the AI agent entirely and execute instantly.

### Cannot connect to desktop
- Make sure the Autonion Desktop Agent (from github.com/Autonion/Autonion-Agent/releases) is installed and running on your PC.
- Ensure both devices are on the same WiFi network. Mobile data or different networks will not work.
- Check if the Desktop Agent is running and shows a port number and IP address in its window.
- Make sure "Allow New Pairings" is enabled on the Desktop Agent's Connect tab (for first-time pairing).
- Try manual IP entry if mDNS auto-discovery does not find the desktop.
- Check Windows Firewall settings. The Desktop Agent needs to accept incoming connections.
- Make sure the WebSocket port (default 4545) is not blocked by your router or firewall.
- Try restarting both the Android app and the Desktop Agent.

### PIN pairing issues
- If the PIN does not appear on the Desktop Agent screen, make sure "Allow New Pairings" is turned on in the Connect tab.
- The PIN expires after 120 seconds. If it expires, tap the device again on Android to generate a new PIN.
- If you enter the wrong PIN, an error message appears. Re-enter the correct PIN shown on the Desktop screen.
- Too many failed PIN attempts will temporarily block the pairing request. Wait a moment and try again.
- If a device was previously paired and revoked, you need to pair again with a new PIN.

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
- You need a compatible browser with the Autonion Android Extension installed. Supported browsers: Kiwi Browser (recommended), Lemur Browser, or Firefox Nightly.
- Download the extension from github.com/Autonion/Autonion-Android-Extension/releases, extract the ZIP, and load it as an unpacked extension in your browser's extension settings.
- Make sure the extension is enabled in the browser's extension settings.
- If the extension cannot connect, check that the ExtensionBridgeServer port (54321) is not being used by another app.
- Verify the extension is connected by checking Automation Debugger > Semantic Actions for a "Browser Extension Connected" log entry.
- If the engine says "Browser extension not detected", the extension may not be installed or may have lost its connection. Try reopening the browser or reloading the extension.
- Try restarting the browser and reopening the page.
- For desktop browser automation, install the Autonion Extension (desktop version) from github.com/Autonion/Autonion-Extension/releases in Chrome or Edge.

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
- Local-First Processing: AI inference defaults to your local network (Ollama) or on-device (GGUF). No data is sent to cloud servers unless you explicitly opt into Cloud API mode.
- Cloud API Opt-in: Cloud API mode is entirely optional and requires explicit user consent via a privacy disclaimer. When enabled, screen element data is sent to your chosen cloud provider (OpenAI, Gemini, Groq, etc.). This is the only mode that transmits data outside your local network.
- Secure Device Pairing: Cross-device connections use OTP-based PIN verification. Paired device secrets are stored using encrypted storage (flutter_secure_storage on Desktop, EncryptedSharedPreferences on Android).
- No Telemetry: The app does not collect or send any usage data, analytics, or crash reports to external servers.
- Local Network Only: Cross-device communication uses your local WiFi. No internet relay servers.
- No Account Required: No sign-up, login, or cloud account is needed.
- Accessibility Service Warning: The Accessibility Service has broad permissions and can read all screen content. Only enable it when actively using automations.
