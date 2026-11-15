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
  assert.equal(L.openRequest({ at: 100, reason: "bedtime" }, { requestAt: 50 }, 200).why, "during bedtime");
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
