package com.example.automationcompanion.features.gesture_recording_playback.managers

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {

    private const val PREFS_NAME = "AutomationSettings"
    private const val KEY_LOOP_COUNT = "loop_count"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveLoopCount(context: Context, count: Int) {
        getPrefs(context).edit().putInt(KEY_LOOP_COUNT, count).apply()
    }

    fun getLoopCount(context: Context): Int {
        return getPrefs(context).getInt(KEY_LOOP_COUNT, 1) // Default to 1 loop
    }
}
