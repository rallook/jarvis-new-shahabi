package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.overlay.JarvisOverlayController
import com.jarvis.assistant.wake.JarvisHandsFreeController

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Warm the overlay controller so lifecycle cleanup stays consistent.
        JarvisOverlayController.getInstance(this)
        // Initialize hands-free wake layer (respects Settings default ON).
        JarvisHandsFreeController.getInstance(this).initialize()
    }

    override fun onTerminate() {
        JarvisHandsFreeController.getInstance(this).shutdown()
        JarvisOverlayController.getInstance(this).stop()
        super.onTerminate()
    }
}
