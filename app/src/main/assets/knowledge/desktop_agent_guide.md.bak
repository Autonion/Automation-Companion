# Autonion Desktop Agent Guide

## Overview

The Autonion Desktop Agent is a Flutter-based companion application that runs on Windows (with potential macOS and Linux support). It receives commands from the Autonion Android app over your local WiFi network and executes them on your desktop. Think of it as a remote assistant: you speak to your phone, and your computer acts. All communication stays on your local network with no cloud dependencies.

## Architecture

### Core Components

1. WebSocket Server (websocket_service.dart)
The central communication hub. It listens on port 4545 (or a dynamic fallback if 4545 is busy) for incoming connections from the Android app and the browser extension.
- Manages multiple simultaneous WebSocket clients
- Tracks whether the browser extension is connected (separate from Android clients)
- Broadcasts events to all clients or sends targeted messages to just the extension
- Handles connection acknowledgment, ping/pong heartbeats, and graceful disconnection

2. mDNS Discovery (discovery_service.dart)
Broadcasts the Desktop Agent's presence on your local network using Bonjour/mDNS (service type: _autonion._tcp). This allows the Android app to discover the desktop automatically without manual IP entry.

3. Connection Provider (connection_provider.dart)
The central orchestrator that wires all services together and routes incoming WebSocket commands to the right handler. It is the "brain" of the Desktop Agent that decides what to do with each incoming message.

4. Desktop Agent Service (desktop_agent_service.dart)
The agentic loop for desktop automation. When a desktop-related task arrives, this service takes over and autonomously interacts with the desktop UI using an LLM for decision-making.

5. Python Bridge Service (python_bridge_service.dart)
Manages a Python subprocess that handles system-level interactions. The Python bridge uses the uiautomation and pyautogui libraries to read the Windows accessibility tree, simulate mouse clicks, keyboard input, and capture screenshots.

6. Browser Launcher Service (browser_launcher_service.dart)
Detects installed Chromium-based browsers on the system (Chrome, Edge, Brave) and launches them when browser automation is needed. Auto-selects the first detected browser.

7. Clipboard Sync Service (clipboard_sync_service.dart)
Bidirectional clipboard synchronization between Android and desktop. Polls the desktop clipboard every second and sends text changes to connected Android devices. Receives clipboard text from Android and writes it to the desktop clipboard.

8. Trigger Rule Service (trigger_rule_service.dart)
Manages event-based automation rules registered by the Android app. Rules are stored in memory and forwarded to the browser extension. When the extension detects a rule condition is met, the event is relayed back through the Desktop Agent to Android.

9. AI Provider System (ai/ directory)
Abstracted AI service layer supporting multiple LLM providers:
- Ollama Service: Connects to a local Ollama instance at http://localhost:11434/api/chat. Supports structured JSON output via the format parameter, vision models (base64 images in messages), and model listing/selection.
- API Key Service: For commercial API providers with API key authentication.
- Web-Based AI Service: For web-based AI platforms (ChatGPT, Gemini) through the browser extension content scripts.
The active provider is managed by AiProviderNotifier and can be switched at runtime.

10. System Services
- System Tray Service: Puts the app in the Windows system tray so it runs in the background.
- Window Manager Service: Controls the Flutter window visibility, size, and position.
- Startup Service: Handles auto-start on system boot.

### How the Python Bridge Works

The Python bridge is the layer that performs actual system-level actions on the desktop:

1. Setup process:
   - On first use, the bridge locates a Python 3 installation on the system.
   - It creates a virtual environment (venv) in the AppData directory.
   - It installs required packages: uiautomation, pyautogui, mss, Pillow.
   - It spawns the desktop_agent.py script as a subprocess.

2. Communication protocol:
   - The Flutter app writes JSON commands to the Python process's stdin.
   - The Python process writes JSON responses back on stdout.
   - Each command has a unique ID for request/response matching.
   - Timeout: 30 seconds per command.
   - Python stderr is captured as debug logs.

3. Available Python bridge commands:
   - ping: Health check. Returns "pong".
   - get_screen_state: Reads the Windows accessibility tree of the foreground window and optionally captures a screenshot. Returns a list of UI elements with their names, roles, bounding boxes, and interactability.
   - execute_action: Performs a UI action (click, type, scroll, hotkey, wait).

4. Accessibility tree reading:
   - Uses the Windows UI Automation framework (via Python uiautomation library).
   - Reads the foreground window's element tree up to depth 10.
   - Extracts actionable elements: buttons, menu items, tabs, hyperlinks, list items, checkboxes, radio buttons, edit controls, combo boxes.
   - Each element gets a node_ID for targeting. Elements cached for click execution.
   - Names are truncated to 100 characters for manageable prompt sizes.

