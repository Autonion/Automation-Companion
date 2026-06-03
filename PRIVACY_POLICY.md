# Privacy Policy for Autonion (Automation Companion)

**Effective Date:** 3 June 2026

Thank you for choosing to be part of the Autonion community. In this document, "Autonion", "we", "us", and "our" refer to the developer(s) of the Autonion application. We are committed to protecting your personal information and your right to privacy. If you have any questions or concerns about this privacy notice, or our practices with regards to your personal information, please contact us at <gurupreetam.ai@gmail.com>.

This Privacy Policy describes how your personal information is collected, used, and shared when you install and use the Autonion Android application (the "App").

## 1. Important Declarations Regarding Sensitive Permissions

To provide advanced automation features, Autonion requires several system-level permissions. We are committed to transparency regarding how we use these permissions:

### Accessibility Service API
The App utilizes Android's Accessibility Service API to perform automated actions on your behalf (such as simulating clicks, gestures, and reading screen nodes to navigate user interfaces). 
* **Why it is required:** This service is the core engine of our automation framework. It powers features including the **Flow Builder** (executing custom visual macros), **Semantic AI Agent**, **Gesture Recording**, **UI Recognition AI**, **Visual Triggers**, **System Context Automations** (performing actions triggered by system events), **Cross-Device Sync** (remote execution of tasks), and **Omni-Chat** (conversational UI interaction). It allows the App to interact with other applications on your device based on your configured workflows.
* **Data Collection and Sharing:** We **do not** use the Accessibility Service to secretly collect your personal data, intercept your passwords, or monitor your private conversations. Any screen context gathered via this service is processed locally on your device by default. If you actively choose to use a "Cloud API" (like OpenAI, Gemini, etc.) for Semantic Automation or Omni-Chat, the necessary screen context will be securely transmitted to that chosen provider solely for the purpose of executing the automation task.

### Media Projection API (Screen Capture)
The App uses the Media Projection API to capture screen content for Visual Triggers and Screen Understanding.
* **Why it is required:** This permission allows the App to visually parse the screen to understand UI states and trigger specific automations across features like the **Flow Builder**, **Semantic AI Agent**, **UI Recognition AI**, **Visual Triggers**, and **Omni-Chat**.
* **Data Collection and Sharing:** We **do not** record your screen for tracking purposes. Images and screen data captured are processed ephemerally, except when you explicitly save them as part of a Visual Trigger or Flow Builder configuration. Any saved images are stored strictly locally on your device's storage. Depending on your configured AI Inference Mode, temporary screen data may be processed entirely off-line (Local SLM/LLM) or sent to your explicitly configured Cloud AI provider to understand the screen context. We do not store your screen captures on our servers or sell them to third parties.

### Location Data (Including Background Location)
The App requests access to your location data (Foreground and Background).
* **Why it is required:** This allows the App to trigger location-based automations (e.g., executing a workflow when you arrive at a specific geofence).
* **Data Collection and Sharing:** You can manually input coordinates for triggers. Optionally, you may use our external map service (hosted on Vercel) to select map coordinates. This web service operates statelessly to simply return the selected coordinates back to the App and **does not** store, log, or track your real-time location. Once the coordinates are set, your live location data is used purely on-device to evaluate automation triggers. We do not track your location on our servers or sell your location history to third parties.

### Contacts and SMS
The App requests permissions to read contacts and send SMS messages.
* **Why it is required:** This enables you to build automation workflows that can automatically send text messages or look up contact information based on your triggers.
* **Data Collection and Sharing:** Your contact lists and messages remain on your device. We do not harvest your contacts or upload your messages to our servers.

## 2. Other System Permissions Used
* **Storage Access:** Required to save your automation flows, presets, and export data.
* **System Alert Window:** Required to display overlays (like the Automation Editor, Chatbot, and Gesture Recording UI) on top of other applications.
* **Query All Packages:** Required so you can select specific apps to launch or target within your automation flows.
* **Battery & Wake Locks:** Required to ensure your scheduled automations run reliably even when the device is asleep.

