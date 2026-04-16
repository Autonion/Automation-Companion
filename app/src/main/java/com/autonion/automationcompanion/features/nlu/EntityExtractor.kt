package com.autonion.automationcompanion.features.nlu

import android.view.KeyEvent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Extracts structured entities from raw user prompts using
 * regex patterns and heuristic rules.
 *
 * This is the "fast path" — no ML or LLM needed.
 */
class EntityExtractor {

    companion object {
        private const val TAG = "EntityExtractor"
    }

    // ═══════════════════════════════════════════════════════════
    //  DURATION / SCHEDULE PATTERNS
    // ═══════════════════════════════════════════════════════════

    private val durationPattern = Regex(
        """every\s+(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)""",
        RegexOption.IGNORE_CASE
    )

    private val repeatCountPattern = Regex(
        """(\d+)\s*times""",
        RegexOption.IGNORE_CASE
    )

    private val scheduledIndicatorPattern = Regex(
        """(every|repeat|keep|continuously|again\s+and\s+again|non[\s-]?stop)""",
        RegexOption.IGNORE_CASE
    )

    // ═══════════════════════════════════════════════════════════
    //  TOGGLE PATTERNS
    // ═══════════════════════════════════════════════════════════

    private val toggleTargets = mapOf(
        "wifi" to listOf("wi-fi", "wi fi", "wireless", "wlan"),
        "bluetooth" to listOf("bt", "blue tooth"),
        "dnd" to listOf("do not disturb", "donotdisturb", "silent mode"),
        "airplane" to listOf("airplane mode", "aeroplane mode", "flight mode"),
        "location" to listOf("gps", "location services"),
        "hotspot" to listOf("mobile hotspot", "tethering", "portable hotspot"),
        "nfc" to listOf("near field", "nfc"),
        "flashlight" to listOf("torch", "flash light"),
        "auto_rotate" to listOf("auto rotate", "rotation", "screen rotation"),
        "mobile_data" to listOf("cellular data", "mobile data", "data")
    )

    private val enableWords = setOf(
        "enable", "turn on", "switch on", "activate", "start", "on"
    )

    private val disableWords = setOf(
        "disable", "turn off", "switch off", "deactivate", "stop", "off"
    )

    // ═══════════════════════════════════════════════════════════
    //  KEY ACTION PATTERNS
    // ═══════════════════════════════════════════════════════════

    private val keyActionVerbs = setOf(
        "press", "hit", "tap", "click", "push", "type", "enter"
    )

    /** Maps human-readable key names → Android KeyEvent codes */
    val keyNameMap: Map<String, Int> = mapOf(
        "enter" to KeyEvent.KEYCODE_ENTER,
        "return" to KeyEvent.KEYCODE_ENTER,
        "submit" to KeyEvent.KEYCODE_ENTER,
        "confirm" to KeyEvent.KEYCODE_ENTER,
        "ok" to KeyEvent.KEYCODE_ENTER,
        "space" to KeyEvent.KEYCODE_SPACE,
        "spacebar" to KeyEvent.KEYCODE_SPACE,
        "tab" to KeyEvent.KEYCODE_TAB,
        "escape" to KeyEvent.KEYCODE_ESCAPE,
        "esc" to KeyEvent.KEYCODE_ESCAPE,
        "cancel" to KeyEvent.KEYCODE_ESCAPE,
        "backspace" to KeyEvent.KEYCODE_DEL,
        "delete" to KeyEvent.KEYCODE_FORWARD_DEL,
        "back" to KeyEvent.KEYCODE_BACK,
        "home" to KeyEvent.KEYCODE_HOME,
        "next" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "forward" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "previous" to KeyEvent.KEYCODE_DPAD_LEFT,
        "prev" to KeyEvent.KEYCODE_DPAD_LEFT,
        "left" to KeyEvent.KEYCODE_DPAD_LEFT,
        "up" to KeyEvent.KEYCODE_DPAD_UP,
        "down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "volume up" to KeyEvent.KEYCODE_VOLUME_UP,
        "volume down" to KeyEvent.KEYCODE_VOLUME_DOWN,
        "mute" to KeyEvent.KEYCODE_VOLUME_MUTE,
        "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
        "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
        "stop" to KeyEvent.KEYCODE_MEDIA_STOP,
        "skip" to KeyEvent.KEYCODE_MEDIA_NEXT,
        "rewind" to KeyEvent.KEYCODE_MEDIA_PREVIOUS
    )

    // ═══════════════════════════════════════════════════════════
    //  CROSS-DEVICE PATTERNS
    // ═══════════════════════════════════════════════════════════

    private val crossDeviceIndicators = listOf(
        "on my desktop", "on my laptop", "on my computer", "on my pc",
        "on the desktop", "on the laptop", "on the computer",
        "send to desktop", "send to laptop", "send to computer",
        "remote", "on my mac", "on my windows"
    )

    // ═══════════════════════════════════════════════════════════
    //  SEMANTIC MODIFIERS
    // ═══════════════════════════════════════════════════════════

