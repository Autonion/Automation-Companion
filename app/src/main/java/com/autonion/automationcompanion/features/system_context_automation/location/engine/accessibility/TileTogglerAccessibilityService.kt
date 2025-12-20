//package com.example.automationcompanion.features.system_context_automation.location.engine.accessibility
//
//import android.accessibilityservice.AccessibilityService
//import android.accessibilityservice.AccessibilityServiceInfo
//import android.os.Handler
//import android.os.Looper
//import android.view.accessibility.AccessibilityNodeInfo
//import android.view.accessibility.AccessibilityEvent
//import android.util.Log
//
//class TileTogglerAccessibilityService : AccessibilityService() {
//
//    companion object {
//        @Volatile
//        var instance: TileTogglerAccessibilityService? = null
//    }
//
//    private val mainHandler = Handler(Looper.getMainLooper())
//
//    override fun onServiceConnected() {
//        instance = this
//        val info = AccessibilityServiceInfo()
//        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
//        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
//        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
//        serviceInfo = info
//        Log.i("TileToggler", "Accessibility connected")
//    }
//
//    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
//        // not used for explicit toggles, but useful for debugging
//    }
//
//    override fun onInterrupt() {}
//
//    override fun onDestroy() {
//        instance = null
//        super.onDestroy()
//    }
//
//    /**
//     * Attempt to toggle Location tile: expand quick settings then find a node labelled "Location".
//     * This method must be called on the main thread (we use mainHandler).
//     */
//    fun attemptToggleLocation(onComplete: (success: Boolean) -> Unit) {
//        mainHandler.post {
//            try {
//                // Expand quick settings (global action)
//                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
//                // Wait a moment for window to populate, then search for the tile
//                mainHandler.postDelayed({
//                    val root = rootInActiveWindow
//                    if (root == null) {
//                        onComplete(false)
//                        return@postDelayed
//                    }
//                    val found = clickLocationTile(root)
//                    // collapse quick settings
//                    performGlobalAction(GLOBAL_ACTION_BACK)
//                    onComplete(found)
//                }, 600L) // tweak delay per device
//            } catch (e: Exception) {
//                Log.e("TileToggler", "toggle error", e)
//                onComplete(false)
//            }
//        }
//    }
//
//    private fun clickLocationTile(root: AccessibilityNodeInfo): Boolean {
//        // Try common content descriptions / text; OEMs differ a lot.
//        val possibleTexts = listOf("Location", "Location services", "GPS", "位置情報", "位置") // include translations as needed
//        // Search by text or contentDescription
//        for (text in possibleTexts) {
//            val nodes = root.findAccessibilityNodeInfosByText(text)
//            if (!nodes.isNullOrEmpty()) {
//                for (n in nodes) {
//                    if (n.isClickable) {
//                        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//                        return true
//                    } else {
//                        // try parent clickable
//                        var p = n.parent
//                        while (p != null) {
//                            if (p.isClickable) {
//                                p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//                                return true
//                            }
//                            p = p.parent
//                        }
//                    }
//                }
//            }
//        }
//        // Fallback: find clickable nodes that might be tiles
//        val clickable = ArrayList<AccessibilityNodeInfo>()
//        collectClickable(root, clickable)
//        for (n in clickable) {
//            // heuristic: tile nodes often have child with text we can inspect
//            val txt = n.text ?: n.contentDescription
//            if (txt != null && possibleTexts.any { txt.toString().contains(it, ignoreCase = true) }) {
//                n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//                return true
//            }
//        }
//        return false
//    }
//
//    private fun collectClickable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
//        if (node.isClickable) out.add(node)
//        for (i in 0 until node.childCount) {
//            val c = node.getChild(i) ?: continue
//            collectClickable(c, out)
//        }
//    }
//}
