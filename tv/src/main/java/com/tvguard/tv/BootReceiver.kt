package com.tvguard.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> GuardService.start(context)
        }
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
