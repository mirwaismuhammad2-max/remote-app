package com.example.remoteapp

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * Represents one app the user has chosen to make remotely launchable.
 */
data class WhitelistedApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)

/**
 * Handles:
 *  - listing installed apps so the owner can pick which ones to whitelist
 *  - persisting the whitelist (SharedPreferences, simple set of package names)
 *  - launching a whitelisted app
 *  - "closing" a whitelisted app (best-effort: home + kill background process)
 *
 * This class has NO networking in it. It's the local logic only.
 * The WebSocket/service layer will call into this.
 */
class WhitelistManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("remoteapp_whitelist", Context.MODE_PRIVATE)
    private val KEY_PACKAGES = "whitelisted_packages"

    // ---------- Persistence ----------

    fun getWhitelistedPackageNames(): Set<String> {
        return prefs.getStringSet(KEY_PACKAGES, emptySet()) ?: emptySet()
    }

    fun setWhitelistedPackageNames(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_PACKAGES, packages).apply()
    }

    fun addToWhitelist(packageName: String) {
        val current = getWhitelistedPackageNames().toMutableSet()
        current.add(packageName)
        setWhitelistedPackageNames(current)
    }

    fun removeFromWhitelist(packageName: String) {
        val current = getWhitelistedPackageNames().toMutableSet()
        current.remove(packageName)
        setWhitelistedPackageNames(current)
    }

    fun isWhitelisted(packageName: String): Boolean {
        return getWhitelistedPackageNames().contains(packageName)
    }

    // ---------- Listing installed apps (for the picker UI) ----------

    /**
     * Returns all user-installed, launchable apps so the owner can pick from them.
     * Excludes system apps with no launcher icon (keeps the picker list sane).
     */
    fun getInstallableApps(): List<WhitelistedApp> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)

        return resolveInfos
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .map { appInfo: ApplicationInfo ->
                WhitelistedApp(
                    packageName = appInfo.packageName,
                    label = pm.getApplicationLabel(appInfo).toString(),
                    icon = try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { null }
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Returns only the apps currently on the whitelist, with fresh labels/icons.
     * This is the list that gets sent to the remote viewer.
     */
    fun getWhitelistedApps(): List<WhitelistedApp> {
        val pm = context.packageManager
        return getWhitelistedPackageNames().mapNotNull { pkg ->
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                WhitelistedApp(
                    packageName = pkg,
                    label = pm.getApplicationLabel(appInfo).toString(),
                    icon = try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { null }
                )
            } catch (e: PackageManager.NameNotFoundException) {
                // App was uninstalled since being whitelisted; drop it silently.
                null
            }
        }
    }

    // ---------- Remote commands ----------

    sealed class CommandResult {
        object Success : CommandResult()
        data class Rejected(val reason: String) : CommandResult()
        data class Failed(val reason: String) : CommandResult()
    }

    /**
     * Launches a whitelisted app. Rejects anything not on the whitelist —
     * this is the single enforcement point, so the remote side can never
     * launch something it shouldn't.
     */
    fun launchApp(packageName: String): CommandResult {
        if (!isWhitelisted(packageName)) {
            return CommandResult.Rejected("Package not whitelisted: $packageName")
        }

        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
            ?: return CommandResult.Failed("No launch intent for $packageName")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(launchIntent)
            CommandResult.Success
        } catch (e: Exception) {
            CommandResult.Failed("Launch failed: ${e.message}")
        }
    }

    /**
     * Best-effort close: sends the device home, then asks the system to kill
     * the app's background process. Android does not allow a normal app to
     * force-close another app outright, so some apps may linger or restart
     * themselves. This requires the KILL_BACKGROUND_PROCESSES permission
     * (normal-level, granted automatically at install).
     */
    fun closeApp(packageName: String): CommandResult {
        if (!isWhitelisted(packageName)) {
            return CommandResult.Rejected("Package not whitelisted: $packageName")
        }

        return try {
            // Send the device to the home screen first so the app isn't
            // visibly yanked away mid-interaction.
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(homeIntent)

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)

            CommandResult.Success
        } catch (e: Exception) {
            CommandResult.Failed("Close failed: ${e.message}")
        }
    }
}