5. Action execution:
   - click: Moves the mouse to the center of the target element's bounding box and clicks.
   - type: Optionally clicks a target element first, then types text character by character.
   - scroll: Scrolls up or down using pyautogui.
   - hotkey: Presses key combinations (e.g., Ctrl+C, Win+R, Alt+Tab).
   - wait: Pauses for 1 second.

Python installation requirement: Python 3.8 or newer must be installed and accessible in PATH. The bridge will search for "python", "py", or "python3" commands automatically.

## Communication Flow

### When Android Sends a Command

Step-by-step flow of what happens when the Android app sends a command to the Desktop Agent:

1. The Android app sends a JSON message over the WebSocket connection. The message contains either a natural language prompt, a structured key_press command, a schedule command, or other action types.

2. The ConnectionProvider's _executeCommand method receives the message and determines its type:

   If it contains a "prompt" field (natural language):
   a. The agent first classifies whether the prompt is browser-related or desktop-related.
   b. Classification method: An LLM prompt asks the AI to classify the prompt as "browser" or "desktop" with one word. If the LLM is unavailable, keyword matching is used as fallback (looking for words like "youtube", "website", "amazon", "google", ".com", etc.).
   c. Browser-related prompts go to the browser extension via the agentic DOM-aware loop.
   d. Desktop-related prompts go to the Desktop Agent Service for autonomous execution.

   If type is "key_press" (structured command):
   - The key is sent directly to the Python bridge for execution via hotkey. No LLM needed.

   If type is "schedule":
   - A periodic timer starts that executes the specified key press at the specified interval.
   - Runs until the repeat count is reached or a schedule_cancel command is received.

   If type is "clipboard.text_copied":
   - The text is written to the desktop clipboard via ClipboardSyncService.

   If type is "register_triggers":
   - Trigger rules are stored and forwarded to the browser extension.

   If type is "open_url":
   - The URL is launched in the system default browser.

3. Status responses are sent back to Android at each stage: started, in_progress, completed, or failed.

### Desktop Automation (Agentic Loop)

When a desktop task is identified, the DesktopAgentService runs its agentic loop:

1. Observe: The Python bridge reads the foreground window's accessibility tree and returns a list of UI elements.
2. Build Prompt: The screen state, user goal, and action history are formatted into a prompt sent to the LLM.
3. Predict: The LLM returns a structured JSON response with a "thought" (reasoning) and "action" (what to do).
4. Execute: The predicted action is sent to the Python bridge for execution.
5. Wait: 500ms delay for the UI to settle.
6. Repeat: Steps 1-5 repeat until the LLM outputs "done" or max steps (15) are reached.

Available desktop actions:
- click: Click a UI element by its index in the accessibility tree
- type: Type text into a specific element or the active focus
- scroll: Scroll the view up or down
- hotkey: Press key combinations (e.g., Win+R for Run dialog, Ctrl+C for copy)
- wait: Wait 1 second for UI to settle
- done: Task is complete, stop the loop

Safety rules for desktop automation:
- Maximum 15 steps per task to prevent infinite loops
- Action history is tracked so the LLM can avoid repeating failed actions
- Win+R is used for file and folder operations (avoids File Explorer search bugs)
- Win key is used for launching applications (reliable across Windows versions)
- User can stop the task at any time via the Android app

### Browser Automation (Agentic DOM-Aware Loop)

When a browser task is identified, the ConnectionProvider runs a DOM-aware browser loop:

1. Initial Planning: The LLM is asked for the first action (usually open_url to navigate to the target website).
2. Extension Execution: The action is sent to the browser extension which executes it in the active tab.
3. DOM Snapshot: After execution, the extension captures the current page DOM and sends it back. The snapshot includes interactive elements with their IDs, tag types, text content, ARIA labels, placeholders, roles, and URLs.
4. Next Step Planning: The DOM snapshot, action history, and user goal are sent to the LLM. It decides the next action.
5. Repeat: This cycle continues for up to 8 steps.

Available browser actions:
- open_url: Navigate to a URL
- click_element: Click a DOM element by its ID (e.g., "el_5")
- type_into: Type text into an input field identified by ID, with optional Enter press
- press_key: Press keyboard keys (Enter, Tab, Escape, ArrowDown)
- wait: Wait for a specified number of milliseconds
- scroll_to: Scroll to a specific element by ID

The extension must be connected to the Desktop Agent via WebSocket. If no extension is connected when a browser task arrives, the Desktop Agent will attempt to launch a browser automatically and wait up to 15 seconds for the extension to connect.

## Browser Extension

The Autonion browser extension (Manifest V3) is a Chromium-based extension that enables web page automation.

