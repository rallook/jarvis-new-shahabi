package com.jarvis.assistant.android

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log

sealed class TimerAlarmResult {
    data object Success : TimerAlarmResult()
    data class Failure(val step: String, val message: String) : TimerAlarmResult()
}

/**
 * Native Android timer/alarm via [AlarmClock] intents.
 * Does not use AccessibilityService and does not require SCHEDULE_EXACT_ALARM —
 * the system Clock app creates the timer/alarm.
 */
class TimerAlarmExecutor(private val context: Context) {

    fun setTimer(durationSeconds: Int, message: String = "Jarvis timer"): TimerAlarmResult {
        Log.i(TAG, "TIMER_REQUEST_RECEIVED — durationSeconds=$durationSeconds")
        if (durationSeconds <= 0) {
            return failTimer("Invalid durationSeconds=$durationSeconds")
        }
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return failTimer("No app available to handle SET_TIMER.")
            }
            context.startActivity(intent)
            Log.i(TAG, "TIMER_CREATED — durationSeconds=$durationSeconds")
            TimerAlarmResult.Success
        } catch (t: Throwable) {
            failTimer(t.message ?: "Failed to start SET_TIMER intent.")
        }
    }

    fun setAlarm(hour: Int, minute: Int, message: String = "Jarvis alarm"): TimerAlarmResult {
        Log.i(TAG, "ALARM_REQUEST_RECEIVED — hour=$hour minute=$minute")
        if (hour !in 0..23 || minute !in 0..59) {
            return failAlarm("Invalid time hour=$hour minute=$minute")
        }
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return failAlarm("No app available to handle SET_ALARM.")
            }
            context.startActivity(intent)
            Log.i(TAG, "ALARM_CREATED — hour=$hour minute=$minute")
            TimerAlarmResult.Success
        } catch (t: Throwable) {
            failAlarm(t.message ?: "Failed to start SET_ALARM intent.")
        }
    }

    private fun failTimer(message: String): TimerAlarmResult.Failure {
        Log.e(TAG, "TIMER_FAILED — $message")
        return TimerAlarmResult.Failure("TIMER_FAILED", message)
    }

    private fun failAlarm(message: String): TimerAlarmResult.Failure {
        Log.e(TAG, "ALARM_FAILED — $message")
        return TimerAlarmResult.Failure("ALARM_FAILED", message)
    }

    companion object {
        private const val TAG = "TimerAlarm"
    }
}
