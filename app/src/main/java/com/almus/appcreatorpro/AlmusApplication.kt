package com.almus.appcreatorpro

import android.app.Application
import com.almus.appcreatorpro.di.AppContainer

class AlmusApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Continue following any cloud build that was still running when the app was last closed.
        container.buildTracker.resumeUnfinished()
    }
}