Extension components:
- Background Service Worker (background.js): Maintains the WebSocket connection to the Desktop Agent. Handles command routing and DOM snapshot requests.
- Content Scripts: Injected into web pages to capture DOM elements and execute actions.
  - semantic-dom.js: Extracts interactive elements from the page (links, buttons, inputs, textareas) and assigns IDs.
  - chatgpt.js: Content script for ChatGPT, enabling web-based AI interaction.
  - gemini.js: Content script for Google Gemini, enabling web-based AI interaction.
- Popup (popup.html): Configuration UI for setting the WebSocket server URL and viewing connection status.

Extension connection flow:
1. User configures the Desktop Agent's IP and port in the extension popup.
2. The extension connects via WebSocket to ws://<IP>:<PORT>/automation.
3. The extension identifies itself by including "source: extension" in its messages.
4. The Desktop Agent tracks the extension as a separate client for targeted messaging.

## Setup

### Requirements
- Windows 10 or 11 (primary support). macOS and Linux may work but are not fully tested.
- Flutter SDK installed (for development or building from source)
- Python 3.8 or newer installed and in system PATH (for the automation bridge)
- Ollama installed locally (for AI-powered automation features)
- A Chromium-based browser installed (Chrome, Edge, Brave) for web automation

### Installation Steps
1. Clone or download the Desktop Agent source code.
2. Open a terminal in the project directory.
3. Run "flutter pub get" to install Dart/Flutter dependencies.
4. Run "flutter run -d windows" to start the Desktop Agent in development mode. For a release build: "flutter build windows".
5. The agent window will display: the WebSocket server port, your local IP addresses, and the connection status.

### Python Bridge Setup
The Python bridge is set up automatically on first use:
1. Make sure Python 3.8+ is installed on your system and accessible from the command line.
2. The bridge creates a virtual environment in your AppData directory (autonion_venv).
3. Required Python packages (uiautomation, pyautogui, mss, Pillow) are installed automatically via pip.
4. The bridge spawns the python/desktop_agent.py script as a subprocess.
5. A ping/pong health check runs on startup to verify the bridge is working.

If Python is not installed, the Desktop Agent will show an error "Python 3 is not installed or not in PATH" when you try to use desktop automation features.

### Ollama Setup
1. Download Ollama from https://ollama.ai and install it.
2. Open a terminal and run: ollama pull qwen2.5:3b (or any model you prefer).
3. Ollama starts automatically at http://localhost:11434.
4. In the Desktop Agent settings, configure the Ollama URL and model. The default URL is http://localhost:11434.
5. The agent uses Ollama for both desktop automation (predicting UI actions) and browser automation (planning browser steps).

### Browser Extension Setup
1. Open your Chromium browser (Chrome, Edge, Brave).
2. Go to the extensions page: chrome://extensions or edge://extensions.
3. Enable "Developer mode" toggle.
4. Click "Load unpacked" and select the Autonion-Extension folder.
5. The extension icon will appear in the browser toolbar.
6. Click the extension icon and enter your Desktop Agent's IP and port (e.g., 192.168.1.100:4545).
7. Click "Connect". The status should show "Connected".

## WebSocket Protocol Reference

### Connection Details
- Default port: 4545 (automatically falls back to a random available port if 4545 is in use)
- Endpoint path: /automation
- Full URL: ws://<IP>:4545/automation
- Auto-discovery: mDNS service type _autonion._tcp
- Upon connection, the server sends a connection_ack message with agent info and timestamp

### Incoming Message Types (from Android or Extension)

prompt: Natural language command to execute
- Fields: prompt (string), transactionId (string), timestamp (number), sourceDeviceId (string)
- The agent classifies the prompt as browser or desktop and routes accordingly.
- A started response is sent immediately as acknowledgment.

key_press: Execute a key press directly without LLM
- Fields: type ("key_press"), keyName (string), transactionId (string)
- The key is sent to the Python bridge for immediate execution.
- Much faster than the LLM path. Used for structured commands from the Android NLU.

schedule: Start a recurring action
- Fields: type ("schedule"), action (object with keyName), intervalMs (number), repeatCount (number or null), transactionId (string)
- Creates a periodic timer that executes the key press at the specified interval.
- If repeatCount is null, runs indefinitely until cancelled.

schedule_cancel: Stop a recurring action
- Fields: type ("schedule_cancel"), transactionId (string)
- Cancels the timer identified by transactionId.

open_url: Launch a URL in the default browser
- Fields: type ("open_url"), url (string) or payload.url (string)

clipboard.text_copied: Clipboard sync event from Android
- Fields: type ("clipboard.text_copied"), payload.text (string)
- Writes the received text to the desktop system clipboard.

