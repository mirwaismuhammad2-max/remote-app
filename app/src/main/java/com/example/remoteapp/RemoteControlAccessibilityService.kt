package com.example.remoteapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

/**
 * This service is what makes "full control" possible: Android does not let
 * a normal app inject touch events into other apps' windows. The only
 * supported way to do this legitimately is via the Accessibility API,
 * which is also how TeamViewer QuickSupport / AnyDesk's "full control"
 * mode works under the hood.
 *
 * The user must manually enable this in Settings > Accessibility — Android
 * will not let an app turn this on for itself, by design. That's a one-time
 * setup step on the owner's own phone.
 *
 * All commands this service accepts come from RemoteCommandProcessor,
 * which only runs them if they target the currently-foregrounded,
 * whitelisted app (see RemoteCommandProcessor.kt, next file).
 */
class RemoteControlAccessibilityService : AccessibilityService() {

    companion object {
        // Set by the service when it connects, read by the rest of the app
        // to know whether remote control is actually available right now.
        var instance: RemoteControlAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val expectedComponentName = ComponentName(context, RemoteControlAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(expectedComponentName.flattenToString())
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to react to events for remote control; this service
        // is used purely for its ability to dispatch gestures and global
        // actions. Left empty on purpose.
    }

    override fun onInterrupt() {}

    // ---------- Gesture injection ----------

    /**
     * Single tap at (x, y) in screen pixel coordinates, as sent by the
     * remote viewer based on the video frame size.
     */
    fun tap(x: Float, y: Float, durationMs: Long = 50) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Swipe/drag from (x1,y1) to (x2,y2) over durationMs.
     */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Long press at (x, y).
     */
    fun longPress(x: Float, y: Float, durationMs: Long = 600) {
        tap(x, y, durationMs)
    }

    /**
     * Multi-touch pinch/zoom, expressed as two simultaneous strokes.
     * Useful for apps where remote users need to zoom (maps, photos, etc.)
     */
    fun pinch(
        x1Start: Float, y1Start: Float, x1End: Float, y1End: Float,
        x2Start: Float, y2Start: Float, x2End: Float, y2End: Float,
        durationMs: Long = 300
    ) {
        val path1 = Path().apply { moveTo(x1Start, y1Start); lineTo(x1End, y1End) }
        val path2 = Path().apply { moveTo(x2Start, y2Start); lineTo(x2End, y2End) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path1, 0, durationMs))
            .addStroke(GestureDescription.StrokeDescription(path2, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ---------- Text input ----------

    /**
     * Types text into whatever node currently has input focus.
     * This finds the focused editable node and sets its text directly —
     * it does not simulate individual key presses, which keeps it reliable
     * across keyboard apps.
     */
    fun typeText(text: String): Boolean {
        val focusedNode = findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false

        return try {
            val arguments = android.os.Bundle()
            arguments.putCharSequence(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
            focusedNode.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                arguments
            )
        } finally {
            focusedNode.recycle()
        }
    }

    // ---------- Navigation / global actions ----------

    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    @RequiresApi(Build.VERSION_CODES.R)
    fun lockScreen() = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
}
