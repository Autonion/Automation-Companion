# 🌟 Features — Automation Companion

This file describes every major feature of the Automation Companion app.  
Each entry includes:
- What the feature does
- Why it matters
- How it works
- Example use-cases
- Technical notes for developers

This document is used as a reference for contributors and for AI assistants during code generation.

---

# 1. 🟩 Semantic Automation
**Folder:** `features/semantic_automation`

Natural-language → automation conversion.

### What it does
Users describe an automation in plain English:
> "When I open YouTube, reduce brightness to 10%"  
> "Every day at 6 PM remind me to practice coding."

The system converts it into:
- Trigger
- Conditions
- Actions
- Execution plan

### Why it matters
Makes automation accessible to non-technical users.

### How it works
- On-device NLP (shallow parsing or tiny TFLite model)
- Optional Cloud API integration (OpenAI, Gemini, Groq, DeepSeek, Mistral, Together AI, OpenRouter, or any OpenAI-compatible endpoint)
- Maps text → trigger/action templates
- Suggests corrections in plain language
- AI Engine Hub allows switching between On-Device SLM, Local Server LLM (Ollama), and Cloud API modes

### Example
> "Send a WhatsApp message to Mom when I reach home."

Produces:
- Trigger: location @ home
- Action: send message

---

# 2. 🟦 Conditional Macros
**Folder:** `features/conditional_macros`

### What it does
Enables logic-based automations:
- IF UI element exists → tap
- IF text appears → notify
- IF popup shows → close
- Wait until loading spinner disappears

### Why it matters
Upgrades automations from simple replay to intelligent decision-making.

### How it works
- UI node detection
- Timeouts
- Branching
- Loops
- Fallback actions

### Example
"Tap ‘Skip Ad’ if visible, otherwise continue."

---

# 3. 🟨 Screen Understanding Using On-Device ML
**Folder:** `features/screen_understanding_using_on_device_ml`

### What it does
Gives the automation engine limited “vision”.

### Capabilities
- OCR text detection
- UI element recognition
- Detect if user is watching video
- Recognize patterns (“Order Completed”, “New Message”, etc.)

### Technology
- ML Kit Text Recognition
- TFLite CNN models
- Tiny transformers for classification

### Example
"Scroll until ‘Assignments’ appears."

---

# 4. 🟪 Multi-Step Multi-App Workflow Pipeline
**Folder:** `features/multi_app_workflow_pipeline`

### What it does
Allows RPA-like sequences across multiple apps.

### Example pipeline
1. Open Chrome
2. Search for a product
3. Copy price
4. Open Notes
5. Paste price
6. Add timestamp
7. Save note

### Why it's powerful
This is consumer-grade **RPA (Robotic Process Automation)**.

---

# 5. 🟫 Dynamic UI Path Recording
**Folder:** `features/dynamic_ui_path_recording`

### What it does
Records UI nodes instead of coordinates:
- Node text
- Resource IDs
- Content description
- Hierarchy path

### Why it matters
Works even if:
- Screen size changes
- Orientation changes
- UI shifts slightly

### Example
Instead of:
`Tap(876, 215)`

It records:
`Button[text="Next"]`

---

# 6. 🟧 Robust Error Handling & Recovery
**Folder:** `features/robust_error_handling_recovery`

### What it does
Adds resilience to automations.

### Techniques
- Try → catch → recover
- Wait until condition met
- Loop until element found
- Snapshot screen before retry
- “Scroll until text appears” loops

### Example
If network delay causes UI to load late → automation waits intelligently.

---

# 7. 🟩 Intelligent Repeat-Action Detection (Automation Profile Learning)
**Folder:** `features/automation_profile_learning`

### What it does
Detects user habits and suggests automations.

### Examples
- Notices you open WhatsApp at 9 PM → suggests creating a routine.
- Notices you scroll reels for 10 minutes → suggests auto-scroll.
- Notices repetitive form filling → suggests macro creation.

### How it works
- Simple clustering
- Action frequency tracking
- Local timeline patterns
- On-device ML (lightweight)

---

# 8. 🟦 App-Specific Automation Packs
**Folder:** `features/app_specific_automation`

### What it does
Ships built-in automation templates for specific apps.

### Examples
**Instagram Pack**
- Auto-like
- Auto-scroll
- Auto-open reels

**YouTube Pack**
- Auto skip ads
- Set playback speed
- Auto minimize

