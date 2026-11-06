package com.almus.appcreatorpro

import android.app.Application
import com.almus.appcreatorpro.di.AppContainer

class AlmusApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Crash fix: each start-up step is isolated. A problem in background tracking, notifications or WorkManager
        // (for example a damaged saved record) must never stop the app from opening.
        runCatching {
            // Continue following any cloud build that was still running when the app was last closed.
            container.buildTracker.resumeUnfinished()
        }
        runCatching {
            // Phase 7: notifications and the WorkManager schedule follow every change of a build.
            container.buildMonitor.start()
        }
        runCatching {
            // Builds whose checking had stopped while the app was closed are looked at again once.
            container.buildMonitor.recoverOnStart()
        }
    }
}
