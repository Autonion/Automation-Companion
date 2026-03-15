package com.autonion.automationcompanion.features.semantic_automation.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * Resolves app names to package names and launches them.
 * Used by the engine to open the target app before entering the screen loop.
 */
object AppLauncher {

    private const val TAG = "AppLauncher"

    /**
     * Well-known app name → package name mapping.
     */
    private val PACKAGE_MAP = mapOf(
        "amazon" to "com.amazon.mShop.android.shopping",
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "playstore" to "com.android.vending",
        "twitter" to "com.twitter.android",
        "spotify" to "com.spotify.music",
        "facebook" to "com.facebook.katana",
        "telegram" to "org.telegram.messenger",
        "netflix" to "com.netflix.mediaclient",
        "uber" to "com.ubercab",
        "camera" to "com.android.camera",
        "calculator" to "com.google.android.calculator",
        "clock" to "com.google.android.deskclock",
        "calendar" to "com.google.android.calendar",
        "contacts" to "com.google.android.contacts",
        "messages" to "com.google.android.apps.messaging",
        "phone" to "com.google.android.dialer",
        "files" to "com.google.android.documentsui",
        "settings" to "com.android.settings"
    )

    /**
     * System-level intents for specific tasks.
     */
    fun launchSystemAction(context: Context, task: String, query: String?): Boolean {
        return try {
            val intent = when (task) {
                "enable" -> when {
                    query?.contains("wifi", ignoreCase = true) == true ->
                        Intent(Settings.ACTION_WIFI_SETTINGS)
                    query?.contains("bluetooth", ignoreCase = true) == true ->
                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    query?.contains("location", ignoreCase = true) == true ->
                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    query?.contains("nfc", ignoreCase = true) == true ->
                        Intent(Settings.ACTION_NFC_SETTINGS)
                    query?.contains("airplane", ignoreCase = true) == true ||
                    query?.contains("flight", ignoreCase = true) == true ->
                        Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                    else -> Intent(Settings.ACTION_SETTINGS)
                }
                "disable" -> when {
                    query?.contains("wifi", ignoreCase = true) == true ->
                        Intent(Settings.ACTION_WIFI_SETTINGS)
                    query?.contains("bluetooth", ignoreCase = true) == true ->
                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    else -> Intent(Settings.ACTION_SETTINGS)
                }
                else -> return false
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.d(TAG, "Launched system action for task='$task', query='$query'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch system action", e)
            false
        }
    }

    /**
     * Launch an app by its alias name (e.g. "amazon", "settings").
     * Returns true if successfully launched.
     */
    fun launchApp(context: Context, appAlias: String): Boolean {
        // Special case: Settings
        if (appAlias == "settings") {
            return try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.d(TAG, "Launched Settings")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch Settings", e)
                false
            }
        }

        // Look up package name
        val packageName = PACKAGE_MAP[appAlias.lowercase()]

        if (packageName != null) {
            return launchByPackage(context, packageName)
        }

        // Fallback: try the alias as a raw package name
        if (appAlias.contains(".")) {
            return launchByPackage(context, appAlias)
        }

        // Last resort: search for the app name in installed apps
        return searchAndLaunch(context, appAlias)
    }

    private fun launchByPackage(context: Context, packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                Log.d(TAG, "Launched: $packageName")
                true
            } else {
                Log.w(TAG, "No launch intent for: $packageName")
                // Try Play Store
                launchPlayStore(context, packageName)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch: $packageName", e)
            false
        }
    }

    /**
     * Search installed apps by label name (fuzzy match).
     */
    private fun searchAndLaunch(context: Context, appName: String): Boolean {
        return try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(mainIntent, 0)

            val match = apps.firstOrNull { resolveInfo ->
                resolveInfo.loadLabel(pm).toString().contains(appName, ignoreCase = true)
            }

            if (match != null) {
                val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(launchIntent)
                    Log.d(TAG, "Found and launched: ${match.activityInfo.packageName} (label match for '$appName')")
                    return true
                }
            }

            Log.w(TAG, "App not found on device: $appName")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error searching for app: $appName", e)
            false
        }
    }

    private fun launchPlayStore(context: Context, packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) { }
    }
}
