package com.tvguard.tv

import android.content.Context
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

        val parentUntil = activeParentUntil(store, config, nowMillis)
        val usage = tracker.today()
        val overrideUntil = maxOf(config.overrideUntil, store.localOverrideUntil)
        val decision = Rules.decide(
            config, usage.totalMinutes, LocalDateTime.now(), overrideUntil, nowMillis, parentUntil,
        )

        val target = if (decision.blockApps) {
            config.packages.filter { policy.isInstalled(it) }.toSet()
        } else {
            emptySet()
        }
        policy.setSuspended(store.suspended - target, false)
        val failed = policy.setSuspended(target, true)
        store.suspended = target - failed

        val lock = decision.lockTv && policy.isDeviceOwner
        val newReason = if (lock) decision.reason else ""
        if (newReason != store.lockReason) {
            val by = if (newReason == "manual" && config.lockNowBy.isNotEmpty()) {
                " (Lock now from ${config.lockNowBy} at ${AppLog.timeOf(config.lockNowAt)})"
            } else {
                ""
            }
            AppLog.i("TvGuardRules", if (newReason.isEmpty()) "TV unlocked" else "TV locked: $newReason$by")
        }
        store.lockReason = newReason
        if (lock) {
            policy.allowLockTask()
            LockActivity.show(context)
        } else if (decision.parentMode) {
            noteParentMode(context, store, parentUntil, decision)
        } else {
            maybeWarn(context, store, decision.minutesToLock, nowMillis)
        }
        if (!decision.parentMode && store.parentNotedFrom != 0L) {
            store.parentNotedFrom = 0
            AppLog.i("TvGuardRules", "Parent mode ended")
            GuardService.current?.sendEvent("parentOff")
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
            parentUntil = if (decision.parentMode) parentUntil else 0,
            parentMinutes = usage.parentMinutes,
        )
        return Result(status, usage)
    }

    /**
     * Records parent mode set from a phone (or the TV lock screen) and returns when it ends if
     * it's on right now, otherwise 0. The recorded periods also keep that time out of the
     * kids' TV time.
     */
    private fun activeParentUntil(store: Store, config: GuardConfig, nowMillis: Long): Long {
        if (config.parentFrom > 0) {
            val end = Rules.parentEnd(config.parentFrom, config.parentUntil, ZoneId.systemDefault())
            val known = store.parentPeriods.firstOrNull { it.first == config.parentFrom }
            if (known?.second != end) store.recordParentPeriod(config.parentFrom, end)
        }
        return store.parentPeriods
            .filter { (from, to) -> nowMillis in from until to }
            .maxOfOrNull { it.second } ?: 0L
    }

    /** "Parent mode is on" once when it starts, and a note 5 minutes before it ends. */
    private fun noteParentMode(context: Context, store: Store, until: Long, decision: Rules.Decision) {
        val active = store.parentPeriods.filter { (from, to) -> System.currentTimeMillis() in from until to }
        val from = active.minOfOrNull { it.first } ?: return
        val endsAt = formatClock(until)
        if (store.parentNotedFrom != from) {
            store.parentNotedFrom = from
            AppLog.i("TvGuardRules", "Parent mode on until $endsAt")
            WarningPopup.note(context, "👤", "Parent mode is on", "Until $endsAt. No limits, and this time isn't counted.")
            GuardService.current?.sendEvent("parentOn", mapOf("until" to endsAt))
        }
        val left = decision.parentMinutesLeft ?: return
        if (left <= WARN_MINUTES && store.parentEndWarnedFor != until) {
            store.parentEndWarnedFor = until
            val then = if (decision.minutesToLock != null) {
                "Then the kids' rules come back and the TV locks."
            } else {
                "Then the kids' rules come back."
            }
            WarningPopup.note(
                context, "👤",
                if (left <= 1) "Parent mode ends in 1 minute" else "Parent mode ends in $left minutes",
                then,
            )
        }
    }

    private fun formatClock(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))

    /** Shows "TV locks in N minutes" once, 5 minutes or less before any whole-TV lock. */
    private fun maybeWarn(context: Context, store: Store, minutesToLock: Int?, nowMillis: Long) {
        if (minutesToLock == null || minutesToLock > WARN_MINUTES) return
        if (nowMillis - store.lastWarnedAt < WARN_REPEAT_MS) return
        store.lastWarnedAt = nowMillis
        val locksAt = java.time.LocalTime.now().plusMinutes(minutesToLock.toLong())
            .format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
        WarningPopup.show(context, minutesToLock, locksAt)
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
        parentUntil: Long = 0,
        parentMinutes: Int = 0,
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
            photoAt = if (LockPhoto.exists(context)) store.photoAt else 0,
            bedtimePhotoAt = if (LockPhoto.exists(context, bedtime = true)) store.bedtimePhotoAt else 0,
            parentUntil = parentUntil,
            parentMinutesToday = parentMinutes,
            versionName = BuildConfig.VERSION_NAME,
        ).also { store.lastStatus = it }
    }

    private const val WARN_MINUTES = 5
    private const val WARN_REPEAT_MS = 10 * 60_000L
}
