/**
 * TV Guard cloud functions: turn what the TV writes into instant notifications on the
 * parents' phones. Deployed by the GitHub workflow when the FIREBASE_SERVICE_ACCOUNT secret is set.
 *
 * At family use this stays far inside Firebase's free monthly allowance: a few hundred
 * function calls a month against about 2 million free.
 */
const { onValueWritten, onValueCreated } = require("firebase-functions/v2/database");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");
const { localParts, weeklyReport } = require("./report");

admin.initializeApp();

// Written by the workflow from google-services.json (functions/database.json), so the triggers
// watch the right database. Read from a file because the deploy tool analyses this code
// before it loads .env files.
const db = (() => {
  try {
    const config = require("./database.json");
    return { instance: config.instance || undefined, region: config.region || "us-central1" };
  } catch (e) {
    return { instance: undefined, region: "us-central1" };
  }
})();
const OFFLINE_AFTER_MS = 20 * 60 * 1000;

/** Sends a data message to every approved phone of a TV, and forgets tokens that no longer work. */
async function notify(deviceId, data) {
  const tokensRef = admin.database().ref(`devices/${deviceId}/tokens`);
  const snapshot = await tokensRef.get();
  const entries = Object.entries(snapshot.val() || {}).filter(([, token]) => typeof token === "string");
  if (entries.length === 0) return;
  const payload = { device: deviceId, ...data };
  for (const key of Object.keys(payload)) payload[key] = String(payload[key]);
  const response = await admin.messaging().sendEachForMulticast({
    tokens: entries.map(([, token]) => token),
    data: payload,
    android: { priority: "high" },
  });
  const stale = {};
  response.responses.forEach((result, i) => {
    const code = result.error && result.error.code;
    if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-argument") {
      stale[entries[i][0]] = null;
    }
  });
  if (Object.keys(stale).length > 0) await tokensRef.update(stale);
}

/** Today's limit was used up. */
exports.limitReached = onValueWritten(
  { ref: "/devices/{id}/status/limitReached", ...db },
  async (event) => {
    if (event.data.after.val() !== true || event.data.before.val() === true) return;
    await notify(event.params.id, {
      type: "limit",
      title: "TV time is used up for today",
      body: "The TV is locked until midnight. You can give bonus time from TV Guard.",
    });
  },
);

/** "Ask for more time" pressed on the TV lock screen. */
exports.moreTimeRequested = onValueWritten(
  { ref: "/devices/{id}/request", ...db },
  async (event) => {
    const after = event.data.after.val();
    const before = event.data.before.val();
    if (!after || !after.at || (before && before.at === after.at)) return;
    const why = { bedtime: "during bedtime", schedule: "outside allowed hours" }[after.reason] ||
      "after today's limit";
    await notify(event.params.id, {
      type: "request",
      title: "The TV is asking for more time",
      body: `Someone on the TV wants more time ${why}.`,
      requestAt: after.at,
      reason: after.reason || "limit",
    });
  },
);

/** A new phone wants to pair and needs an approved phone to say yes. */
exports.phoneWantsToPair = onValueCreated(
  { ref: "/devices/{id}/pending/{uid}", ...db },
  async (event) => {
    const info = event.data.val() || {};
    const parents = await admin.database().ref(`devices/${event.params.id}/parents`).get();
    if (!parents.exists()) return; // The TV approves the first phone itself.
    await notify(event.params.id, {
      type: "pending",
      title: "A new phone wants to pair with your TV",
      body: `${info.name || "A phone"} is asking to control the TV. Approve it only if it's yours.`,
      uid: event.params.uid,
      name: info.name || "Phone",
    });
  },
);

/** Unlocks and failed unlock attempts on the TV lock screen. */
exports.lockScreenEvent = onValueCreated(
  { ref: "/devices/{id}/events/{eventId}", ...db },
  async (event) => {
    const info = event.data.val() || {};
    const messages = {
      unlock: ["The TV was unlocked", "Someone used a one-time unlock code. The TV is unlocked for 30 minutes."],
      emergency: [
        "The TV was unlocked with the emergency code",
        "The TV is unlocked for 30 minutes. The emergency code won't work again; set a new one in TV Guard.",
      ],
      failed: ["Wrong unlock code on the TV", "Someone typed a wrong arrow code on the TV lock screen."],
    };
    const [title, body] = messages[info.type] || ["TV Guard", "Something happened on the TV lock screen."];
    await notify(event.params.id, { type: info.type || "event", title, body });
    await event.data.ref.remove();
  },
);

/**
 * Every 10 minutes: tells parents when a TV has stopped checking in for 20 minutes,
 * sends the weekly report on Sunday at 8 pm TV time, and trims old per-app history.
 */
exports.checkTvs = onSchedule({ schedule: "every 10 minutes", region: db.region }, async () => {
  const devices = (await admin.database().ref("devices").get()).val() || {};
  const now = Date.now();
  for (const [id, device] of Object.entries(devices)) {
    if (!device || !device.tokens) continue;
    const status = device.status || {};
    const alerts = device.alerts || {};
    const updates = {};
    try {
      const seen = Number(status.updatedAt) || 0;
      if (seen > 0 && !status.released && now - seen > OFFLINE_AFTER_MS && alerts.offlineFor !== seen) {
        updates["alerts/offlineFor"] = seen;
        await notify(id, {
          type: "offline",
          title: "The TV stopped checking in",
          body: "It may be switched off, offline, or TV Guard stopped. Open TV Guard and tap Sync now to check.",
        });
      }

      const local = localParts(status.timeZone, new Date(now));
      if (local.weekday === "Sun" && local.hour === 20 && alerts.weeklyFor !== local.date) {
        updates["alerts/weeklyFor"] = local.date;
        await notify(id, { type: "weekly", title: "This week's TV time", body: weeklyReport(device, local.date) });
      }

      const oldest = new Date(now - 14 * 86400000).toISOString().slice(0, 10);
      for (const day of Object.keys(device.appHistory || {})) {
        if (day < oldest) updates[`appHistory/${day}`] = null;
      }
      for (const day of Object.keys(device.history || {})) {
        if (day < oldest) updates[`history/${day}`] = null;
      }
    } catch (e) {
      logger.error(`Check failed for a TV`, e);
    }
    if (Object.keys(updates).length > 0) await admin.database().ref(`devices/${id}`).update(updates);
  }
});
