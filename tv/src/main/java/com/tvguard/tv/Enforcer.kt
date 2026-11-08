package com.tvguard.tv

import android.content.Context
import java.time.LocalDateTime

/** Applies the current config: suspends the blocked apps, or lets them run, and starts or ends bedtime. */
object Enforcer {

    fun evaluate(context: Context): GuardStatus {
        val store = Store(context)
        val policy = Policy(context)
        val tracker = UsageTracker(context)
        val config = store.config

        if (config.releaseOwner) {
            store.bedtimeActive = false
            policy.setSuspended(store.suspended, false)
            store.suspended = emptySet()
            policy.release()
            return GuardStatus(
                usedMinutesToday = 0, limitMinutesToday = 0, limitReached = false, reason = "",
                bedtime = false, suspended = false, deviceOwner = policy.isDeviceOwner,
                usageAccess = tracker.hasAccess(), released = true, updatedAt = System.currentTimeMillis(),
            ).also { store.lastStatus = it }
        }

        policy.harden()
        val used = tracker.minutesToday(config.packages)
        val decision = Rules.decide(config, used, LocalDateTime.now())

        val target = if (decision.blockApps) {
            config.packages.filter { policy.isInstalled(it) }.toSet()
        } else {
            emptySet()
        }
        policy.setSuspended(store.suspended - target, false)
        val failed = policy.setSuspended(target, true)
        store.suspended = target - failed

        store.bedtimeActive = decision.bedtime && policy.isDeviceOwner
        if (store.bedtimeActive) {
            policy.allowLockTask()
            LockActivity.show(context)
        }

        return GuardStatus(
            usedMinutesToday = used,
            limitMinutesToday = decision.limitMinutes,
            limitReached = decision.limitReached,
            reason = decision.reason,
            bedtime = store.bedtimeActive,
            suspended = target.isNotEmpty() && failed.isEmpty(),
            deviceOwner = policy.isDeviceOwner,
            usageAccess = tracker.hasAccess(),
            released = false,
            updatedAt = System.currentTimeMillis(),
        ).also { store.lastStatus = it }
    }
}
