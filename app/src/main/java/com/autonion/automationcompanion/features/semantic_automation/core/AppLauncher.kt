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

    // ── App Category System ────────────────────────────────────

    /**
     * Categories of apps, used to show relevant suggestions when a user
     * command is ambiguous (e.g. "play X" without specifying which app).
     */
    enum class AppCategory { MEDIA, SHOPPING, MESSAGING, MAPS, ALL }

    /** Well-known packages per category. */
    private val CATEGORY_PACKAGES = mapOf(
        AppCategory.MEDIA to listOf(
            "com.google.android.youtube",      // YouTube
            "com.spotify.music",               // Spotify
            "com.netflix.mediaclient",         // Netflix
            "com.amazon.avod.thirdpartyclient",// Prime Video
            "com.disney.disneyplus",           // Disney+
            "com.apple.android.music",         // Apple Music
            "com.gaana",                       // Gaana
            "com.jio.media.jiobeats",          // JioSaavn
            "com.bsbportal.music",             // Wynk Music
            "com.hungama.myplay.activity",     // Hungama
            "com.google.android.apps.youtube.music", // YouTube Music
            "deezer.android.app",              // Deezer
            "com.soundcloud.android",          // SoundCloud
            "com.pandora.android",             // Pandora
            "tv.twitch.android.app",           // Twitch
            "com.mxtech.videoplayer.ad",       // MX Player
            "org.videolan.vlc",                // VLC
        ),
        AppCategory.SHOPPING to listOf(
            "com.amazon.mShop.android.shopping", // Amazon
            "com.flipkart.android",              // Flipkart
            "com.myntra.android",                // Myntra
            "com.snapdeal.main",                 // Snapdeal
            "in.amazon.mShop.android.shopping",  // Amazon India
            "com.meesho.supply",                 // Meesho
            "club.cred",                         // CRED
            "com.shopsy.android",                // Shopsy
        ),
        AppCategory.MESSAGING to listOf(
            "com.whatsapp",                      // WhatsApp
            "org.telegram.messenger",            // Telegram
            "com.google.android.apps.messaging", // Messages
            "com.facebook.orca",                 // Messenger
            "com.discord",                       // Discord
            "com.Slack",                         // Slack
            "com.skype.raider",                  // Skype
            "com.instagram.android",             // Instagram DM
        ),
        AppCategory.MAPS to listOf(
            "com.google.android.apps.maps",      // Google Maps
            "com.waze",                          // Waze
            "com.here.app.maps",                 // HERE Maps
            "com.sygic.aura",                    // Sygic
        ),
    )

    /**
     * Maps a task type to the most relevant app category.
     * Returns null for tasks that don't typically need app clarification.
     */
    fun taskToCategory(task: String): AppCategory? {
        return when (task) {
            "play"         -> AppCategory.MEDIA
            "search"       -> AppCategory.SHOPPING
            "send_message" -> AppCategory.MESSAGING
            "navigate"     -> AppCategory.MAPS
            "open", "login" -> AppCategory.ALL
            else           -> null  // enable, disable, back, etc. don't need an app
        }
    }

    /**
     * Discovers installed apps matching a category.
     * Returns human-readable app labels (e.g. "YouTube", "Spotify").
     *
     * For [AppCategory.ALL], returns all launcher-visible apps.
     */
    fun getInstalledAppsByCategory(context: Context, category: AppCategory): List<String> {
        val pm = context.packageManager

        if (category == AppCategory.ALL) {
            // Return top launcher apps (limited to keep the list manageable)
            return try {
                val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
                val apps = pm.queryIntentActivities(mainIntent, 0)
                apps.map { it.loadLabel(pm).toString() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                    .take(15) // Keep the choice list manageable
            } catch (_: Exception) { emptyList() }
        }

        val packages = CATEGORY_PACKAGES[category] ?: return emptyList()
        val found = mutableListOf<String>()

        for (pkg in packages) {
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                if (label.isNotBlank()) {
                    found.add(label)
                }
            } catch (_: Exception) {
                // Not installed
            }
        }

        // Also check Android's category-based discovery for media apps
        if (category == AppCategory.MEDIA) {
            try {
                val musicIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
                val musicApps = pm.queryIntentActivities(musicIntent, 0)
                for (app in musicApps) {
                    val label = app.loadLabel(pm).toString()
                    if (label.isNotBlank() && label !in found) {
                        found.add(label)
                    }
                }
            } catch (_: Exception) {}
        }

        Log.d(TAG, "Discovered ${found.size} ${category.name} apps: $found")
        return found
    }

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
            val success = launchByPackage(context, packageName)
            if (success) return true
        }

        // Fallback: try the alias as a raw package name
        if (appAlias.contains(".")) {
            val success = launchByPackage(context, appAlias)
            if (success) return true
        }

        // Last resort: search for the app name in installed apps (e.g., regional package names)
        val searchSuccess = searchAndLaunch(context, appAlias)
        if (searchSuccess) return true

        // If all local launches failed and we had a known hardcoded package, send to play store
        if (packageName != null) {
            Log.w(TAG, "App not found locally, opening Play Store for: $packageName")
            launchPlayStore(context, packageName)
        }

        return false
    }

    /**
     * Checks if an exact match exists for the target app alias mapped to its package name,
     * without launching it or triggering fuzzy matching.
     */
    fun hasExactApp(context: Context, appAlias: String): Boolean {
        if (appAlias == "settings") return true
        val packageName = PACKAGE_MAP[appAlias.lowercase()]
        if (packageName != null) return isPackageInstalled(context, packageName)
        if (appAlias.contains(".")) return isPackageInstalled(context, appAlias) // Raw package
        // Search installed apps by label (covers apps not in PACKAGE_MAP like flipkart, zomato, etc.)
        return hasAppByLabel(context, appAlias)
    }

    /**
     * Checks if the given app alias is recognizable as a real app, website, or service
     * WITHOUT launching anything. This is the anti-hallucination gate — if we can't
     * recognize the target at all, the command is likely gibberish.
     *
     * Returns true if:
     *  - It's in our PACKAGE_MAP (well-known apps)
     *  - It's installed on the device (by label or package name)
     *  - It looks like a domain name (contains a dot)
     *  - It matches a category package list (media, shopping, etc.)
     */
    fun isRecognizableTarget(context: Context, appAlias: String): Boolean {
        val alias = appAlias.lowercase().trim()
        if (alias.isBlank()) return false

        // 1. Known in our hardcoded map
        if (alias in PACKAGE_MAP) return true
        if (alias == "settings" || alias == "browser") return true

        // 2. Looks like a package name or domain (contains a dot)
        if (alias.contains(".")) return true

        // 3. Check all category packages for a label match
        val allCategoryPackages = CATEGORY_PACKAGES.values.flatten().distinct()
        val pm = context.packageManager
        for (pkg in allCategoryPackages) {
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                if (label.contains(alias, ignoreCase = true)) return true
            } catch (_: Exception) {}
        }

        // 4. Search installed apps by label (fuzzy)
        if (hasAppByLabel(context, alias)) return true

        return false
    }

    /**
     * Checks if any installed app's label contains the given name.
     */
    private fun hasAppByLabel(context: Context, appName: String): Boolean {
        return try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(mainIntent, 0)
            apps.any { it.loadLabel(pm).toString().contains(appName, ignoreCase = true) }
        } catch (e: Exception) {
            false
        }
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
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

    fun launchPlayStore(context: Context, packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) { }
    }

    /**
     * Launch the default browser to a specific URL.
     * Prefers browsers that support extensions (Kiwi, Lemur, Firefox Nightly).
     */
    fun launchBrowserUrl(context: Context, url: String): Boolean {
        return launchBrowserUrlWithCommand(context, url, null)
    }

    /**
     * Launch the browser to a URL AND pass a command for the Autonion Extension
     * to execute. The command is encoded as base64 JSON in the URL hash fragment:
     *   https://amazon.com#__autonion__=eyJjbWQiOiJzZWFyY2giLCJxIjoic2hvZXMifQ==
     * 
     * The extension's content script (android-bridge.js) reads the hash on page
     * load and executes the command (search, click, type, scroll).
     * 
     * @param context Android context
     * @param url The target URL to open
     * @param command A JSON-serializable map like {"cmd":"search","q":"shoes"}
     */
    fun launchBrowserUrlWithCommand(
        context: Context,
        url: String,
        command: Map<String, String>?
    ): Boolean {
        return try {
            var validUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else {
                url
            }

            // Append Autonion command hash if provided
            if (command != null) {
                val json = org.json.JSONObject(command as Map<*, *>).toString()
                val base64 = android.util.Base64.encodeToString(
                    json.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
                validUrl = "$validUrl#__autonion__=$base64"
                Log.d(TAG, "Appended Autonion command hash: cmd=${command["cmd"]}")
            }

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(validUrl)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            
            // Prefer our supported extension browsers if they are installed
            if (isPackageInstalled(context, "com.kiwibrowser.browser")) {
                intent.setPackage("com.kiwibrowser.browser")
                context.startActivity(intent)
                Log.d(TAG, "Launched Kiwi Browser with URL: $validUrl")
                return true
            } else if (isPackageInstalled(context, "com.lemurbrowser.exts")) {
                intent.setPackage("com.lemurbrowser.exts")
                context.startActivity(intent)
                Log.d(TAG, "Launched Lemur Browser with URL: $validUrl")
                return true
            } else if (isPackageInstalled(context, "org.mozilla.fenix")) {
                intent.setPackage("org.mozilla.fenix")
                context.startActivity(intent)
                Log.d(TAG, "Launched Firefox Nightly Browser with URL: $validUrl")
                return true
            }

            // Fallback
            val pm = context.packageManager
            val resolveInfo = pm.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)

            if (resolveInfo != null && resolveInfo.activityInfo.packageName != "android") {
                intent.setPackage(resolveInfo.activityInfo.packageName)
                context.startActivity(intent)
                Log.d(TAG, "Launched browser (${resolveInfo.activityInfo.packageName}) with URL: $validUrl")
            } else {
                val chooser = Intent.createChooser(intent, "Choose browser for Automation").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(chooser)
                Log.d(TAG, "Launched browser chooser with URL: $validUrl")
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch browser URL: $url", e)
            false
        }
    }
}

