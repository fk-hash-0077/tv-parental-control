package com.tvguard.tv

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Brings TV Guard's background service back if it isn't running: every 15 minutes, and once
 * shortly after a self-update. Jobs are kept by the system across app updates, so this works
 * even when the TV's software doesn't deliver the "app was updated" broadcast.
 */
class KeepAliveJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        if (GuardService.current == null) {
            AppLog.init(this)
            AppLog.i("TvGuard", "Starting from keep-alive job ${params.jobId}")
        }
        runCatching { GuardService.start(this) }
            .onFailure { AppLog.w("TvGuard", "Keep-alive couldn't start the service", it) }
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val PERIODIC_ID = 1001
        private const val AFTER_UPDATE_ID = 1002

        private fun component(context: Context) = ComponentName(context, KeepAliveJob::class.java)

        /** Every 15 minutes (the shortest the system allows). Safe to call often. */
        fun schedulePeriodic(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(PERIODIC_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(PERIODIC_ID, component(context))
                    .setPeriodic(15 * 60_000L)
                    .setPersisted(true)
                    .build()
            )
        }

        /** Once, about [delayMs] from now: set just before installing an update. */
        fun scheduleAfterUpdate(context: Context, delayMs: Long) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            scheduler.schedule(
                JobInfo.Builder(AFTER_UPDATE_ID, component(context))
                    .setMinimumLatency(delayMs)
                    .setOverrideDeadline(delayMs + 30_000L)
                    .setPersisted(true)
                    .build()
            )
        }
    }
}
