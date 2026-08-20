# Autonion Desktop Agent Guide

## Overview

The Autonion Desktop Agent (also called Autonion-Agent) is a Flutter-based Windows desktop application that serves as the companion app for the Autonion Android app. It enables cross-device automation, allowing you to control your Windows PC from your Android phone using natural language commands, visual automation flows, and remote desktop unlock.

Download: github.com/Autonion/Autonion-Agent/releases
Source code: github.com/Autonion/Autonion-Agent

## Navigation Tabs

The Desktop Agent has 7 main navigation tabs in the left rail:

1. **Dashboard**: Overview of connection status, recent activity, and quick stats.
2. **Connect**: Manage device connections, paired companions, and pairing settings.
3. **Automate**: Configure automation rules and responses to commands from the Android app.
4. **Flows**: Visual node-based flow builder for creating desktop automation workflows.
5. **AI**: AI settings and Ollama server configuration for intelligent command processing.
6. **Logs**: View detailed logs of all actions, connections, and errors.
7. **Settings**: General app settings, startup behavior, and preferences.

## Device Connection and OTP Pairing

### How Pairing Works
The Desktop Agent uses OTP-based device pairing to securely connect with Android devices:

1. The Desktop Agent broadcasts its presence on the local network via mDNS (service type: _autonion._tcp) on port 4545.
2. When an Android device discovers and taps on the Desktop Agent, a pairing request is received.
3. The Desktop Agent displays a 6-digit PIN in a modal pairing dialog. The dialog shows:
   - The 6-digit PIN in large monospace font
   - A 120-second countdown timer
   - The requesting device's name and IP address
   - A "Decline" button to reject the request
4. The user enters the PIN on their Android device.
5. If the PIN matches, a unique encrypted secret is generated, exchanged, and stored securely.
6. The device is now "paired" and future connections are automatic — no PIN needed again.

### Managing Paired Devices
In the Connect tab:
- **Paired Companions list**: View all devices that have completed OTP pairing, with their names and IP addresses.
- **Allow New Pairings toggle**: When turned off, no new devices can initiate pairing. Only previously paired devices can connect.
- **Revoke**: Remove a paired device's trusted status. After revoking, the device must pair again with a new PIN.
- **Auto-reconnection**: Paired devices automatically reconnect when both are on the same WiFi network.

### Security
- Paired device secrets are stored using flutter_secure_storage (encrypted).
- PIN comparison uses constant-time comparison to prevent timing attacks.
- Each pairing session generates a unique cryptographic secret.

## Flows Tab (Desktop Flow Builder)

The Flows tab provides a full visual node-based workflow editor for creating desktop automation flows.

### Creating a Flow
1. Click "+" to create a new flow.
2. Drag nodes from the palette onto the canvas.
3. Connect nodes by dragging between their input/output ports.
4. Configure each node's properties in the right panel.
5. Set a trigger type for the flow.
6. Save the flow.

### Node Types (18+)
The Desktop Flow Builder supports the following node types:

**Basic Actions:**
- **Start**: Entry point for every flow
- **Click**: Click at screen coordinates or a UIA element
- **Double Click**: Double-click at a position
- **Right Click**: Right-click at a position
- **Type Text**: Type a string of text

**Input:**
- **Keyboard**: Execute hotkey combinations (e.g., Ctrl+S, Alt+Tab, Win+L). Supports action types:
  - Press & Release: Standard key combination
  - Hold: Hold a key down
  - Release: Release a held key
  - Type Sequence: Type a series of key presses

**System:**
- **Launch App**: Open a specific application by name or path
- **Delay**: Wait for a specified duration (milliseconds)
- **Screenshot**: Capture the current screen

**Navigation:**
- **Scroll**: Scroll up/down at a position
- **Swipe**: Swipe gesture on screen

**Logic:**
- **Repeat**: Loop a section of the flow N times
- **Conditional**: If/else branching based on UI state or context variables

**AI/Detection:**
- **Visual Trigger**: Image template matching on the desktop screen
- **UI Detect**: Match Windows UIA (UI Automation) elements by automation ID, class name, or role

**Special:**
- **Unlock**: Remotely unlock the Windows desktop (works even on lock screen)
- **Data Iterator**: Iterate through lists, grids, or tables discovered via the accessibility tree
- **Done**: Marks the flow as complete

### UI Targeting Modes
Nodes that interact with screen elements support multiple targeting modes:
- **Screen Coordinates**: Click/interact at fixed X,Y pixel positions
- **UIA Attribute Match**: Find elements by their Windows UI Automation attributes (automation ID, class name, control type)
- **Stable Element ID**: Use a persistent element identifier for reliable targeting across sessions

### Flow Triggers
Each flow can be configured with one of these trigger types:
- **Manual**: User explicitly clicks "Run" in the UI
- **Hotkey**: A global OS hotkey starts the flow (e.g., Ctrl+Shift+F1). The hotkey works from any app.
- **Element Appears**: A UIA element matching specific attributes appears on screen, automatically triggering the flow
- **Scheduled**: Timed or recurring execution at configured intervals

