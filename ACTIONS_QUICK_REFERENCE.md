# Quick Reference: Automation Actions Module

## 📂 File Locations

### New Shared Module (Trigger-Agnostic)
```
features/automation/actions/
├── models/
│   ├── AutomationAction.kt       # Sealed class: SendSms, SetVolume, SetBrightness, SetDnd
│   └── ConfiguredAction.kt       # Sealed class: Audio, Brightness, Dnd, SendSms
├── ui/
│   ├── ActionComponents.kt       # Internal components: ActionRow, AudioActionConfig, etc.
│   └── ActionPicker.kt           # @Composable fun ActionPicker(...)
└── builders/
    └── ActionBuilder.kt          # object ActionBuilder: buildActions(), isValid(), hasAnyValidAction()
```

### Updated Location Module
```
features/system_context_automation/location/
├── ui/
│   ├── SlotConfigScreen.kt       # ✅ Refactored: Uses ActionPicker
│   └── SlotConfigActivity.kt     # ✅ Refactored: Simplified state (configuredActions)
├── data/models/Slot.kt           # ✅ Updated import
├── data/db/
│   ├── TypeConverter.kt          # ✅ Updated import
│   └── Migrations.kt             # ✅ Updated import
├── executor/ActionExecutor.kt    # ✅ Updated import
├── helpers/SendHelper.kt         # ✅ Updated import
└── permissions/PermissionPreflight.kt  # ✅ Updated import
```

## 🔑 Key Classes

### ConfiguredAction (UI State)
```kotlin
sealed class ConfiguredAction {
    data class Audio(val ringVolume: Int, val mediaVolume: Int)
    data class Brightness(val level: Int)
    data class Dnd(val enabled: Boolean)
    data class SendSms(val message: String, val contactsCsv: String)
}
```

### AutomationAction (Executable, Serializable)
```kotlin
@Serializable
sealed class AutomationAction {
    @SerialName("send_sms")
    data class SendSms(val message: String, val contactsCsv: String)
    
    @SerialName("set_volume")
    data class SetVolume(val ring: Int, val media: Int)
    
    @SerialName("set_brightness")
    data class SetBrightness(val level: Int)
    
    @SerialName("set_dnd")
    data class SetDnd(val enabled: Boolean)
}
```

### ActionBuilder
```kotlin
object ActionBuilder {
    fun buildActions(configuredActions: List<ConfiguredAction>): List<AutomationAction>
    fun isValid(config: ConfiguredAction): Boolean
    fun hasAnyValidAction(configuredActions: List<ConfiguredAction>): Boolean
}
```

### ActionPicker (Main UI Component)
```kotlin
@Composable
fun ActionPicker(
    configuredActions: List<ConfiguredAction>,
    onActionsChanged: (List<ConfiguredAction>) -> Unit,
    onPickContactClicked: (actionIndex: Int) -> Unit,
    dndDisabledReason: String? = null
)
```

## 🎯 Usage Example (Location Trigger)

### Before Refactoring
```kotlin
SlotConfigScreen(
    // ... 20 parameters for individual toggles and values
    smsEnabled = smsEnabled,
    onSmsEnabledChange = { smsEnabled = it },
    message = message,
    onMessageChanged = { message = it },
    contactsCsv = contactsCsv,
    onPickContactClicked = { pickContact() },
    volumeEnabled = volumeEnabled,
    ringVolume = ringVolume,
    onVolumeEnabledChange = { volumeEnabled = it },
    onRingVolumeChange = { ringVolume = it },
    mediaVolume = mediaVolume,
    onMediaVolumeChange = { mediaVolume = it },
    // ... brightness and DND params
)
```

### After Refactoring
```kotlin
SlotConfigScreen(
    // Trigger-specific config
    latitude = lat,
    longitude = lng,
    radiusMeters = radius,
    startLabel = startLabel,
    endLabel = endLabel,
    selectedDays = selectedDays,
    remindBeforeMinutes = remindBeforeMinutes,
    
    // Action config (delegated!)
    configuredActions = configuredActions,
    onActionsChanged = { configuredActions = it },
    onPickContactClicked = { actionIndex ->
        contactPickerActionIndex = actionIndex
        pickContact()
    },
    volumeEnabled = configuredActions.any { it is ConfiguredAction.Audio }
)
```

## 🔄 Data Flow

### Saving
```
User enables Volume action in ActionPicker
    ↓
onActionsChanged() called with updated List<ConfiguredAction>
    ↓
Activity updates: configuredActions = it
    ↓
User clicks Save
    ↓
ActionBuilder.buildActions(configuredActions) → List<AutomationAction>
    ↓
Save to database and execute
```

### Loading
```
Load Slot from database (has List<AutomationAction>)
    ↓
populateFromSlot() maps AutomationAction → ConfiguredAction
    ↓
Activity updates: configuredActions = [ConfiguredAction.Audio(...), ...]
    ↓
ActionPicker displays loaded actions
```

## ✅ Implementation Checklist for New Triggers

To add a **new trigger type** (e.g., Battery, App Foreground):

- [ ] Create `BatteryConfigScreen.kt` in `features/system_context_automation/battery/ui/`
- [ ] Include the SAME `ActionPicker` component (no changes needed!)
- [ ] Manage trigger-specific state (battery threshold, charging state, etc.)
- [ ] Call ActionBuilder when saving: `ActionBuilder.buildActions(configuredActions)`
- [ ] Done! Actions UI is 100% reusable ✨

## 🧪 Testing Utilities

### ActionBuilder Validation
```kotlin
// Pre-save validation
if (!ActionBuilder.hasAnyValidAction(configuredActions)) {
    button.enabled = false
}

// Single action validation
if (ActionBuilder.isValid(smsAction)) {
    // Safe to convert
}
```

### Action Conversion
```kotlin
val automationActions = ActionBuilder.buildActions(configuredActions)
// Now ready to save/execute - all invalid configs filtered out
```

## 📝 Architecture Principles

| Principle | Implementation |
|-----------|-----------------|
| **Trigger-Agnostic** | Actions don't import/reference location, battery, etc. |
| **Composable** | New actions = new ConfiguredAction + new AutomationAction + new UI |
| **Validated** | ActionBuilder ensures no invalid actions reach database |
| **Reusable** | ActionPicker works for any trigger without modification |
| **Testable** | Each component has clear, single responsibility |

## 🚨 Common Mistakes to Avoid

❌ Don't put action code inside location package
✅ Use `features/automation/actions/` for all action logic

❌ Don't modify SlotConfigScreen for new actions
✅ Extend ActionPicker and ActionComponents

❌ Don't validate actions in triggers
✅ Use ActionBuilder for all validation

❌ Don't store ConfiguredAction in database
✅ Convert to AutomationAction first

✅ Do reuse ActionPicker in all new triggers
✅ Do extend ConfiguredAction for new action types
✅ Do add UI component to ActionComponents.kt
✅ Do handle conversion in ActionBuilder.kt

## 📚 Related Documentation

- **ARCHITECTURE_REFACTORING.md** - Comprehensive design rationale
- **ActionPicker.kt** - Detailed inline comments explaining reusability
- **ActionBuilder.kt** - Validation logic documentation
