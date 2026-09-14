package com.jarvis.assistant.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R

/**
 * Foreground microphone service required on newer Android versions when
 * capturing audio while the app may briefly lose focus during WhatsApp handoff,
 * or while hands-free wake listening is active.
 * Recognition itself remains in [SpeechRecognizerManager] / [com.jarvis.assistant.wake.WakeWordEngine].
 */
class SpeechRecognitionForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_COMMAND
                startAsForeground(mode)
            }
        }
        return START_STICKY
    }

    private fun startAsForeground(mode: String) {
        ensureChannel()
        val text = if (mode == MODE_WAKE) {
            getString(R.string.wake_service_notification)
        } else {
            getString(R.string.speech_service_notification)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.speech_service_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.jarvis.assistant.voice.START"
        const val ACTION_STOP = "com.jarvis.assistant.voice.STOP"
        const val EXTRA_MODE = "mode"
        const val MODE_COMMAND = "command"
        const val MODE_WAKE = "wake"
        private const val CHANNEL_ID = "jarvis_mic"
        private const val NOTIFICATION_ID = 42
    }
}
