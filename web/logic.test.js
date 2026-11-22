import { test } from "node:test";
import assert from "node:assert/strict";
import * as L from "./logic.js";

test("unlock codes match the TV's Totp.kt", async () => {
  // Vectors from HMAC-SHA1 with secret bytes 0..19, the same steps as Totp.kt.
  const secret = L.decodeSecret("AAECAwQFBgcICQoLDA0ODxAREhM=");
  assert.deepEqual(await L.totpCode(secret, 0), [2, 0, 2, 0, 3, 3]);
  assert.deepEqual(await L.totpCode(secret, 12345), [3, 2, 0, 3, 0, 2]);
  assert.deepEqual(await L.totpCode(secret, 14710000), [3, 1, 3, 2, 1, 2]);
  assert.equal(L.formatArrows([0, 1, 2, 3]), "↑↓←→");
  assert.equal(L.counterAt(240000), 2);
  assert.equal(L.secondsLeft(241000), 119);
  assert.equal(L.decodeSecret(""), null);
  assert.equal(L.decodeSecret("%%%"), null);
});

test("pairing code is cleaned and dashed", () => {
  assert.equal(L.cleanCode("pybk-sy6v b52w extra"), "PYBKSY6VB52W");
  assert.equal(L.dashCode("pybksy6vb5"), "PYBK-SY6V-B5");
});

test("formatting", () => {
  assert.equal(L.formatMinutes(34), "34 min");
  assert.equal(L.formatMinutes(86), "1 h 26 min");
  assert.equal(L.formatMinutes(120), "2 h");
  assert.equal(L.formatShort(86), "1h 26m");
  assert.equal(L.formatShort(120), "2h");
  assert.equal(L.formatTime(21 * 60), "9:00 PM");
  assert.equal(L.formatTime(0), "12:00 AM");
  assert.equal(L.formatTime(12 * 60 + 5), "12:05 PM");
  assert.equal(L.toTimeInput(7 * 60), "07:00");
  assert.equal(L.fromTimeInput("21:30"), 1290);
  assert.equal(L.fromTimeInput("bad"), null);
});

test("answering a request matches the Android app", () => {
  const today = "2026-11-15";
  assert.deepEqual(L.answerUpdates({ bonusDate: today, bonusMinutes: 15 }, "limit", 30, today, 0),
    { bonusDate: today, bonusMinutes: 45 });
  assert.deepEqual(L.answerUpdates({ bonusDate: "2026-11-14", bonusMinutes: 15 }, "limit", 15, today, 0),
    { bonusDate: today, bonusMinutes: 15 });
  assert.deepEqual(L.answerUpdates({ overrideUntil: 5000 }, "bedtime", 15, today, 1000),
    { overrideUntil: 5000 + 900000 });
  assert.deepEqual(L.answerUpdates({}, "schedule", 30, today, 1000), { overrideUntil: 1000 + 1800000 });
  assert.deepEqual(L.answerUpdates({}, "limit", 0, today, 1000), {});
});

test("open request", () => {
  assert.equal(L.openRequest(null, null, 10), null);
  assert.equal(L.openRequest({ at: 100 }, { requestAt: 100 }, 200), null);
  assert.equal(L.openRequest({ at: 100 }, null, 100 + 3600000), null);
  assert.equal(L.openRequest({ at: 100, reason: "hours" }, { requestAt: 50 }, 200).why, "outside TV hours");
  assert.equal(L.openRequest({ at: 100, reason: "manual" }, null, 200).why, "while you've locked it");
  assert.equal(L.openRequest({ at: 100, reason: "limit" }, null, 200).why, "after today's TV time");
});

test("usage and history", () => {
  const usage = { date: "2026-11-15", apps: [{ label: "Live TV", minutes: 22 }, { label: "Netflix", pkg: "n", minutes: 52 }] };
  assert.deepEqual(L.todaysApps(usage, "2026-11-15").map((a) => a.label), ["Netflix", "Live TV"]);
  assert.deepEqual(L.todaysApps(usage, "2026-11-16"), []);
  const week = L.lastWeek({ "2026-11-15": 90, "2026-11-09": 30 }, "2026-11-15");
  assert.equal(week.length, 7);
  assert.equal(week[0].date, "2026-11-09");
  assert.equal(week[0].minutes, 30);
  assert.equal(week[6].minutes, 90);
});

