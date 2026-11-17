package com.example.remoteapp

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Single choke point for every command coming from the remote side.
 * Nothing in the networking/signaling layer should call the
 * AccessibilityService or WhitelistManager directly — it should always
 * go through here, so the enforcement rules can't accidentally be bypassed.
 *
 * Enforcement rules:
 *  1. launch / close: target package must be on the whitelist.
 *  2. tap / swipe / type / etc: the app currently in the foreground must
 *     be on the whitelist. If the owner has switched away from the
 *     whitelisted app (e.g. to Settings, or another app), ALL control
 *     commands are silently rejected until a whitelisted app is foreground
 *     again. This is what stops "full control" from ever reaching an app
 *     that wasn't approved for it.
 */
class RemoteCommandProcessor(
    private val context: Context,
    private val whitelistManager: WhitelistManager
) {

    sealed class CommandOutcome {
        object Success : CommandOutcome()
        data class Rejected(val reason: String) : CommandOutcome()
        data class Error(val reason: String) : CommandOutcome()
    }

    // ---------- Foreground app detection ----------

    /**
     * Requires PACKAGE_USAGE_STATS, granted via a special settings screen
     * (not a normal runtime permission dialog). The app should guide the
     * user there once during setup.
     */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun getForegroundPackage(): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = end - 10_000 // look back 10s, enough to catch the latest event
        val events = usm.queryEvents(begin, end)
        var lastForegroundPkg: String? = null
        val event = android.app.usage.UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastForegroundPkg = event.packageName
            }
        }
        return lastForegroundPkg
    }

    private fun isForegroundAppWhitelisted(): Boolean {
        val fg = getForegroundPackage() ?: return false
        return whitelistManager.isWhitelisted(fg)
    }

    // ---------- Lifecycle commands (launch/close) ----------

    fun handleLaunch(packageName: String): CommandOutcome {
        return when (val result = whitelistManager.launchApp(packageName)) {
            is WhitelistManager.CommandResult.Success -> CommandOutcome.Success
            is WhitelistManager.CommandResult.Rejected -> CommandOutcome.Rejected(result.reason)
            is WhitelistManager.CommandResult.Failed -> CommandOutcome.Error(result.reason)
        }
    }

    fun handleClose(packageName: String): CommandOutcome {
        return when (val result = whitelistManager.closeApp(packageName)) {
            is WhitelistManager.CommandResult.Success -> CommandOutcome.Success
            is WhitelistManager.CommandResult.Rejected -> CommandOutcome.Rejected(result.reason)
            is WhitelistManager.CommandResult.Failed -> CommandOutcome.Error(result.reason)
        }
    }

    // ---------- Input commands (tap/swipe/type) ----------

    private fun requireControlService(): RemoteControlAccessibilityService? {
        val service = RemoteControlAccessibilityService.instance
        if (service == null) return null
        if (!isForegroundAppWhitelisted()) return null
        return service
    }

    fun handleTap(x: Float, y: Float): CommandOutcome {
        val service = requireControlService()
            ?: return CommandOutcome.Rejected("No whitelisted app in foreground, or accessibility service not running")
        service.tap(x, y)
        return CommandOutcome.Success
    }

    fun handleSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): CommandOutcome {
        val service = requireControlService()
            ?: return CommandOutcome.Rejected("No whitelisted app in foreground, or accessibility service not running")
        service.swipe(x1, y1, x2, y2, durationMs)
        return CommandOutcome.Success
    }

    fun handleLongPress(x: Float, y: Float): CommandOutcome {
        val service = requireControlService()
            ?: return CommandOutcome.Rejected("No whitelisted app in foreground, or accessibility service not running")
        service.longPress(x, y)
        return CommandOutcome.Success
    }

    fun handleType(text: String): CommandOutcome {
        val service = requireControlService()
            ?: return CommandOutcome.Rejected("No whitelisted app in foreground, or accessibility service not running")
        return if (service.typeText(text)) CommandOutcome.Success
        else CommandOutcome.Error("No focused text field to type into")
    }

    fun handleBack(): CommandOutcome {
        val service = requireControlService()
            ?: return CommandOutcome.Rejected("No whitelisted app in foreground, or accessibility service not running")
        service.pressBack()
        return CommandOutcome.Success
    }
}
