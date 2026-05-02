# ✅ **PROJECT_OVERVIEW.md (Final Master Document)**

**Copy/Paste this into `docs/PROJECT_OVERVIEW.md`**

---

# **AI-Powered Offline-First, Cloud-Enhanced Automation Companion — Project Overview**

## 📌 Purpose

This document defines the **complete architecture**, **project structure**, **feature modules**, **coding rules**, and **team workflow** for the Automation Companion app.
It also serves as a reference for **AI assistants (e.g., ChatGPT)** to understand the project without needing full context every time.

**Any teammate using AI MUST paste:**

> “Use docs/PROJECT_OVERVIEW.md as reference for this project”
> before asking AI to generate code.

---

# 1. 🚀 **Project Summary**

The Automation Companion is an **offline-first, cloud-enhanced** Android app that allows users to create automations using:

* Triggers
* Conditions
* Actions
* Gesture recording
* Multi-step pipelines
* System context triggers
* On-device ML
* Emergency triggers
* **Optional Cloud API integration** (OpenAI, Gemini, Groq, DeepSeek, Mistral, Together AI, OpenRouter)

All core features run **on-device** with **no external servers required**.
An optional **Cloud API** mode enables users to leverage powerful third-party LLMs for higher-intelligence tasks.
The app includes a **visual workflow builder**, **Accessibility-based recorder**, **macro replay engine**, and **feature-specific modules**.

> ⚠️ **Privacy Notice:** When Cloud API mode is enabled, user prompts and automation commands are sent to the selected third-party API provider. API keys are encrypted on-device with AES-256-GCM via Android Keystore. Autonion does not collect, store, or forward any user data.

---

# 2. 📁 **Project Architecture**

We use a **multi-layered modular structure**:

```
app/
  src/main/java/com/example/automationcompanion/
    ui/                  # UI screens, navigation, components
    core/                # Interfaces, models, contracts
    data/                # Implementations, repositories, stub data
    features/            # Each feature is isolated here
    engine/              # Macro runner, evaluators, services
docs/                    # Project documentation
.github/                 # PR templates, CI, issue templates
```

### 🌟 **UI Layer (`ui/`)**

* HomeScreen
* Navigation host
* Feature cards
* Common components
* App theme
* Previews

### 🌟 **Core Layer (`core/`)**

* Data models: Automation, Macro, Action, Trigger
* Interfaces: AutomationRepository, MacroRepository, RecorderContract
* Exceptions, Result wrappers

### 🌟 **Data Layer (`data/`)**

* Stub repositories (mock data)
* Future: Room database storage
* JSON serialization

### 🌟 **Features (`features/`)**

Each feature has its **own folder**, **own screen**, and **own README**.

Example:

```
features/
  gesture_recording_playback/
    GestureRecordingScreen.kt
    README.md
```

### 🌟 **Engine (`engine/`)**

* Macro playback
* Condition evaluator
* Safety rules
* Execution logs

---

# 3. 🧩 **Features List & Descriptions**

Each feature is implemented inside:

```
features/<feature_name>/
```

Below is the official list with canonical names (AI should use *exact* names):

| Feature Folder Name                     | Description                                                   |
| --------------------------------------- | ------------------------------------------------------------- |
| gesture_recording_playback              | Record user gestures using Accessibility and replay as macros |
| dynamic_ui_path_recording               | UI path-based recording (selectors instead of coordinates)    |
| screen_understanding_using_on_device_ml | On-device ML to detect UI elements, OCR, semantics            |
| semantic_automation                     | Natural language → automation graph (local SLM, local server LLM, or Cloud API) |
| conditional_macros                      | Add conditions, branching, and guards                         |
| multi_step_multi_app_pipeline           | Run multi-app automation sequences                            |
| robust_error_handling_recovery          | Retry logic, fallback, error boundaries                       |
| app_specific_automation                 | Per-app optimized automation handlers                         |
| automation_debugger                     | Step-through automation inspector                             |
| cross_device_automation                 | Local Wi-Fi/Bluetooth multi-device sync                       |
| system_context_automation               | Battery, location, Wi-Fi, time triggers                       |
| emergency_trigger                       | Panic gestures/phrases triggering safety macros               |

---

# 4. 🌉 **Navigation Rules**

Each feature exposes a **single entry screen** called:

```
<FeatureName>Screen.kt
```

Example:

```
GestureRecordingScreen.kt
SemanticAutomationScreen.kt
```

Navigation is handled inside:

```
ui/AppNavHost.kt
```

Each feature route must follow:

```
"feature/<folder-name>"
```

Example:

```
"feature/gesture_recording_playback"
```

---

# 5. 🎨 **UI Guidelines**

1. Only **HomeScreen** lives at top level.
2. Each feature has **one entry screen**.
3. All UI must use **Jetpack Compose**.
4. Theme must use **AppTheme**.
5. Use MVVM later when implementing real data.

---

# 6. 🧱 **Backend Guidelines**

### Models (`core/model/`)

* `Automation.kt`
* `Macro.kt`
* `Trigger.kt`
* `Action.kt`
* `ExecutionResult.kt`

### Repositories (`core/repo/`)

* `AutomationRepository`
* `MacroRepository`

### All repositories must have:

* Stub implementation first
* Room/real implementation later
* DI-friendly (Hilt later)

---

# 7. 🧪 **Development Workflow**

### Branch Strategy

* `main` — stable
* `develop` — integration
* `feature/<name>-<dev>` — individual work

### PR Rules

* Always PR → `develop`
* CI must pass
* PR template must be completed
* At least 1 reviewer approval

### No direct pushes to main/develop.

---

# 8. 🤖 **How to Use AI for This Project**

Any teammate using ChatGPT/Copilot **must start with:**

> “Refer to docs/PROJECT_OVERVIEW.md for project structure.”

Then describe the feature or file they want to generate.

### Why?

AI needs:

* Folder names
* Architecture rules
* Navigation pattern
* Data layering
* Naming conventions

This file gives all missing context.

### Example:

> “Refer to PROJECT_OVERVIEW.md.
> Generate a `GestureRecordingViewModel.kt` that follows the architecture.
> It should live in `features/gesture_recording_playback/`.”

AI will then stay consistent.

---

# 9. 🛠️ **How to Add a New Feature**

1. Create folder under `/features/`
2. Create entry screen
3. Add README.md inside feature folder
4. Add navigation route in AppNavHost
5. Add card in HomeScreen
6. Implement ViewModel (if needed)
7. Implement repository logic (optional at first)

---

# 10. 🌐 **.github Rules (CI, PR template, issue templates)**

### Included:

* Automatic Android build/test pipeline
* PR template
* Issue templates
* Commented-out CODEOWNERS file
* Dependabot

### Purpose:

To ensure:

* No broken code
* Consistent PRs
* Clear feature requests
* Up-to-date dependencies

---

# 11. 📚 **Important Docs Directory**

```
docs/
  PROJECT_OVERVIEW.md      <-- THIS FILE
  features.md              <-- Feature descriptions
  getting-started.md       <-- Developer onboarding
  architecture.md          <-- Optional expansion
```

---

# 12. 🧑‍🤝‍🧑 **Team Example Usage**

If Alice wants to implement semantic automation:

* She creates branch `feature/semantic-alice`
* Reads her folder README
* Reads PROJECT_OVERVIEW.md
* Uses AI:

  > "Refer to PROJECT_OVERVIEW.md, generate SemanticAutomationScreen.kt"
* Makes PR → CI → Review → Merge

If Guru needs to take over:

* Just branch off from develop
* AI continues following rules because the overview defines architecture.

---

# 🎉 **End of Master Document**
