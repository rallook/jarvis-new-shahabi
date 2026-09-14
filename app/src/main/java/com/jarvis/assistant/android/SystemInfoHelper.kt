package com.jarvis.assistant.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Native system queries that must not go through AccessibilityService.
 */
object SystemInfoHelper {

    fun batteryStatusSpeech(context: Context): String {
        return try {
            val manager = context.getSystemService(BatteryManager::class.java)
            val level = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val sticky = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            when {
                level != null && level in 0..100 -> {
                    if (charging) {
                        "Your battery is at $level percent and charging."
                    } else {
                        "Your battery is at $level percent."
                    }
                }
                else -> "I couldn't read the battery level."
            }
        } catch (_: Throwable) {
            "I couldn't read the battery level."
        }
    }
}
