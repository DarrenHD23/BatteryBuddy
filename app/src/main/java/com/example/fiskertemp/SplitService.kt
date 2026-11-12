package com.example.fiskertemp

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Lets the app ask Android to put it into split screen (the same as the system's own split-screen action).
 * It does not read anything from the screen: it ignores every event and only performs that one action.
 */
class SplitService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }

    /** Ask Android to toggle split screen for the app in front. */
    fun splitScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)

    companion object {
        @Volatile var instance: SplitService? = null
    }
}
