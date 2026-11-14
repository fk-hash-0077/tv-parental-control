package com.tvguard.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.time.LocalDateTime
import java.time.ZoneId

/** Applies the current config: suspends the listed apps, locks the whole TV, and warns before a lock. */
object Enforcer {

    class Result(val status: GuardStatus, val usage: UsageTracker.Usage)

    /** How long installs stay allowed while TV Guard updates itself. */
    const val UPDATE_WINDOW_MS = 10 * 60_000L

    fun evaluate(context: Context): Result {
        val store = Store(context)
        val policy = Policy(context)
        val tracker = UsageTracker(context)
        val config = store.config
        val nowMillis = System.currentTimeMillis()

        if (config.releaseOwner) {
            store.lockReason = ""
            policy.setSuspended(store.suspended, false)
            store.suspended = emptySet()
            policy.release()
            val status = status(
                context, store, config, used = 0, limit = 0, limitReached = false, reason = "",
                appsAllowed = true, overrideUntil = 0, suspended = false, released = true,
            )
            return Result(status, UsageTracker.Usage(0, emptyMap()))
        }

        policy.harden()
        val updating = nowMillis - store.updatingSince in 0 until UPDATE_WINDOW_MS
        policy.setInstallsAllowed(config.installsAllowed || updating)

        val usage = tracker.today()
        val overrideUntil = maxOf(config.overrideUntil, store.localOverrideUntil)
        val decision = Rules.decide(config, usage.totalMinutes, LocalDateTime.now(), overrideUntil, nowMillis)

        val target = if (decision.blockApps) {
            config.packages.filter { policy.isInstalled(it) }.toSet()
        } else {
            emptySet()
        }
        policy.setSuspended(store.suspended - target, false)
        val failed = policy.setSuspended(target, true)
        store.suspended = target - failed

        val lock = decision.lockTv && policy.isDeviceOwner
        store.lockReason = if (lock) decision.reason else ""
        if (lock) {
            policy.allowLockTask()
            LockActivity.show(context)
        } else {
            maybeWarn(context, store, decision.minutesToLock, nowMillis)
        }

        val status = status(
            context, store, config,
            used = usage.totalMinutes,
            limit = decision.limitMinutes,
            limitReached = decision.limitReached,
            reason = store.lockReason,
            appsAllowed = Rules.appsAllowed(config, LocalDateTime.now()),
            overrideUntil = if (decision.overridden) overrideUntil else 0,
            suspended = target.isNotEmpty() && failed.isEmpty(),
            released = false,
        )
        return Result(status, usage)
    }

    /** Shows "TV locks in N minutes" once, 5 minutes or less before any whole-TV lock. */
    private fun maybeWarn(context: Context, store: Store, minutesToLock: Int?, nowMillis: Long) {
        if (minutesToLock == null || minutesToLock > WARN_MINUTES) return
        if (nowMillis - store.lastWarnedAt < WARN_REPEAT_MS) return
        store.lastWarnedAt = nowMillis
        val text = if (minutesToLock <= 1) {
            "TV Guard: the TV locks in 1 minute"
        } else {
            "TV Guard: the TV locks in $minutesToLock minutes"
        }
        val main = Handler(Looper.getMainLooper())
        // A long toast lasts about 3.5 seconds, so show it a few times in a row.
        repeat(3) { i ->
            main.postDelayed({ Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show() }, i * 3_600L)
        }
    }

    private fun status(
        context: Context,
        store: Store,
        config: GuardConfig,
        used: Int,
        limit: Int,
        limitReached: Boolean,
        reason: String,
        appsAllowed: Boolean,
        overrideUntil: Long,
        suspended: Boolean,
        released: Boolean,
    ): GuardStatus {
        val policy = Policy(context)
        return GuardStatus(
            usedMinutesToday = used,
            limitMinutesToday = limit,
            limitReached = limitReached,
            reason = reason,
            locked = reason.isNotEmpty(),
            appsAllowed = appsAllowed,
            overrideUntil = overrideUntil,
            suspended = suspended,
            deviceOwner = policy.isDeviceOwner,
            usageAccess = UsageTracker(context).hasAccess(),
            released = released,
            versionCode = BuildConfig.VERSION_CODE,
            update = store.updateState,
            pongAt = config.pingAt,
            timeZone = ZoneId.systemDefault().id,
            updatedAt = System.currentTimeMillis(),
        ).also { store.lastStatus = it }
    }

    private const val WARN_MINUTES = 5
    private const val WARN_REPEAT_MS = 10 * 60_000L
}