    private val semanticModifiers = setOf(
        "random", "any", "some", "whatever", "anything",
        "arbitrary", "whichever", "a random", "any random"
    )

    // ═══════════════════════════════════════════════════════════
    //  APP NAME PATTERNS
    // ═══════════════════════════════════════════════════════════

    private val commonApps = mapOf(
        "settings" to "com.android.settings",
        "flipkart" to "com.flipkart.android",
        "amazon" to "in.amazon.mShop.android.shopping",
        "spotify" to "com.spotify.music",
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "chrome" to "com.android.chrome",
        "firefox" to "org.mozilla.firefox",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "camera" to "com.android.camera",
        "clock" to "com.android.deskclock",
        "calculator" to "com.android.calculator2",
        "phone" to "com.android.dialer",
        "messages" to "com.google.android.apps.messaging",
        "telegram" to "org.telegram.messenger",
        "facebook" to "com.facebook.katana",
        "snapchat" to "com.snapchat.android",
        "netflix" to "com.netflix.mediaclient",
        "google pay" to "com.google.android.apps.nbu.paisa.user",
        "gpay" to "com.google.android.apps.nbu.paisa.user",
        "zomato" to "com.application.zomato",
        "swiggy" to "in.swiggy.android",
        "uber" to "com.ubercab",
        "ola" to "com.olacabs.customer",
        "paytm" to "net.one97.paytm",
        "phonepe" to "com.phonepe.app"
    )

    private val appPrepositions = setOf("on", "in", "using", "with", "via", "through")

    private val taskVerbs = setOf(
        "search", "open", "play", "send", "find", "browse", "look",
        "navigate", "go", "check", "launch", "start", "run",
        "watch", "listen", "read", "write", "create", "make",
        "order", "buy", "book", "download"
    )

    // ═══════════════════════════════════════════════════════════
    //  QUESTION PATTERNS
    // ═══════════════════════════════════════════════════════════

    private val questionIndicators = Regex(
        """^(how|what|why|when|where|who|which|can|does|is|are|do|explain|tell\s+me|help|guide|is\s+there|are\s+there)""",
        RegexOption.IGNORE_CASE
    )

    /** Catches indirect questions: "I have a doubt...", "I was wondering..." */
    private val indirectQuestionIndicators = Regex(
        """(i\s+have\s+a\s+doubt|i\s+was\s+wondering|i('m|\s+am)\s+confused|can\s+i|is\s+it\s+possible|is\s+there\s+(a|any)|are\s+there\s+(a|any)|could\s+you\s+explain|wondering\s+if|i\s+want\s+to\s+know|doubt\s+about|question\s+about)""",
        RegexOption.IGNORE_CASE
    )

    // ═══════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════

