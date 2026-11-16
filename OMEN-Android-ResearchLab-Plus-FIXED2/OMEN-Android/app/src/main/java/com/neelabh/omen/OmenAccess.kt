package com.neelabh.omen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Optional: presses the Send button in WhatsApp right after OMEN opens a prefilled chat. */
class OmenAccess : AccessibilityService() {
    companion object {
        @Volatile var armedUntil = 0L
        @Volatile var alive = false
        fun arm() { armedUntil = System.currentTimeMillis() + 15000 }
    }
    override fun onServiceConnected() { alive = true }
    override fun onUnbind(i: Intent?): Boolean { alive = false; return super.onUnbind(i) }
    override fun onInterrupt() {}
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (System.currentTimeMillis() > armedUntil) return
        val root = rootInActiveWindow ?: return
        val n: AccessibilityNodeInfo? =
            root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send").firstOrNull()
                ?: root.findAccessibilityNodeInfosByText("Send").firstOrNull { it.isClickable }
        if (n != null) { armedUntil = 0; n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }
}
