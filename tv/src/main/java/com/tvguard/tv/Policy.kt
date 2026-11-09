package com.tvguard.tv

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log

/** Device Owner actions. All of them are no-ops if the app isn't Device Owner. */
class Policy(private val context: Context) {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, AdminReceiver::class.java)

    val isDeviceOwner: Boolean
        get() = dpm.isDeviceOwnerApp(context.packageName)

    /** Stops kids from uninstalling the app. Safe to call repeatedly. */
    fun harden() {
        if (!isDeviceOwner) return
        dpm.setUninstallBlocked(admin, context.packageName, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            dpm.setPermissionGrantState(
                admin, context.packageName, Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        }
    }

    fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Returns the packages that could not be changed. */
    fun setSuspended(packages: Collection<String>, suspended: Boolean): Set<String> {
        if (packages.isEmpty()) return emptySet()
        if (!isDeviceOwner) return packages.toSet()
        return try {
            dpm.setPackagesSuspended(admin, packages.toTypedArray(), suspended).toSet()
        } catch (e: Exception) {
            Log.w(TAG, "setPackagesSuspended failed", e)
            packages.toSet()
        }
    }

    /**
     * Lets the bedtime screen pin itself with no Home, Back-to-launcher or
     * notification access, so the TV can't be used until it lets go.
     */
    fun allowLockTask() {
        if (!isDeviceOwner) return
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
    }

    /**
     * Blocks or allows installing and updating apps. The Play Store can't be
     * suspended like other apps (Android keeps it running to verify installs),
     * so this is how installs from it are stopped.
     */
    fun setInstallsAllowed(allowed: Boolean) {
        if (!isDeviceOwner) return
        if (allowed) {
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
        } else {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
        }
    }

    /** Gives up Device Owner so the app can be uninstalled normally. */
    fun release() {
        if (!isDeviceOwner) return
        setInstallsAllowed(true)
        dpm.setLockTaskPackages(admin, emptyArray())
        dpm.setUninstallBlocked(admin, context.packageName, false)
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(context.packageName)
    }

    companion object {
        private const val TAG = "TvGuardPolicy"
    }
}