### Important
- No server required for core automation features
- No API access required for gesture/macro automation
- Optional Cloud API for higher-intelligence NLP tasks
- Fully local heuristic actions for app-specific packs

---

# 9. 🟥 Cross-Device Automation (LAN-Based)
**Folder:** `features/cross_device_automation`

### What it does
Sync automations between:
- Phone → PC
- PC → phone
- Phone → Tablet

### Examples
- PC receives a call → pause media
- Phone gets WhatsApp → show desktop popup
- PC hotkey triggers phone automation

### Tech possibilities
- WebRTC
- Local Wi-Fi direct
- BLE

---

# 10. ⭐ Automation Debugger
**Folder:** `features/automation_debugger`

### What it does
Developers can play an automation step-by-step.

### Capabilities
- Visual highlight of the node being tapped
- Step-by-step macro playback
- Multi-speed replay
- UI tree inspection
- Execution logs

### Why it matters
This is a **professional-grade debugging tool** rarely found in consumer apps.

---

# 11. 🟦 System Context Automation
**Folder:** `features/system_context_automation`

### What it does
Automations triggered by:
- Battery %
- Wi-Fi on/off
- Bluetooth on/off
- Time schedules
- Location (lat/long/radius from website)
- Headphones connected
- App opened

### Example
“When battery < 20%, reduce brightness to 10%.”

---

# 12. 🟥 Emergency Trigger
**Folder:** `features/emergency_trigger`

### What it does
Instant protective automations.

### Examples
- Panic gesture stops all macros
- Emergency phrase triggers:
    - Silent video recording
    - Send alert
    - Start logging
- Requires explicit user consent & safe UX

---

# 📌 Extra Features from Long-Term Planning (Optional Modules)

These are in your original idea list and can be added as future modules.

---

## 💡 Optional A: Gesture Recording & Playback
**Folder:** `features/gesture_recording_playback`

Already part of your main feature list but worth highlighting.

### What it does
Record gestures using AccessibilityService:
- Tap
- Swipe
- Long-press
- Scroll
- Text input

Replays them safely with constraints.

---

## 💡 Optional B: Semantic Node-Based Replay
(This is combined with Semantic Automation + Dynamic UI Paths)

### Highlights:
- Instead of coordinates → find nodes based on label, content description, hierarchy
- More reliable automation

---

## 💡 Optional C: LAN Mirroring & PC Integration
(Part of Cross-Device Automation)

---

## 💡 Optional D: Automation Packs Marketplace (Local Only)
- Safe future addition
- Users can export/import local automation sets

---

# 🗂 Interaction Between Features

Here’s how modules connect:

| Feature | Uses | Used By |
|---------|------|----------|
| Gesture Recording | AccessibilityService | Dynamic UI Path, Conditional Macros |
| Dynamic UI Path Recording | Recorder | Semantic Automation, Error Handling |
| Conditional Macros | Node Detection | Multi-App Pipelines |
| Screen ML | Enhances detection | Semantic Automation |
| Automation Debugger | All modules | Dev/Testing |
| Automation Profile (Learning) | User behavior logs | Suggestion engine |
| System Context | Triggers | Multi-App Pipelines |
| Cross-Device | Event sync | All |
| Emergency Trigger | Safety Manager | All |

---

# 📜 Summary Table

| ID | Feature Name | Folder | Status |
|----|--------------|--------|--------|
| 1 | Semantic Automation | `semantic_automation` | core feature |
| 2 | Conditional Macros | `conditional_macros` | core feature |
| 3 | Screen Understanding ML | `screen_understanding_using_on_device_ml` | core feature |
| 4 | Multi-App Pipeline | `multi_app_workflow_pipeline` | core feature |
| 5 | Dynamic UI Paths | `dynamic_ui_path_recording` | core feature |
| 6 | Robust Error Handling | `robust_error_handling_recovery` | core feature |
| 7 | Automation Profile Learning | `automation_profile_learning` | core feature |
| 8 | App-Specific Automations | `app_specific_automation` | optional |
| 9 | Cross-Device Automation | `cross_device_automation` | optional |
|10 | Automation Debugger | `automation_debugger` | unique feature |
|11 | System Context Automation | `system_context_automation` | core feature |
|12 | Emergency Trigger | `emergency_trigger` | optional |

---

# 🎉 End of `features.md`

