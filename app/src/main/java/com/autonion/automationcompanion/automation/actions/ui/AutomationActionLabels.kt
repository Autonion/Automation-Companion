package com.autonion.automationcompanion.automation.actions.ui

import com.autonion.automationcompanion.automation.actions.models.AutomationAction

/** Explicit UI labels stay readable when R8 renames action model classes. */
fun AutomationAction.displayLabel(): String = when (this) {
    is AutomationAction.SendSms -> "Send SMS"
    is AutomationAction.SetVolume -> "Set volume"
    is AutomationAction.SetBrightness -> "Set brightness"
    is AutomationAction.SetDnd -> "Do Not Disturb"
    is AutomationAction.SetAutoRotate -> "Auto-rotate"
    is AutomationAction.SetScreenTimeout -> "Screen timeout"
    is AutomationAction.SetKeepScreenAwake -> "Keep screen awake"
    is AutomationAction.AppAction -> "App"
    is AutomationAction.NotificationAction -> "Notification"
    is AutomationAction.SetBatterySaver -> "Battery saver"
}
