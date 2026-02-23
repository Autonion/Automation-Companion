<p align="center">
  <h1 align="center">🤖 Automation Companion</h1>
  <p align="center">
    <b>AI-Powered, On-Device Android Automation — No Root, No Cloud, No Limits.</b>
  </p>
  <p align="center">
    <a href="#-features">Features</a> •
    <a href="#%EF%B8%8F-architecture">Architecture</a> •
    <a href="#-getting-started">Getting Started</a> •
    <a href="#-tech-stack">Tech Stack</a> •
    <a href="#-contributing">Contributing</a> •
    <a href="#-license">License</a>
  </p>
  <p align="center">
    <img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white" alt="Platform" />
    <img src="https://img.shields.io/badge/Min%20SDK-24-blue" alt="Min SDK" />
    <img src="https://img.shields.io/badge/Target%20SDK-36-blue" alt="Target SDK" />
    <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
    <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
    <img src="https://img.shields.io/badge/License-PolyForm%20NC%201.0-orange" alt="License" />
  </p>
</p>

---

## 📖 Overview

**Automation Companion** is an offline-first Android application that empowers users to create powerful automations — from simple gesture macros to complex, multi-app workflows — entirely on-device. It leverages **Accessibility Services**, **on-device ML (TFLite + ML Kit)**, **screen capture**, and **system event receivers** to automate virtually anything on your phone, without requiring root access or cloud connectivity.

Think of it as **Tasker meets IFTTT meets RPA** — built natively for modern Android with Jetpack Compose.

---

## ✨ Features

### 🎯 Core Automation

| Feature | Description |
|---------|-------------|
| **🎮 Gesture Recording & Playback** | Record taps, swipes, long-presses, scrolls, and text inputs via AccessibilityService and replay them as macros |
| **🔀 Flow Automation** | Visual node-based workflow editor with drag-to-connect edges, conditional branching, and multi-node execution |
| **🧠 Screen Understanding (ML)** | On-device ML-powered screen analysis — OCR text detection, UI element recognition, and image template matching |
| **👁️ Visual Triggers** | Configure screen-capture-based triggers that watch for specific visual patterns and execute actions automatically |

### ⚡ System Context Triggers

| Trigger | Description |
|---------|-------------|
| **📍 Location** | Geofence-based automations with radius, day-of-week, and time-window filtering |
| **🔋 Battery** | Trigger actions based on battery level thresholds and charging state |
| **⏰ Time of Day** | Schedule automations using exact alarms for precise time-based triggers |
| **📶 Wi-Fi** | React to Wi-Fi connect/disconnect events and specific network names |
| **📱 App Specific** | Per-app automation handlers triggered when specific apps are opened |

### 🛠️ Tools & Utilities

| Tool | Description |
|------|-------------|
| **🐛 Automation Debugger** | Step-through inspector with categorized logs for every feature module — debug your automations in real time |
| **🌐 Cross-Device Automation** | LAN-based multi-device sync (Phone ↔ PC ↔ Tablet) via local Wi-Fi |
| **📦 Reusable Action System** | Trigger-agnostic actions (SMS, volume, brightness, DnD) shareable across all trigger types |

---

## 🏗️ Architecture

The project follows a **multi-layered modular architecture** with strict separation of concerns:

```
app/src/main/java/com/autonion/automationcompanion/
├── ui/                          # Home screen, navigation, theme, shared components
├── core/                        # Interfaces, models, contracts
├── automation/                  # Shared action system (trigger-agnostic)
│   └── actions/                 # ActionPicker, ActionBuilder, ConfiguredAction
└── features/                    # Feature modules (isolated)
    ├── flow_automation/         # Visual workflow editor + execution engine
    │   ├── ui/editor/           # Canvas, ViewModel, node rendering
    │   ├── engine/              # FlowExecutionService, node executors
    │   └── model/               # FlowDefinition, NodeData, Edge
    ├── gesture_recording_playback/
    │   └── overlay/             # OverlayService, AutomationService (Accessibility)
    ├── screen_understanding_ml/ # TFLite + ML Kit screen analysis
    ├── visual_trigger/          # Vision-based trigger service
    ├── system_context_automation/
    │   ├── location/            # Geofencing, tracking service, Room DB
    │   ├── battery/             # Battery monitoring service
    │   ├── timeofday/           # Alarm-based scheduling
    │   ├── wifi/                # Wi-Fi state receiver
    │   └── app_specific/        # Per-app automation handlers
    ├── automation_debugger/     # Runtime log inspector
    ├── cross_device_automation/ # LAN sync engine
    └── settings/                # App preferences
```

