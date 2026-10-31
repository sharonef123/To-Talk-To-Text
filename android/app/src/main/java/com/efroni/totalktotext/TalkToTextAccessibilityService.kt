package com.efroni.totalktotext

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * This is the piece that actually lets dictated text land inside OTHER
 * apps' text fields. A floating overlay's WindowManager view can never hold
 * input focus for another app's EditText - there is no permission or API
 * that grants that - so direct cross-app text injection has to go through
 * an AccessibilityService, which is allowed to inspect the currently
 * focused AccessibilityNodeInfo system-wide and perform ACTION_SET_TEXT /
 * ACTION_SET_SELECTION on it. This mirrors how Voice Access and similar
 * dictation tools type into arbitrary apps.
 */
class TalkToTextAccessibilityService : AccessibilityService() {

    companion object {
        var instance: TalkToTextAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event handling needed - this service is used purely as an
        // on-demand action API (see insertTextIntoFocusedField below).
    }

    override fun onInterrupt() {}

    /**
     * Append [text] to whichever field currently has input focus, anywhere
     * on screen (not just within this app). Returns false if no editable
     * field is focused, or the insertion failed for any reason - callers
     * should fall back to the clipboard in that case.
     */
    fun insertTextIntoFocusedField(text: String): Boolean {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        try {
            if (!node.isEditable) return false
            val existing = node.text?.toString() ?: ""
            val combined = existing + text

            val setTextArgs = Bundle()
            setTextArgs.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined
            )
            val wrote = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)
            if (wrote) {
                val selectionArgs = Bundle()
                selectionArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, combined.length)
                selectionArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, combined.length)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
            }
            return wrote
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }
}
