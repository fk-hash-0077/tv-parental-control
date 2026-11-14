package com.tvguard.tv

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * What the parent set from the phone. Cached locally so rules still apply offline.
 * Times of day are minutes after midnight, e.g. 15:30 is 930. Timestamps are epoch milliseconds.
 */
data class GuardConfig(
    /** The listed apps are blocked unless this is today's date (yyyy-MM-dd), so they re-block at midnight. */
    val unblockedDate: String = "",
    /** Total TV time allowed Monday to Friday; 0 means no limit. */
    val dailyLimitMinutes: Int = 120,
    /** Total TV time allowed Saturday and Sunday; 0 means no limit. */
    val weekendLimitMinutes: Int = 180,
    val packages: List<String> = DEFAULT_PACKAGES,
    /** Extra minutes for [bonusDate] (yyyy-MM-dd) only. */
    val bonusDate: String = "",
    val bonusMinutes: Int = 0,
    /** When on, the TV can only be used inside the allowed hours for the day. */
    val scheduleEnabled: Boolean = false,
    val weekdayStart: Int = 15 * 60,
    val weekdayEnd: Int = 19 * 60,
    val weekendStart: Int = 9 * 60,
    val weekendEnd: Int = 20 * 60,
    /** When on, the whole TV shows a lock screen between these times. */
    val bedtimeEnabled: Boolean = false,
    val bedtimeStart: Int = 21 * 60,
    val bedtimeEnd: Int = 7 * 60,
    /**
     * When false, nothing can be installed or updated on the TV, including from the
     * Play Store and over adb. TV Guard lifts this briefly for its own updates.
     */
    val installsAllowed: Boolean = false,
    /** Set by the phone when it approves more time during bedtime or outside allowed hours. */
    val overrideUntil: Long = 0,
    /** Bumped by the phone's Update TV button. */
    val updateRequestAt: Long = 0,
    /** Bumped by the phone's Sync now button; the TV echoes it back as status.pongAt. */
    val pingAt: Long = 0,
    /** Emergency arrow sequence set from the phone (digits 0-3), or empty when off. */
    val emergencyCode: String = "",
    /** When [emergencyCode] was set. Each one works once; the TV remembers which it used. */
    val emergencySetAt: Long = 0,
    /** Set from the phone to remove protection so the app can be uninstalled. */
    val releaseOwner: Boolean = false,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "unblockedDate" to unblockedDate,
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
        "installsAllowed" to installsAllowed,
        "overrideUntil" to overrideUntil,
        "updateRequestAt" to updateRequestAt,
        "pingAt" to pingAt,
        "emergencyCode" to emergencyCode,
        "emergencySetAt" to emergencySetAt,
        "releaseOwner" to releaseOwner,
    )

    companion object {
        val DEFAULT_PACKAGES = listOf(
            "com.google.android.youtube.tv",
            "com.google.android.youtube",
            "com.google.android.youtube.tvkids",
            "com.roblox.client",
        )

        /** Reads a config written by the phone. Missing or malformed fields keep their defaults. */
        fun fromMap(map: Map<*, *>): GuardConfig {
            val d = GuardConfig()
            fun bool(key: String, default: Boolean) = map[key] as? Boolean ?: default
            fun int(key: String, default: Int) = (map[key] as? Number)?.toInt() ?: default
            fun long(key: String, default: Long) = (map[key] as? Number)?.toLong() ?: default
            fun str(key: String, default: String) = map[key] as? String ?: default
            val packages = (map["packages"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.filter { it.isNotBlank() }
            return GuardConfig(
                unblockedDate = str("unblockedDate", d.unblockedDate),
                dailyLimitMinutes = int("dailyLimitMinutes", d.dailyLimitMinutes),
                weekendLimitMinutes = int("weekendLimitMinutes", d.weekendLimitMinutes),
                // An empty list is a real choice (block nothing), so only fall back when missing.
                packages = packages ?: d.packages,
                bonusDate = str("bonusDate", d.bonusDate),
                bonusMinutes = int("bonusMinutes", d.bonusMinutes),
                scheduleEnabled = bool("scheduleEnabled", d.scheduleEnabled),
                weekdayStart = int("weekdayStart", d.weekdayStart),
                weekdayEnd = int("weekdayEnd", d.weekdayEnd),
                weekendStart = int("weekendStart", d.weekendStart),
                weekendEnd = int("weekendEnd", d.weekendEnd),
                bedtimeEnabled = bool("bedtimeEnabled", d.bedtimeEnabled),
                bedtimeStart = int("bedtimeStart", d.bedtimeStart),
                bedtimeEnd = int("bedtimeEnd", d.bedtimeEnd),
                installsAllowed = bool("installsAllowed", d.installsAllowed),
                overrideUntil = long("overrideUntil", d.overrideUntil),
                updateRequestAt = long("updateRequestAt", d.updateRequestAt),
                pingAt = long("pingAt", d.pingAt),
                emergencyCode = str("emergencyCode", "").filter { it in '0'..'3' },
                emergencySetAt = long("emergencySetAt", 0),
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
    /** Why something is locked right now: bedtime, schedule, limit, blocked (listed apps only), or empty. */
    val reason: String,
    /** True when the whole TV shows the lock screen. */
    val locked: Boolean,
    val appsAllowed: Boolean,
    val overrideUntil: Long,
    val suspended: Boolean,
    val deviceOwner: Boolean,
    val usageAccess: Boolean,
    val released: Boolean,
    val versionCode: Int,
    val update: String,
    val pongAt: Long,
    val timeZone: String,
    val updatedAt: Long,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "usedMinutesToday" to usedMinutesToday,
        "limitMinutesToday" to limitMinutesToday,
        "limitReached" to limitReached,
        "reason" to reason,
        "locked" to locked,
        "appsAllowed" to appsAllowed,
        "overrideUntil" to overrideUntil,
        "suspended" to suspended,
        "deviceOwner" to deviceOwner,
        "usageAccess" to usageAccess,
        "released" to released,
        "versionCode" to versionCode,
        "update" to update,
        "pongAt" to pongAt,
        "timeZone" to timeZone,
        "updatedAt" to updatedAt,
    )
}

/** Local state on the TV. Everything here survives restarts but not a reinstall. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("guard", Context.MODE_PRIVATE)

    /** 12-character pairing code, generated once. */
    val deviceId: String
        get() = prefs.getString(KEY_ID, null) ?: newId().also { prefs.edit().putString(KEY_ID, it).apply() }

    /** Shared with paired phones to generate one-time unlock codes. Generated once. */
    val unlockSecret: ByteArray
        get() {
            prefs.getString(KEY_SECRET, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
            val secret = ByteArray(20).also { SecureRandom().nextBytes(it) }
            prefs.edit().putString(KEY_SECRET, Base64.encodeToString(secret, Base64.NO_WRAP)).apply()
            return secret
        }

    var config: GuardConfig
        get() = prefs.getString("config", null)
            ?.let { runCatching { GuardConfig.fromMap(jsonToMap(JSONObject(it))) }.getOrNull() }
            ?: GuardConfig()
        set(value) = prefs.edit().putString("config", JSONObject(value.toMap()).toString()).apply()

    /** Packages this app has suspended, so it can unsuspend ones removed from the list. */
    var suspended: Set<String>
        get() = prefs.getStringSet("suspended", emptySet())!!.toSet()
        set(value) = prefs.edit().putStringSet("suspended", value).apply()

    /** Why the whole TV is locked (bedtime, schedule, limit), or empty. Read by the lock screen. */
    var lockReason: String
        get() = prefs.getString("lock_reason", "") ?: ""
        set(value) = prefs.edit().putString("lock_reason", value).apply()

    /** Set when an unlock code is typed on the TV. */
    var localOverrideUntil: Long
        get() = prefs.getLong("override_until", 0)
        set(value) = prefs.edit().putLong("override_until", value).apply()

    /** Highest one-time code counter used, so each code works once. */
    var lastCodeCounter: Long
        get() = prefs.getLong("code_counter", 0)
        set(value) = prefs.edit().putLong("code_counter", value).apply()

    var failedAttempts: Int
        get() = prefs.getInt("failed", 0)
        set(value) = prefs.edit().putInt("failed", value).apply()

    var lockoutUntil: Long
        get() = prefs.getLong("lockout_until", 0)
        set(value) = prefs.edit().putLong("lockout_until", value).apply()

    /** The emergencySetAt of the emergency code already used, so it works only once. */
    var usedEmergencySetAt: Long
        get() = prefs.getLong("emergency_used", 0)
        set(value) = prefs.edit().putLong("emergency_used", value).apply()

    /** The emergency arrow sequence if one is set and not used yet. */
    val emergencyCode: List<Int>
        get() {
            val c = config
            if (c.emergencyCode.length < 4 || c.emergencySetAt == usedEmergencySetAt) return emptyList()
            return c.emergencyCode.map { it - '0' }
        }

    /** When the lock screen last asked for more time. */
    var lastRequestAt: Long
        get() = prefs.getLong("request_at", 0)
        set(value) = prefs.edit().putLong("request_at", value).apply()

    /** The parent's answer to the latest "ask for more time", shown on the lock screen. */
    var requestAnswer: String
        get() = prefs.getString("request_answer", "") ?: ""
        set(value) = prefs.edit().putString("request_answer", value).apply()

    var lastWarnedAt: Long
        get() = prefs.getLong("warned_at", 0)
        set(value) = prefs.edit().putLong("warned_at", value).apply()

    /** Progress of the latest self-update, reported to the phone. */
    var updateState: String
        get() = prefs.getString("update_state", "") ?: ""
        set(value) = prefs.edit().putString("update_state", value).apply()

    /** While set (and recent), installs stay allowed so TV Guard can update itself. */
    var updatingSince: Long
        get() = prefs.getLong("updating_since", 0)
        set(value) = prefs.edit().putLong("updating_since", value).apply()

    var handledUpdateRequestAt: Long
        get() = prefs.getLong("update_handled", 0)
        set(value) = prefs.edit().putLong("update_handled", value).apply()

    /** The version a self-update is installing, so the new version can report success. */
    var updateTarget: Int
        get() = prefs.getInt("update_target", 0)
        set(value) = prefs.edit().putInt("update_target", value).apply()

    var lastStatus: GuardStatus?
        get() {
            val json = prefs.getString("status", null) ?: return null
            return runCatching {
                val o = JSONObject(json)
                GuardStatus(
                    usedMinutesToday = o.optInt("usedMinutesToday"),
                    limitMinutesToday = o.optInt("limitMinutesToday"),
                    limitReached = o.optBoolean("limitReached"),
                    reason = o.optString("reason"),
                    locked = o.optBoolean("locked"),
                    appsAllowed = o.optBoolean("appsAllowed"),
                    overrideUntil = o.optLong("overrideUntil"),
                    suspended = o.optBoolean("suspended"),
                    deviceOwner = o.optBoolean("deviceOwner"),
                    usageAccess = o.optBoolean("usageAccess"),
                    released = o.optBoolean("released"),
                    versionCode = o.optInt("versionCode"),
                    update = o.optString("update"),
                    pongAt = o.optLong("pongAt"),
                    timeZone = o.optString("timeZone"),
                    updatedAt = o.optLong("updatedAt"),
                )
            }.getOrNull()
        }
        set(value) {
            value ?: return
            prefs.edit().putString("status", JSONObject(value.toMap()).toString()).apply()
        }

    companion object {
        private const val KEY_ID = "device_id"
        private const val KEY_SECRET = "unlock_secret"
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
