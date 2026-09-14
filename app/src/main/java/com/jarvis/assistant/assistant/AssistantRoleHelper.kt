package com.jarvis.assistant.assistant

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Helpers for [RoleManager.ROLE_ASSISTANT] where the platform supports it.
 * Availability is checked before any request — never forced.
 */
object AssistantRoleHelper {

    fun isRoleApiAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun isAssistantRoleAvailable(context: Context): Boolean {
        if (!isRoleApiAvailable()) return false
        return try {
            val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
            val available = roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
            if (available) {
                Log.i(TAG, "JARVIS_ASSISTANT_ROLE_AVAILABLE")
            }
            available
        } catch (t: Throwable) {
            Log.w(TAG, "Assistant role availability check failed", t)
            false
        }
    }

    fun isAssistantRoleHeld(context: Context): Boolean {
        if (!isRoleApiAvailable()) return false
        return try {
            val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
            val held = roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            if (held) {
                Log.i(TAG, "JARVIS_ASSISTANT_ROLE_ACTIVE")
            }
            held
        } catch (t: Throwable) {
            Log.w(TAG, "Assistant role held check failed", t)
            false
        }
    }

    /**
     * Returns an intent to request the assistant role, or null if unsupported.
     * Caller must launch with Activity result APIs.
     */
    fun createRequestRoleIntent(context: Context): Intent? {
        if (!isAssistantRoleAvailable(context)) return null
        if (isAssistantRoleHeld(context)) return null
        return try {
            val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
            roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
        } catch (t: Throwable) {
            Log.w(TAG, "createRequestRoleIntent failed", t)
            null
        }
    }

    /** Fallback: open voice-input / assistant settings screens. */
    fun openAssistantSettings(activity: Activity) {
        val candidates = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            try {
                activity.startActivity(intent)
                return
            } catch (_: Throwable) {
                // try next
            }
        }
    }

    private const val TAG = "AssistantRole"
}
