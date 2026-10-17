package com.tvguard.tv

import android.content.Context

/** Applies the current config: suspends the blocked apps or lets them run. */
object Enforcer {

    fun evaluate(context: Context): GuardStatus {
        val store = Store(context)
        val policy = Policy(context)
        val tracker = UsageTracker(context)
        val config = store.config

        if (config.releaseOwner) {
            policy.setSuspended(store.suspended, false)
            store.suspended = emptySet()
            policy.release()
            return status(policy, tracker, used = 0, limitReached = false, suspended = false, released = true)
                .also { store.lastStatus = it }
        }

        policy.harden()
        val used = tracker.minutesToday(config.packages)
        val limitReached = config.dailyLimitMinutes > 0 && used >= config.dailyLimitMinutes
        val target = if (config.blocked || limitReached) {
            config.packages.filter { policy.isInstalled(it) }.toSet()
        } else {
            emptySet()
        }

        policy.setSuspended(store.suspended - target, false)
        val failed = policy.setSuspended(target, true)
        store.suspended = target - failed

        return status(
            policy, tracker, used, limitReached,
            suspended = target.isNotEmpty() && failed.isEmpty(),
            released = false,
        ).also { store.lastStatus = it }
    }

    private fun status(
        policy: Policy,
        tracker: UsageTracker,
        used: Int,
        limitReached: Boolean,
        suspended: Boolean,
        released: Boolean,
    ) = GuardStatus(
        usedMinutesToday = used,
        limitReached = limitReached,
        suspended = suspended,
        deviceOwner = policy.isDeviceOwner,
        usageAccess = tracker.hasAccess(),
        released = released,
        updatedAt = System.currentTimeMillis(),
    )
}
