package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.overlay.JarvisOverlayController
import com.jarvis.assistant.wake.JarvisHandsFreeController

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Warm the overlay controller so lifecycle cleanup stays consistent.
        JarvisOverlayController.getInstance(this)
        // Bridge for system assistant invocations (no continuous wake STT).
        JarvisHandsFreeController.getInstance(this).initialize()
    }

    override fun onTerminate() {
        JarvisHandsFreeController.getInstance(this).shutdown()
        JarvisOverlayController.getInstance(this).stop()
        super.onTerminate()
    }
}
