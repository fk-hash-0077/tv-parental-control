package com.tvguard.tv

import android.content.Context
import android.content.Intent

/** Apps a kid could open from the TV home screen, offered to the parent as things to block. */
object AppCatalog {

    data class App(val pkg: String, val label: String)

    fun launchable(context: Context): List<App> {
        val pm = context.packageManager
        val categories = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        return categories
            .flatMap { category ->
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            }
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { App(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
    }
}