### Age Verification Data (Google Play Age Signals API)
In jurisdictions where age verification is legally required (currently the U.S. state of Texas under SB 2420, with potential expansion to other states), the App receives age-range signals from Google Play.
* **What data is received:** An age band (e.g., 13–17, 18+) and a verification/supervision status (e.g., Verified, Supervised). We do **not** receive your exact date of birth.
* **How it is used:** This data is used **solely** to comply with applicable age verification laws — specifically, to respect parental access decisions made through Google Play's parental controls.
* **What we do NOT do:** We do **not** use this data for advertising, analytics, profiling, marketing, or any purpose other than legal compliance.
* **Storage:** The age-range signal is cached locally on your device for offline access. It is **not** transmitted to our servers or any third party.
* **Non-regulated regions:** If you are not in a jurisdiction with an active age verification law, no age data is received or processed.

## 3. Data Processing, Ecosystem Integrations, and Cloud Providers

Autonion is built with an "Offline-First, Cloud-Enhanced" architecture that spans the Android App, the Autonion Desktop Agent, and Browser Extensions (e.g., Lemur Browser extension).
* **Local Processing:** By default, or when using Local SLMs or a Local Server LLM (like your own Ollama server), your data never leaves your local network. 
* **Autonion Ecosystem (Desktop Agent & Extensions):** If you use Cross-Device Sync, the App securely transmits automation commands and context over your local network to the Autonion Desktop Agent or Browser Extensions. We do not intermediate this connection with a central server.
* **Cloud AI Processing:** If you opt-in to use Cloud APIs (e.g., OpenAI, Anthropic, Google Gemini) either in the Android App or via the Desktop Agent to power the Semantic Automation Engine, the relevant context (text or screen representations) required to fulfill your prompt will be sent to those third-party providers. Their use of your data is governed by their respective Privacy Policies. We recommend reviewing the privacy policies of the AI providers you choose to connect.

## 4. Do We Share Your Information?
We do not sell, trade, or rent your personal identification information to others. The App does not contain third-party ad networks or hidden tracking SDKs. Data transmission only occurs based on the explicit API configurations you set up within the App (e.g., your configured Cloud LLM provider).

## 5. Security of Your Information
We use administrative, technical, and physical security measures to help protect your personal information. While we have taken reasonable steps to secure the personal information you provide to us, please be aware that despite our efforts, no security measures are perfect or impenetrable.

## 6. Open Source and Transparency
Autonion is an open-source project. We believe that applications requiring system-level permissions should operate with complete transparency. You can review the source code of the App, Desktop Agent, and Extensions, audit our data handling practices, and contribute to the project on our GitHub repository: <https://github.com/Autonion>.

## 7. Children's Privacy
Our App does not address anyone under the age of 13. We do not knowingly collect personally identifiable information from children under 13. In jurisdictions with age verification laws (such as Texas SB 2420), we use the Google Play Age Signals API to receive age-range signals and respect parental controls. If a parent or guardian has denied access to this App through Google Play's parental controls, the App will display a restriction screen and prevent usage until the parent updates their settings.

## 8. Your Data Protection Rights (GDPR, CCPA, CalOPPA)
Depending on your location (such as the EU or California), you have specific rights regarding your personal data, including the right to request access, deletion, or restriction of your data. 
Because Autonion processes your data locally on your device and **does not** collect, store, or sell your personal data on our servers, we inherently comply with data minimization principles. There is no personal data stored on our servers for us to delete or provide upon request. If you actively connect a third-party Cloud AI provider, any requests regarding data processed by them must be directed to that specific provider.

## 9. Changes to This Privacy Policy
We may update our Privacy Policy from time to time. We will notify you of any changes by posting the new Privacy Policy on this page and updating the "Effective Date" at the top. You are advised to review this Privacy Policy periodically for any changes.

## 10. Contact Us
If you have questions or comments about this Privacy Policy, please contact:
Guru Preetam (Developer of Autonion)
<gurupreetam.ai@gmail.com>
<https://github.com/Autonion>