test("config helpers", () => {
  assert.deepEqual(L.packages({}), L.DEFAULT_PACKAGES);
  assert.deepEqual(L.packages({ packages: ["a"] }), ["a"]);
  assert.deepEqual(L.packages({ packages: { 0: "a", 1: "b" } }), ["a", "b"]);
  assert.equal(L.ringTone(100, 120, false), "warn");
  assert.equal(L.ringTone(30, 120, false), "ok");
  assert.equal(L.ringTone(30, 120, true), "bad");
  assert.equal(L.connection(true, 1000, 1000 + 30000, String).pill, "Online");
  assert.equal(L.connection(true, 0, 5, String).tone, "warn");
  assert.equal(L.deviceName("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)"), "iPhone");
});

test("TV time, lock now and versions match the Android app", () => {
  const fmt = (ms) => new Date(ms).toISOString().slice(11, 16);
  const now = new Date(2026, 10, 20, 18, 0).getTime();
  assert.equal(L.lockPill("hours").text, "🌙 Outside TV hours");
  assert.equal(L.lockPill("manual", "Locked by this phone at 5").text, "🔒 Locked by this phone at 5");
  assert.equal(L.lockedByShort({}, "me", now, fmt), "Locked from a phone");
  assert.match(L.lockedByShort({ lockNowBy: "Pixel", lockNowByUid: "me", lockNowAt: now - 60000 }, "me", now, fmt), /^Locked by this phone at /);
  assert.match(L.lockedByShort({ lockNowBy: "Pixel", lockNowByUid: "x", lockNowAt: now - 86400000 }, "me", now, fmt), /^Locked by Pixel yesterday at /);
  assert.match(L.formatDayTime(new Date(2026, 10, 17, 9, 0).getTime(), now, fmt), /^on Tue 17 Nov at /);
  assert.deepEqual(L.lockNowLines({ lockNow: true }, fmt).length, 2);
  assert.equal(L.versionLabel("1.0.2", 41), "1.0.2");
  assert.equal(L.versionLabel("0.40", 40), "build 40");
  assert.equal(L.versionLabel(null, 39), "build 39");
  const friday = new Date(2026, 10, 20, 12);
  const saturday = new Date(2026, 10, 21, 12);
  assert.deepEqual(L.hoursToday({}, friday), [900, 1140]);
  assert.deepEqual(L.hoursToday({ weekendStart: 600 }, saturday), [600, 1200]);
  assert.equal(L.tvTimeSummary({ scheduleEnabled: true }, friday),
    "📺 Today: TV can be on 3:00 PM to 7:00 PM, for up to 2 h.\nIt locks at 7:00 PM, or sooner if the 2 h run out.");
  assert.equal(L.tvTimeSummary({ dailyLimitMinutes: 0 }, friday), "📺 Today: No TV hours and no daily limit. The TV never locks by itself.");
});

test("bedtime moves into TV hours like on Android", () => {
  assert.equal(L.bedtimeMigration({ bedtimeEnabled: false }), null);
  assert.deepEqual(L.bedtimeMigration({ bedtimeEnabled: true, scheduleEnabled: true, weekdayStart: 900, weekdayEnd: 1320,
    weekendStart: 360, weekendEnd: 1200, bedtimeStart: 1260, bedtimeEnd: 420 }),
  { scheduleEnabled: true, weekdayStart: 900, weekdayEnd: 1260, weekendStart: 420, weekendEnd: 1200, bedtimeEnabled: false });
  assert.deepEqual(L.bedtimeMigration({ bedtimeEnabled: true }),
    { scheduleEnabled: true, weekdayStart: 420, weekdayEnd: 1260, weekendStart: 420, weekendEnd: 1260, bedtimeEnabled: false });
});

test("parent mode ends by 6 AM", () => {
  const evening = new Date(2026, 10, 20, 21, 0).getTime();
  assert.equal(L.parentCutoff(evening), new Date(2026, 10, 21, 6, 0).getTime());
  const early = new Date(2026, 10, 21, 5, 30).getTime();
  assert.equal(L.parentCutoff(early), new Date(2026, 10, 22, 6, 0).getTime());
  const config = { parentFrom: evening, parentUntil: evening + 2 * L.HOUR_MS };
  assert.equal(L.parentActiveUntil(config, evening + L.HOUR_MS), evening + 2 * L.HOUR_MS);
  assert.equal(L.parentActiveUntil(config, evening + 3 * L.HOUR_MS), 0);
  assert.equal(L.parentActiveUntil({ parentFrom: evening, parentUntil: evening + 20 * L.HOUR_MS }, evening + 10 * L.HOUR_MS), 0);
  assert.equal(L.parentActiveUntil({}, evening), 0);
});
