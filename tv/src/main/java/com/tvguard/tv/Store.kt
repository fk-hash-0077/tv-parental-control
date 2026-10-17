package com.tvguard.tv

import android.content.Context
import java.security.SecureRandom

/** What the parent set from the phone. Cached locally so rules still apply offline. */
data class GuardConfig(
    val blocked: Boolean = false,
    /** 0 means no daily limit. */
    val dailyLimitMinutes: Int = 60,
    val packages: List<String> = DEFAULT_PACKAGES,
    /** Set from the phone to remove protection so the app can be uninstalled. */
    val releaseOwner: Boolean = false,
) {
    companion object {
        val DEFAULT_PACKAGES = listOf(
            "com.google.android.youtube.tv",
            "com.google.android.youtube",
        )
    }
}

/** What the TV reports back to the phone. */
data class GuardStatus(
    val usedMinutesToday: Int,
    val limitReached: Boolean,
    val suspended: Boolean,
    val deviceOwner: Boolean,
    val usageAccess: Boolean,
    val released: Boolean,
    val updatedAt: Long,
)

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("guard", Context.MODE_PRIVATE)

    /** 12-character pairing code, generated once. Knowing it is what lets a phone control this TV. */
    val deviceId: String
        get() = prefs.getString(KEY_ID, null) ?: newId().also { prefs.edit().putString(KEY_ID, it).apply() }

    var config: GuardConfig
        get() = GuardConfig(
            blocked = prefs.getBoolean("blocked", false),
            dailyLimitMinutes = prefs.getInt("limit", GuardConfig().dailyLimitMinutes),
            packages = prefs.getString("packages", null)?.split(',')?.filter { it.isNotBlank() }
                ?: GuardConfig.DEFAULT_PACKAGES,
            releaseOwner = prefs.getBoolean("release", false),
        )
        set(value) {
            prefs.edit()
                .putBoolean("blocked", value.blocked)
                .putInt("limit", value.dailyLimitMinutes)
                .putString("packages", value.packages.joinToString(","))
                .putBoolean("release", value.releaseOwner)
                .apply()
        }

    /** Packages this app has suspended, so it can unsuspend ones removed from the list. */
    var suspended: Set<String>
        get() = prefs.getStringSet("suspended", emptySet())!!.toSet()
        set(value) = prefs.edit().putStringSet("suspended", value).apply()

    var lastStatus: GuardStatus?
        get() {
            if (!prefs.contains("st_at")) return null
            return GuardStatus(
                usedMinutesToday = prefs.getInt("st_used", 0),
                limitReached = prefs.getBoolean("st_limit", false),
                suspended = prefs.getBoolean("st_susp", false),
                deviceOwner = prefs.getBoolean("st_owner", false),
                usageAccess = prefs.getBoolean("st_usage", false),
                released = prefs.getBoolean("st_released", false),
                updatedAt = prefs.getLong("st_at", 0),
            )
        }
        set(value) {
            value ?: return
            prefs.edit()
                .putInt("st_used", value.usedMinutesToday)
                .putBoolean("st_limit", value.limitReached)
                .putBoolean("st_susp", value.suspended)
                .putBoolean("st_owner", value.deviceOwner)
                .putBoolean("st_usage", value.usageAccess)
                .putBoolean("st_released", value.released)
                .putLong("st_at", value.updatedAt)
                .apply()
        }

    companion object {
        private const val KEY_ID = "device_id"
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        private fun newId(): String {
            val random = SecureRandom()
            return (1..12).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        }

        fun formatId(id: String) = id.chunked(4).joinToString("-")
    }
}