### Key Design Principles

- **Offline-First** — Everything runs on-device, no external servers
- **Modular Features** — Each feature is fully isolated with its own UI, engine, and models
- **Trigger-Agnostic Actions** — Actions (SMS, volume, brightness, DnD) are decoupled from triggers and reusable everywhere
- **MVVM Pattern** — ViewModels manage UI state; clean separation between UI and business logic
- **Accessibility-Powered** — Gesture replay and UI inspection via Android AccessibilityService

---

## 🚀 Getting Started

### Prerequisites

| Tool | Version |
|------|---------|
| **Android Studio** | Ladybug or later |
| **JDK** | 17 |
| **Gradle** | Wrapper included (`./gradlew`) |
| **Android SDK** | API 24 – 36 |

### Setup

```bash
# Clone the repository
git clone https://github.com/Autonion/Automation-Companion.git
cd Automation-Companion

# Open in Android Studio and let Gradle sync, then Run
# — or build via terminal —
./gradlew assembleDebug
```

### Required Permissions

The app requests several permissions at runtime for its automation capabilities:

| Permission | Used For |
|------------|----------|
| Accessibility Service | Gesture recording, replay, and UI inspection |
| Overlay (Draw Over Apps) | Floating control panels and recording UI |
| Media Projection | Screen capture for ML analysis and visual triggers |
| Location | Geofence-based automations |
| Exact Alarms | Time-of-day scheduling |
| Notification Access | Posting foreground service notifications |

---

## 🧰 Tech Stack

| Layer | Technology |
|-------|------------|
| **Language** | Kotlin |
| **UI Framework** | Jetpack Compose + Material 3 |
| **Navigation** | Compose Navigation |
| **Database** | Room (with KSP) |
| **ML / Vision** | TensorFlow Lite (LiteRT), ML Kit Text Recognition |
| **Native** | C++ via CMake (OpenCV integration) |
| **Networking** | OkHttp (LAN cross-device sync) |
| **Serialization** | Kotlinx Serialization, Gson |
| **Async** | Kotlin Coroutines, WorkManager |
| **Location** | Google Play Services Location, OSMDroid maps |
| **Build System** | Gradle KTS with Version Catalogs |
| **CI/CD** | GitHub Actions |

---

## 📂 Documentation

| Document | Description |
|----------|-------------|
| [`docs/PROJECT_OVERVIEW.md`](docs/PROJECT_OVERVIEW.md) | Complete architecture, coding rules, and team workflow |
| [`docs/features.md`](docs/features.md) | Detailed descriptions of every feature module |
| [`docs/getting-started.md`](docs/getting-started.md) | Developer onboarding guide |
| [`ACTIONS_QUICK_REFERENCE.md`](ACTIONS_QUICK_REFERENCE.md) | Quick reference for the shared Actions module |
| [`ARCHITECTURE_REFACTORING.md`](ARCHITECTURE_REFACTORING.md) | Design rationale for the action system refactoring |

---

## 🤝 Contributing

We welcome contributions! Here's how to get started:

1. **Fork** the repository
2. **Create a feature branch** from `develop`:
   ```bash
   git checkout develop
   git checkout -b feature/<feature-name>-<your-name>
   ```
3. **Make your changes** following the [project architecture](docs/PROJECT_OVERVIEW.md)
4. **Open a Pull Request** → `develop` using the [PR template](.github/PULL_REQUEST_TEMPLATE.md)
5. Ensure **CI passes** and get at least **1 reviewer approval**

### Branch Strategy

| Branch | Purpose |
|--------|---------|
| `main` | Stable releases |
| `develop` | Integration branch |
| `feature/*` | Individual feature work |

> ⚠️ **No direct pushes to `main` or `develop`.**

---

## 📄 License

This project is licensed under the [**PolyForm Noncommercial License 1.0.0**](LICENSE).

You are free to use, modify, and distribute this software for **noncommercial purposes** — personal use, research, education, hobby projects, and more. Commercial use is not permitted under this license.

---

<p align="center">
  Made with ❤️ by the <b>Autonion</b> team
</p>
