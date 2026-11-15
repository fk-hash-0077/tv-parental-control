/**
 * The parts of the web parent app that don't touch the page or Firebase, so they can be
 * tested with `node --test`. Field names and defaults must match the Android phone app
 * (phone/.../MainActivity.kt) and GuardConfig in the TV app.
 */

export const DEFAULT_PACKAGES = [
  "com.google.android.youtube.tv",
  "com.google.android.youtube",
  "com.google.android.youtube.tvkids",
  "com.roblox.client",
];

/** Config fields that hold a time of day in minutes after midnight, with their defaults. */
export const TIME_DEFAULTS = {
  weekdayStart: 15 * 60,
  weekdayEnd: 19 * 60,
  weekendStart: 9 * 60,
  weekendEnd: 20 * 60,
  bedtimeStart: 21 * 60,
  bedtimeEnd: 7 * 60,
};

// ---- One-time unlock codes (same as Totp.kt on the phone and TV) ----

export const STEP_SECONDS = 120;
export const CODE_LENGTH = 6;
export const ARROWS = "↑↓←→";

export function counterAt(epochMillis) {
  return Math.floor(epochMillis / 1000 / STEP_SECONDS);
}

export function secondsLeft(epochMillis) {
  return STEP_SECONDS - (Math.floor(epochMillis / 1000) % STEP_SECONDS);
}

/** Decodes the TV's Base64 secret, or returns null. */
export function decodeSecret(base64) {
  if (!base64) return null;
  try {
    const raw = atob(base64);
    const bytes = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
    return bytes.length > 0 ? bytes : null;
  } catch (e) {
    return null;
  }
}

/** The 6 arrows (0 up, 1 down, 2 left, 3 right) for one 2-minute window. */
export async function totpCode(secret, counter) {
  const key = await crypto.subtle.importKey("raw", secret, { name: "HMAC", hash: "SHA-1" }, false, ["sign"]);
  const message = new Uint8Array(8);
  let c = BigInt(counter);
  for (let i = 7; i >= 0; i--) {
    message[i] = Number(c & 0xffn);
    c >>= 8n;
  }
  const hash = new Uint8Array(await crypto.subtle.sign("HMAC", key, message));
  const offset = hash[hash.length - 1] & 0x0f;
  const value =
    ((hash[offset] & 0x7f) << 24) |
    (hash[offset + 1] << 16) |
    (hash[offset + 2] << 8) |
    hash[offset + 3];
  const arrows = [];
  for (let shift = CODE_LENGTH - 1; shift >= 0; shift--) arrows.push((value >>> (2 * shift)) & 3);
  return arrows;
}

export function formatArrows(code) {
  return code.map((a) => ARROWS[a]).join("");
}

// ---- Pairing code ----

/** Letters and numbers only, upper case, at most 12. */
export function cleanCode(text) {
  return String(text || "").toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 12);
}

/** XXXX-XXXX-XXXX, as the code is typed. */
export function dashCode(text) {
  return (cleanCode(text).match(/.{1,4}/g) || []).join("-");
}

// ---- Dates and numbers ----

/** yyyy-mm-dd in this phone's time zone, like LocalDate.now() on Android. */
export function localDate(date = new Date()) {
  const p = (n) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}`;
}

export function formatMinutes(minutes) {
  const m = Math.max(0, Math.round(minutes));
  return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60} min` : `${m} min`;
}

/** "1h 26m", for the ring. */
export function formatShort(minutes) {
  const m = Math.max(0, Math.round(minutes));
  if (m < 60) return `${m}m`;
  if (m % 60 === 0) return `${m / 60}h`;
  return `${Math.floor(m / 60)}h ${m % 60}m`;
}

/** Minutes after midnight as "9:00 PM". */
export function formatTime(minutes) {
  const total = ((Math.round(minutes) % 1440) + 1440) % 1440;
  const h = Math.floor(total / 60);
  const m = total % 60;
  const hour12 = h % 12 === 0 ? 12 : h % 12;
  return `${hour12}:${String(m).padStart(2, "0")} ${h < 12 ? "AM" : "PM"}`;
}

/** "21:30" for an <input type=time>. */
export function toTimeInput(minutes) {
  const total = ((Math.round(minutes) % 1440) + 1440) % 1440;
  return `${String(Math.floor(total / 60)).padStart(2, "0")}:${String(total % 60).padStart(2, "0")}`;
}

export function fromTimeInput(value) {
  const match = /^(\d{1,2}):(\d{2})/.exec(value || "");
  if (!match) return null;
  const h = Number(match[1]);
  const m = Number(match[2]);
  if (h > 23 || m > 59) return null;
  return h * 60 + m;
}

export function greeting(hour) {
  if (hour >= 5 && hour <= 11) return "Good morning";
  if (hour >= 12 && hour <= 16) return "Good afternoon";
  return "Good evening";
}

