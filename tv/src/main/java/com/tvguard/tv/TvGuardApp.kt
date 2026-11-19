package com.tvguard.tv

import android.app.Application

/**
 * Starts the log as soon as any part of TV Guard runs, and records crashes with their full
 * stack trace (see [AppLog.recordCrash]) before Android closes the app.
 */
class TvGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { AppLog.recordCrash(this, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }
}
