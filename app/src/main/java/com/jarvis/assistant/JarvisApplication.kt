package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.overlay.JarvisOverlayController

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Warm the overlay controller so lifecycle cleanup stays consistent.
        JarvisOverlayController.getInstance(this)
    }

    override fun onTerminate() {
        JarvisOverlayController.getInstance(this).stop()
        super.onTerminate()
    }
}