// ---- Reading config ----

export function num(config, key, fallback) {
  const v = config && config[key];
  return typeof v === "number" ? v : fallback;
}

export function packages(config) {
  const list = config && config.packages;
  if (Array.isArray(list)) return list.filter((p) => typeof p === "string");
  if (list && typeof list === "object") return Object.values(list).filter((p) => typeof p === "string");
  return DEFAULT_PACKAGES;
}

export function todaysBonus(config, today) {
  return config && config.bonusDate === today ? num(config, "bonusMinutes", 0) : 0;
}

/**
 * Config changes for answering "ask for more time": for a limit lock the minutes become bonus
 * time; for bedtime or allowed hours the TV stays unlocked that long. Same as Cloud.answerRequest.
 */
export function answerUpdates(config, reason, minutes, today, now) {
  if (minutes <= 0) return {};
  if (reason === "limit") {
    return { bonusDate: today, bonusMinutes: todaysBonus(config, today) + minutes };
  }
  const from = Math.max(num(config, "overrideUntil", 0), now);
  return { overrideUntil: from + minutes * 60000 };
}

/** The unanswered "ask for more time" from the last hour, or null. */
export function openRequest(request, answer, now) {
  const askedAt = request && typeof request.at === "number" ? request.at : 0;
  const answeredFor = answer && typeof answer.requestAt === "number" ? answer.requestAt : 0;
  if (askedAt <= 0 || askedAt === answeredFor || now - askedAt >= 3600000) return null;
  const why = { bedtime: "during bedtime", schedule: "outside allowed hours" }[request.reason] ||
    "after today's limit";
  return { at: askedAt, reason: request.reason || "limit", why };
}

/** Today's minutes per app, most watched first. */
export function todaysApps(usage, today) {
  if (!usage || usage.date !== today) return [];
  const apps = Array.isArray(usage.apps) ? usage.apps : Object.values(usage.apps || {});
  return apps
    .filter((a) => a && typeof a.minutes === "number" && typeof a.label === "string")
    .map((a) => ({ label: a.label, pkg: a.pkg || null, minutes: a.minutes }))
    .sort((a, b) => b.minutes - a.minutes);
}

/** The last 7 days of total minutes, oldest first, ending today. */
export function lastWeek(history, today) {
  const end = new Date(`${today}T12:00:00`);
  const days = [];
  for (let back = 6; back >= 0; back--) {
    const d = new Date(end.getTime() - back * 86400000);
    const key = localDate(d);
    const v = history && history[key];
    days.push({ date: key, minutes: typeof v === "number" ? v : 0, label: "SMTWTFS"[d.getDay()] });
  }
  return days;
}

/** How the connection to the TV looks: text plus tone (ok, warn, bad). */
export function connection(phoneOnline, seen, now, formatWhen) {
  const ago = Math.floor((now - seen) / 60000);
  if (!phoneOnline) return { pill: "Phone offline", text: "This phone is offline", tone: "bad", synced: "Tap to sync ⟳" };
  if (!seen) return { pill: "Waiting for the TV", text: "Waiting for the TV", tone: "warn", synced: "Tap to sync ⟳" };
  if (ago < 2) return { pill: "Online", text: "Connected to the TV", tone: "ok", synced: "Synced just now ⟳" };
  if (ago < 20) {
    return { pill: `TV last seen ${ago} min ago`, text: `TV last seen ${ago} min ago`, tone: "warn",
      synced: `Synced ${formatWhen(seen)} · tap to sync ⟳` };
  }
  return { pill: "TV offline", text: `TV offline, last seen ${formatWhen(seen)}`, tone: "bad",
    synced: `Synced ${formatWhen(seen)} · tap to sync ⟳` };
}

export function lockPill(reason) {
  return {
    bedtime: { text: "🌙 Bedtime lock", tone: "bad" },
    schedule: { text: "🔒 Outside allowed hours", tone: "bad" },
    limit: { text: "🔒 Time's up", tone: "bad" },
    manual: { text: "🔒 Locked by you", tone: "bad" },
  }[reason] || { text: "🔓 Unlocked", tone: "ok" };
}

/** Ring colour for today's use. */
export function ringTone(used, limit, locked) {
  const left = Math.max(0, limit - used);
  if (locked || (limit > 0 && left === 0)) return "bad";
  if (limit > 0 && (left <= 15 || used / limit >= 0.7)) return "warn";
  return "ok";
}

/** A sensible name for this phone in the parents list. */
export function deviceName(userAgent) {
  const ua = userAgent || "";
  if (/iPhone/.test(ua)) return "iPhone";
  if (/iPad/.test(ua)) return "iPad";
  if (/Android/.test(ua)) return "Android browser";
  if (/Mac/.test(ua)) return "Mac";
  if (/Windows/.test(ua)) return "Windows computer";
  return "Web browser";
}
