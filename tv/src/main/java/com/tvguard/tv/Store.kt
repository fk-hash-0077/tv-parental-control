package com.tvguard.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * What the parent set from the phone. Cached locally so rules still apply offline.
 * Times of day are minutes after midnight, e.g. 15:30 is 930.
 */
data class GuardConfig(
    val blocked: Boolean = false,
    /** Monday to Friday limit; 0 means no limit. */
    val dailyLimitMinutes: Int = 60,
    /** Saturday and Sunday limit; 0 means no limit. */
    val weekendLimitMinutes: Int = 120,
    val packages: List<String> = DEFAULT_PACKAGES,
    /** Extra minutes for [bonusDate] (yyyy-MM-dd) only. */
    val bonusDate: String = "",
    val bonusMinutes: Int = 0,
    /** When on, blocked apps only open inside the allowed hours for the day. */
    val scheduleEnabled: Boolean = false,
    val weekdayStart: Int = 15 * 60,
    val weekdayEnd: Int = 19 * 60,
    val weekendStart: Int = 9 * 60,
    val weekendEnd: Int = 20 * 60,
    /** When on, the whole TV shows a lock screen between these times. */
    val bedtimeEnabled: Boolean = false,
    val bedtimeStart: Int = 21 * 60,
    val bedtimeEnd: Int = 7 * 60,
    /** Set from the phone to remove protection so the app can be uninstalled. */
    val releaseOwner: Boolean = false,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "blocked" to blocked,
        "dailyLimitMinutes" to dailyLimitMinutes,
        "weekendLimitMinutes" to weekendLimitMinutes,
        "packages" to packages,
        "bonusDate" to bonusDate,
        "bonusMinutes" to bonusMinutes,
        "scheduleEnabled" to scheduleEnabled,
        "weekdayStart" to weekdayStart,
        "weekdayEnd" to weekdayEnd,
        "weekendStart" to weekendStart,
        "weekendEnd" to weekendEnd,
        "bedtimeEnabled" to bedtimeEnabled,
        "bedtimeStart" to bedtimeStart,
        "bedtimeEnd" to bedtimeEnd,
        "releaseOwner" to releaseOwner,
    )

    companion object {
        val DEFAULT_PACKAGES = listOf(
            "com.google.android.youtube.tv",
            "com.google.android.youtube",
        )

        /** Reads a config written by the phone. Missing or malformed fields keep their defaults. */
        fun fromMap(map: Map<*, *>): GuardConfig {
            val d = GuardConfig()
            fun bool(key: String, default: Boolean) = map[key] as? Boolean ?: default
            fun int(key: String, default: Int) = (map[key] as? Number)?.toInt() ?: default
            val packages = (map["packages"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.filter { it.isNotBlank() }
            return GuardConfig(
                blocked = bool("blocked", d.blocked),
                dailyLimitMinutes = int("dailyLimitMinutes", d.dailyLimitMinutes),
                weekendLimitMinutes = int("weekendLimitMinutes", d.weekendLimitMinutes),
                // An empty list is a real choice (block nothing), so only fall back when missing.
                packages = packages ?: d.packages,
                bonusDate = map["bonusDate"] as? String ?: d.bonusDate,
                bonusMinutes = int("bonusMinutes", d.bonusMinutes),
                scheduleEnabled = bool("scheduleEnabled", d.scheduleEnabled),
                weekdayStart = int("weekdayStart", d.weekdayStart),
                weekdayEnd = int("weekdayEnd", d.weekdayEnd),
                weekendStart = int("weekendStart", d.weekendStart),
                weekendEnd = int("weekendEnd", d.weekendEnd),
                bedtimeEnabled = bool("bedtimeEnabled", d.bedtimeEnabled),
                bedtimeStart = int("bedtimeStart", d.bedtimeStart),
                bedtimeEnd = int("bedtimeEnd", d.bedtimeEnd),
                releaseOwner = bool("releaseOwner", false),
            )
        }
    }
}

/** What the TV reports back to the phone. */
data class GuardStatus(
    val usedMinutesToday: Int,
    /** Today's limit including bonus time; 0 means no limit. */
    val limitMinutesToday: Int,
    val limitReached: Boolean,
    /** Why apps are locked right now: bedtime, blocked, schedule, limit, or empty. */
    val reason: String,
    val bedtime: Boolean,
    val suspended: Boolean,
    val deviceOwner: Boolean,
    val usageAccess: Boolean,
    val released: Boolean,
    val updatedAt: Long,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "usedMinutesToday" to usedMinutesToday,
        "limitMinutesToday" to limitMinutesToday,
        "limitReached" to limitReached,
        "reason" to reason,
        "bedtime" to bedtime,
        "suspended" to suspended,
        "deviceOwner" to deviceOwner,
        "usageAccess" to usageAccess,
        "released" to released,
        "updatedAt" to updatedAt,
    )
}

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("guard", Context.MODE_PRIVATE)

    /** 12-character pairing code, generated once. Knowing it is what lets a phone control this TV. */
    val deviceId: String
        get() = prefs.getString(KEY_ID, null) ?: newId().also { prefs.edit().putString(KEY_ID, it).apply() }

    var config: GuardConfig
        get() = prefs.getString("config", null)
            ?.let { runCatching { GuardConfig.fromMap(jsonToMap(JSONObject(it))) }.getOrNull() }
            ?: GuardConfig()
        set(value) = prefs.edit().putString("config", JSONObject(value.toMap()).toString()).apply()

    /** Packages this app has suspended, so it can unsuspend ones removed from the list. */
    var suspended: Set<String>
        get() = prefs.getStringSet("suspended", emptySet())!!.toSet()
        set(value) = prefs.edit().putStringSet("suspended", value).apply()

    /** Read by the bedtime lock screen to know when to let go. */
    var bedtimeActive: Boolean
        get() = prefs.getBoolean("bedtime", false)
        set(value) = prefs.edit().putBoolean("bedtime", value).apply()

    var lastStatus: GuardStatus?
        get() {
            if (!prefs.contains("st_at")) return null
            return GuardStatus(
                usedMinutesToday = prefs.getInt("st_used", 0),
                limitMinutesToday = prefs.getInt("st_limit_min", 0),
                limitReached = prefs.getBoolean("st_limit", false),
                reason = prefs.getString("st_reason", "") ?: "",
                bedtime = prefs.getBoolean("st_bedtime", false),
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
                .putInt("st_limit_min", value.limitMinutesToday)
                .putBoolean("st_limit", value.limitReached)
                .putString("st_reason", value.reason)
                .putBoolean("st_bedtime", value.bedtime)
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

        private fun jsonToMap(json: JSONObject): Map<String, Any?> =
            json.keys().asSequence().associateWith { key ->
                when (val value = json.get(key)) {
                    is JSONArray -> (0 until value.length()).map { value.get(it) }
                    JSONObject.NULL -> null
                    else -> value
                }
            }
    }
}