register_triggers: Register automation rules from Android
- Fields: type ("register_triggers"), payload.rules (array of rule objects)
- Rules are forwarded to the browser extension.

Extension messages: Messages with source "extension" are routed to the extension handler.
- execution_status: Step-level progress updates from extension
- execution_result: Final result of an extension automation task
- dom_snapshot: DOM element snapshot from a webpage
- step_result: Result of a single agentic step with DOM snapshot for next planning cycle
- kill_switch_ack: Extension acknowledges a stop command
- rule_triggered: A trigger rule condition was met

### Outgoing Message Types (to Android or Extension)

connection_ack: Sent on new connection. Contains status, agent name, timestamp, and server info (port, client count).

prompt_response: Command execution status update sent to Android.
- Fields: type ("prompt_response"), transactionId (string), status (string), message (string), timestamp (string)
- Status values: started, in_progress, completed, failed, scheduled, cancelled

pong: Heartbeat response to a ping message. Contains timestamp.

clipboard.text_copied: Clipboard sync event from desktop to Android. Sent when a new text is copied on the desktop clipboard.

## Troubleshooting

### Desktop Agent will not start
- Make sure Flutter SDK is properly installed. Run "flutter doctor" to check for issues.
- Try running with the verbose flag: flutter run -d windows --verbose
- Check if port 4545 is already in use by another application. The agent will fall back to a random port, but check the logs.
- If the window appears briefly and closes, check the terminal/console for error messages.

### Android cannot find the Desktop
- Both devices must be on the same WiFi network. Verify this first.
- Check the Desktop Agent window. It should show "Server listening on 0.0.0.0:<port>".
- Look for the "Reachable at: ws://..." lines in the agent window. These show your actual IP addresses.
- Check Windows Firewall: Allow the autonion_agent.exe (or flutter_windows.exe during development) through the firewall.
- If mDNS discovery fails, use manual IP entry on the Android app with the IP and port shown.
- Some corporate or guest WiFi networks block mDNS. Try a home or personal WiFi network.

### Desktop automation commands fail
- For AI-powered automation: Make sure Ollama is running and has at least one model loaded. Check by visiting http://localhost:11434 in your browser.
- For direct key presses: Make sure the Python bridge is initialized. Check the Desktop Agent logs for "PythonBridge" messages. If the bridge failed, check that Python 3 is installed and in PATH.
- Python bridge troubleshooting: If the virtual environment creation fails, manually delete the autonion_venv folder in your AppData/Roaming directory and restart the agent.
- Check the Automation Debugger on the Android app for error details about what went wrong.

### Browser extension does not connect
- The extension only works with Chromium-based browsers: Chrome, Edge, Brave.
- Make sure the extension is loaded and enabled in the browser's extension page.
- In the extension popup, verify the WebSocket URL matches the Desktop Agent's IP and port.
- Try reloading the extension: go to chrome://extensions, find Autonion, click the refresh icon.
- If the browser was launched by the Desktop Agent automatically, the extension may need a few seconds to establish the WebSocket connection.

### Clipboard sync is not working
- Both devices must be connected via WebSocket. Check the Desktop Agent shows at least 1 connected client.
- Only text content is synced. Images, files, and rich content are not supported.
- There is a 1-second polling interval for detecting clipboard changes on the desktop.
- If you copy text on Android and it does not appear on the desktop, check the WebSocket connection status on both sides.

### Python bridge errors
- "Python 3 is not installed or not in PATH": Install Python 3.8+ and make sure it is accessible from the command line. Run "python --version" in a terminal to verify.
- "Failed to create venv": Delete the existing venv folder at AppData/Roaming/autonion_venv and restart the agent.
- "desktop_agent.py not found": Make sure the python/ folder with desktop_agent.py exists in the project directory.
- "Command timed out": The Python bridge has a 30-second timeout per command. Complex accessibility trees can take longer. Try closing unnecessary windows on the desktop.

### Agent reaches max steps without completing
- The desktop agentic loop has a maximum of 15 steps. If the task is complex, try breaking it into simpler sub-tasks.
- Check the action history in the logs. If the agent is clicking the wrong elements or getting stuck, the accessibility tree may not have the expected elements.
- Some Windows applications have complex or non-standard UI that the accessibility tree cannot read properly. Try using hotkey-based shortcuts instead.

## Privacy and Security

- All communication happens over your local WiFi network. No data is sent to cloud servers or external APIs.
- Ollama runs entirely locally on your machine. LLM inference happens on your hardware.
- No telemetry, analytics, or usage tracking of any kind.
- No account or login required.
- The Python bridge has system-level access (mouse, keyboard, screen). Only run the Desktop Agent on trusted machines.
- The WebSocket server accepts connections from any device on the local network. Keep your WiFi network secure.
