package com.jarvis.assistant.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.jarvis.assistant.MainActivity

/**
 * Launches apps via normal Android intents. Accessibility is not used for launch.
 */
class AppLauncher(private val context: Context) {

    data class LaunchResult(
        val success: Boolean,
        val packageName: String? = null,
        val message: String
    )

    fun openWhatsApp(): LaunchResult {
        val candidates = listOf(PACKAGE_WHATSAPP, PACKAGE_WHATSAPP_BUSINESS)
        for (pkg in candidates) {
            if (isInstalled(pkg)) {
                return launchPackage(pkg, "WhatsApp")
            }
        }
        return LaunchResult(false, null, "WhatsApp is not installed.")
    }

    fun openYouTube(): LaunchResult {
        if (isInstalled(PACKAGE_YOUTUBE)) {
            return launchPackage(PACKAGE_YOUTUBE, "YouTube")
        }
        return LaunchResult(false, null, "YouTube is not installed.")
    }

    fun openApp(appName: String, packageName: String? = null): LaunchResult {
        if (!packageName.isNullOrBlank()) {
            return launchPackage(packageName, appName)
        }
        if (appName.contains("whatsapp", ignoreCase = true)) {
            return openWhatsApp()
        }
        if (appName.contains("youtube", ignoreCase = true)) {
            return openYouTube()
        }
        val resolved = resolvePackageByLabel(appName)
            ?: return LaunchResult(false, null, "Could not find app \"$appName\".")
        return launchPackage(resolved, appName)
    }

    fun isWhatsAppInForeground(packageName: String?): Boolean {
        return packageName == PACKAGE_WHATSAPP || packageName == PACKAGE_WHATSAPP_BUSINESS
    }

    fun resolveWhatsAppPackage(): String? {
        return listOf(PACKAGE_WHATSAPP, PACKAGE_WHATSAPP_BUSINESS).firstOrNull { isInstalled(it) }
    }

    /**
     * Returns Jarvis to the foreground so confirmation / error UI is visible
     * after WhatsApp automation has taken focus.
     */
    fun bringJarvisToForeground() {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        context.startActivity(intent)
    }

    private fun launchPackage(packageName: String, label: String): LaunchResult {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return LaunchResult(false, packageName, "No launch intent for $label.")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launch)
            LaunchResult(true, packageName, "$label opened.")
        } catch (t: Throwable) {
            LaunchResult(false, packageName, "Failed to open $label: ${t.message}")
        }
    }

    private fun isInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun resolvePackageByLabel(appName: String): String? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
        val target = appName.trim().lowercase()
        return apps.firstOrNull {
            it.loadLabel(pm).toString().lowercase() == target
        }?.activityInfo?.packageName
            ?: apps.firstOrNull {
                it.loadLabel(pm).toString().lowercase().contains(target)
            }?.activityInfo?.packageName
    }

    companion object {
        const val PACKAGE_WHATSAPP = "com.whatsapp"
        const val PACKAGE_WHATSAPP_BUSINESS = "com.whatsapp.w4b"
        const val PACKAGE_YOUTUBE = "com.google.android.youtube"
    }
}
