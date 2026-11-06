package com.almus.appcreatorpro

import android.app.Application
import com.almus.appcreatorpro.di.AppContainer
import java.io.File

class AlmusApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Keep the reason of the last crash so the app can show it on the next start (see AiGeneratorScreen).
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { File(filesDir, "last_crash.txt").writeText(android.util.Log.getStackTraceString(error).take(6000)) } catch (ignored: Throwable) { }
            previousHandler?.uncaughtException(thread, error)
        }
        container = AppContainer(this)
        // Continue following any cloud build that was still running when the app was last closed.
        container.buildTracker.resumeUnfinished()
        // Phase 7: notifications and the WorkManager schedule follow every change of a build; builds whose checking had
        // stopped while the app was closed are looked at again once.
        container.buildMonitor.start()
        container.buildMonitor.recoverOnStart()
    }
}
