package com.example.automationcompanion.features.system_context_automation.location.engine.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

class TileTogglerHelper(
    private val service: AccessibilityService
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun attemptToggleLocation(onComplete: (Boolean) -> Unit) {
        mainHandler.post {
            try {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)

                mainHandler.postDelayed({
                    val root = service.rootInActiveWindow
                    if (root == null) {
                        onComplete(false)
                        return@postDelayed
                    }

                    val found = clickLocationTile(root)
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    onComplete(found)
                }, 600L)
            } catch (e: Exception) {
                onComplete(false)
            }
        }
    }

    private fun clickLocationTile(root: AccessibilityNodeInfo): Boolean {
        // Try common content descriptions / text; OEMs differ a lot.
        val possibleTexts = listOf("Location", "Location services", "GPS", "位置情報", "位置") // include translations as needed
        // Search by text or contentDescription
        for (text in possibleTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (!nodes.isNullOrEmpty()) {
                for (n in nodes) {
                    if (n.isClickable) {
                        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    } else {
                        // try parent clickable
                        var p = n.parent
                        while (p != null) {
                            if (p.isClickable) {
                                p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                return true
                            }
                            p = p.parent
                        }
                    }
                }
            }
        }
        // Fallback: find clickable nodes that might be tiles
        val clickable = ArrayList<AccessibilityNodeInfo>()
        collectClickable(root, clickable)
        for (n in clickable) {
            // heuristic: tile nodes often have child with text we can inspect
            val txt = n.text ?: n.contentDescription
            if (txt != null && possibleTexts.any { txt.toString().contains(it, ignoreCase = true) }) {
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return false
    }

    private fun collectClickable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable) out.add(node)
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collectClickable(c, out)
        }
    }
}
