package com.tvguard.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, QUICKBOOT, QUICKBOOT_HTC -> "TV turned on"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app updated"
            // Sent when the clock is synced or the time zone set, which often happens on wake.
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> "clock synced"
            else -> return
        }
        if (GuardService.current != null) return
        AppLog.init(context)
        AppLog.i("TvGuard", "Starting after ${intent.action?.substringAfterLast('.')}")
        runCatching { GuardService.start(context, reason) }
            .onFailure { AppLog.w("TvGuard", "Couldn't start after ${intent.action}", it) }
        Protection.armAlarm(context)
    }

    companion object {
        /** "Fast boot" power-on on some TVs and phones instead of BOOT_COMPLETED. */
        private const val QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        private const val QUICKBOOT_HTC = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}

/**
 * Recovery when every paired phone is lost. Run from a computer:
 *   adb shell am broadcast -a com.tvguard.tv.RESET_PHONES -n com.tvguard.tv/.ResetPhonesReceiver
 * Protected by the DUMP permission, which adb's shell has and normal apps can't get.
 */
class ResetPhonesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = GuardService.current?.resetPhones() == true
        resultData = if (done) "Paired phones removed. Pair a phone again." else "TV Guard isn't connected yet. Try again in a minute."
        if (!done) GuardService.start(context)
    }
}