### Flow Storage
- Flows are stored as JSON files in `~/.autonion/flows/<flow-id>.json`
- Each flow file contains the complete node graph, connections, and trigger configuration

### Remote Flow Triggering
Desktop flows can be triggered remotely from the Autonion Android app:
1. The Android app connects to the Desktop Agent via WebSocket.
2. The Android app sends a `list_flows` request to get all available flows.
3. Each flow's metadata (name, description, node count, trigger type) is shown in the Cross-Device > Flows tab on Android.
4. The user taps a flow to send a `trigger_flow` request.
5. The Desktop Agent executes the flow and sends real-time progress updates back:
   - STARTED: Flow execution has begun
   - STEP_EXECUTING: A specific node is being executed
   - STEP_COMPLETED: A node has finished
   - COMPLETED: The entire flow finished successfully
   - STOPPED: The user cancelled the flow
   - FAILED: An error occurred during execution
6. The user can send a `stop_flow` request to halt execution at any time.

## Pre-Login Mode (Background Service)

The Desktop Agent can run a background service that stays active even on the Windows lock screen (before the user logs in). This enables:
- **Remote Desktop Unlock**: The Android app can trigger Unlock flows to unlock Windows remotely
- **Pre-login Flow Execution**: Flows can run even before the desktop GUI is fully available
- The Android app's Flows tab shows a "Pre-login Mode" banner when connected via background service only

## WebSocket Communication

The Desktop Agent runs a WebSocket server on port 4545 (or dynamic fallback).
- Endpoint: `ws://<IP>:4545/automation`
- Supports bidirectional communication with the Android app
- Message types include: automation commands, status updates, flow management, clipboard sync, rule registration
- Transaction IDs ensure correct command-response matching

## Automation Features

### Natural Language Commands
When the Android app sends a cross-device command (e.g., "on my laptop open chrome"), the Desktop Agent:
1. Receives the command via WebSocket
2. Processes it using Ollama (if connected) or rule-based heuristics
3. Executes the action (launch app, press keys, type text, etc.)
4. Sends status updates back to Android

### Clipboard Sync
- Text copied on the Android device automatically appears on the Desktop clipboard, and vice versa
- Only text content is supported (not images or files)
- Works over local WiFi only

### Rule-Based Triggers
The Android app can register trigger rules that execute on the Desktop when specific conditions are met.

### Scheduled Actions
Recurring actions (e.g., "press next every 30 seconds") are handled by the Desktop Agent's built-in scheduler.

### Hardware Remote
The Android app includes a Hardware Remote feature that maps the phone's physical volume buttons to desktop keyboard commands via the WebSocket connection. This turns the phone into a wireless remote control for the PC.

How it works:
1. The user taps the remote icon in the Cross-Device top bar on the Android app.
2. A configuration sheet opens with 6 mapping slots:
   - Volume Up: Single Tap, Double Tap, Long Press
   - Volume Down: Single Tap, Double Tap, Long Press
3. Each slot can be mapped to a desktop key action: Enter, Space, Up/Down/Left/Right Arrow, Escape, or Backspace.
4. When activated, a foreground service keeps the mappings alive even with the phone screen off.
5. Volume button presses are intercepted, the corresponding gesture is detected (single/double/long), and the mapped key command is sent to the Desktop Agent via WebSocket.
6. The Desktop Agent executes the key press on the PC.

Use cases:
- Presentation control: Volume Up → Next slide (Right Arrow), Volume Down → Previous slide (Left Arrow)
- Media control: Volume Up → Play/Pause (Space), Volume Down → Skip (Right Arrow)
- Hands-free navigation: Control the PC from across the room

## Browser Extension Integration

For web automation tasks, the Autonion Extension can be installed in Chrome or Edge on the desktop:
- The extension captures the webpage DOM (links, buttons, text fields, forms)
- DOM snapshots are sent to the Desktop Agent, which forwards them to the Android app
- The AI agent can then predict actions on web content using element IDs from the DOM
- This is significantly more reliable than using the Windows accessibility tree for web content

Installation:
1. Download from github.com/Autonion/Autonion-Extension/releases
2. Extract the ZIP file
3. Open Chrome > chrome://extensions (or Edge > edge://extensions)
4. Enable "Developer mode"
5. Click "Load unpacked" and select the extracted folder

## Troubleshooting

### Desktop Agent not found by Android app
- Ensure both devices are on the same WiFi network
- Check that port 4545 is not blocked by Windows Firewall
- Try manual IP entry on the Android app
- Restart the Desktop Agent

### Pairing PIN not appearing
- Make sure "Allow New Pairings" is enabled in the Connect tab
- Check that the Android device is tapping on the correct discovered device
- Restart the Desktop Agent and try again

### Flows not triggering remotely
- Verify the WebSocket connection is active (check the Connect tab status)
- Ensure the flow was saved properly
- Check the Logs tab for error messages during flow execution

### Hotkey triggers not working
- Some hotkey combinations may conflict with other apps
- Ensure the Desktop Agent has focus permissions
- Try a different hotkey combination