    /**
     * Extract all entities from a raw prompt.
     * Returns a fully populated [ExtractedEntities] instance.
     */
    fun extract(prompt: String): ExtractedEntities {
        val lower = prompt.lowercase().trim()

        return ExtractedEntities(
            keyName = extractKeyEventName(lower),
            keyLabel = extractKeyLabel(lower),
            textToType = extractTextToType(lower, prompt),
            appName = extractAppName(lower),
            searchQuery = extractSearchQuery(lower, prompt),
            toggleTarget = extractToggleTarget(lower),
            toggleDesiredState = extractToggleState(lower),
            interval = extractDuration(lower),
            repeatCount = extractRepeatCount(lower),
            targetDevice = extractTargetDevice(lower),
            semanticModifiers = extractSemanticModifiers(lower),
            taskVerb = extractTaskVerb(lower)
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  EXTRACTION METHODS
    // ═══════════════════════════════════════════════════════════

    fun extractKeyEventName(lower: String): String? {
        val label = extractKeyLabel(lower) ?: return null
        val keyCode = keyNameMap[label] ?: return null
        return KeyEvent.keyCodeToString(keyCode)
    }

    fun extractKeyLabel(lower: String): String? {
        // Pattern: "press/hit/type/click <key>"
        val words = lower.split(Regex("\\s+"))
        if (words.size < 2) return null

        // Check if it starts with a key action verb
        val startsWithVerb = keyActionVerbs.any { lower.startsWith(it) }
        if (!startsWithVerb) return null

        // Extract the key part (everything after the verb, cleaned)
        val verbEnd = keyActionVerbs.firstOrNull { lower.startsWith(it) } ?: return null
        val rest = lower.removePrefix(verbEnd).trim()
            .removePrefix("the ").removePrefix("a ").removePrefix("an ")
            .removeSuffix(" key").removeSuffix(" button")
            .trim()

        if (rest.isEmpty()) return null

        // Check if the rest matches a known key
        return keyNameMap.keys.firstOrNull { key ->
            rest == key || rest.startsWith("$key ") || rest.endsWith(" $key")
        }
    }

    fun extractTextToType(lower: String, original: String): String? {
        // "type hello world" where no key match → text input
        if (!lower.startsWith("type ") && !lower.startsWith("enter ")) return null

        val keyLabel = extractKeyLabel(lower)
        if (keyLabel != null) return null // It's a key press, not text

        val verb = if (lower.startsWith("type ")) "type " else "enter "
        val text = original.substring(original.lowercase().indexOf(verb) + verb.length).trim()
        return text.ifEmpty { null }
    }

    fun extractAppName(lower: String): String? {
        // Check for direct app name mentions
        for ((appName, _) in commonApps) {
            if (lower.containsWord(appName)) return appName
        }
        // Check "on/in <app>" pattern
        for (prep in appPrepositions) {
            val pattern = Regex("""$prep\s+(\w+)""")
            val match = pattern.find(lower)
            if (match != null) {
                val candidate = match.groupValues[1]
                if (commonApps.containsKey(candidate)) return candidate
            }
        }
        return null
    }

    fun getPackageName(appName: String): String? {
        return commonApps[appName.lowercase()]
    }

    fun extractSearchQuery(lower: String, original: String): String? {
        val taskVerb = extractTaskVerb(lower) ?: return null
        if (taskVerb !in setOf("search", "find", "look", "browse")) return null

        val appName = extractAppName(lower)

        // Extract query: "search <query> on <app>" or "search for <query> on <app>"
        var query = lower
        query = query.replaceFirst(Regex("^(search|find|look|browse)\\s+(for\\s+)?"), "")

        // Remove app reference
        if (appName != null) {
            for (prep in appPrepositions) {
                query = query.replace(Regex("\\s+$prep\\s+$appName.*$"), "")
            }
        }

        // Remove cross-device references
        crossDeviceIndicators.forEach { indicator ->
            query = query.replace(indicator, "")
        }

        return query.trim().ifEmpty { null }
    }

    fun extractToggleTarget(lower: String): String? {
        for ((target, aliases) in toggleTargets) {
            if (lower.containsWord(target) || aliases.any { lower.containsWord(it) }) {
                return target
            }
        }
        return null
    }

    fun extractToggleState(lower: String): Boolean? {
        val hasEnable = enableWords.any { lower.containsWord(it) }
        val hasDisable = disableWords.any { lower.containsWord(it) }
        return when {
            hasEnable && !hasDisable -> true
            hasDisable && !hasEnable -> false
            else -> null // Ambiguous or toggle
        }
    }

    fun extractDuration(lower: String): Duration? {
        val match = durationPattern.find(lower) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        return when {
            unit.startsWith("sec") -> amount.seconds
            unit.startsWith("min") -> amount.minutes
            unit.startsWith("hour") || unit.startsWith("hr") -> amount.hours
            else -> null
        }
    }

    fun extractRepeatCount(lower: String): Int? {
        val match = repeatCountPattern.find(lower)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    fun extractTargetDevice(lower: String): String? {
        for (indicator in crossDeviceIndicators) {
            if (lower.containsWord(indicator)) {
                return when {
                    indicator.contains("desktop") || indicator.contains("pc") -> "desktop"
                    indicator.contains("laptop") -> "laptop"
                    indicator.contains("computer") -> "computer"
                    indicator.contains("mac") -> "mac"
                    indicator.contains("windows") -> "windows"
                    else -> "desktop"
                }
            }
        }
        return null
    }

    fun extractSemanticModifiers(lower: String): List<String> {
        return semanticModifiers.filter { lower.containsWord(it) }
    }

    fun extractTaskVerb(lower: String): String? {
        val words = lower.split(Regex("\\s+"))
        return words.firstOrNull { it in taskVerbs }
    }

    // ═══════════════════════════════════════════════════════════
    //  INTENT HINT CHECKS (used by IntentClassifier)
    // ═══════════════════════════════════════════════════════════

    /** True if the prompt looks like a key press command */
    fun isLikelyKeyAction(lower: String): Boolean {
        val hasVerb = keyActionVerbs.any { lower.startsWith(it) }
        val hasKey = extractKeyLabel(lower) != null
        return hasVerb && hasKey
    }

    /** True if the prompt looks like a toggle command */
    fun isLikelyToggle(lower: String): Boolean {
        val hasToggleWord = (enableWords + disableWords).any { lower.containsWord(it) }
        val hasTarget = extractToggleTarget(lower) != null
        return hasToggleWord && hasTarget
    }

    /** True if the prompt contains schedule/repeat indicators */
    fun isLikelyScheduled(lower: String): Boolean {
        return scheduledIndicatorPattern.containsMatchIn(lower) &&
               (extractDuration(lower) != null || extractRepeatCount(lower) != null)
    }

    /** True if the prompt references a remote device */
    fun isLikelyCrossDevice(lower: String): Boolean {
        return crossDeviceIndicators.any { lower.containsWord(it) }
    }

    /** True if the prompt looks like a question */
    fun isLikelyQuestion(lower: String): Boolean {
        return questionIndicators.containsMatchIn(lower) ||
               indirectQuestionIndicators.containsMatchIn(lower) ||
               lower.endsWith("?")
    }

    private fun String.containsWord(word: String): Boolean {
        return Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(this)
    }
}
