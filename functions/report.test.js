// Run with: node --test
const test = require("node:test");
const assert = require("node:assert");
const { localParts, weeklyReport, formatMinutes } = require("./report");

test("local time follows the TV's time zone", () => {
  // 2026-11-15 15:00 UTC is Sunday 20:00 in Karachi (UTC+5).
  const parts = localParts("Asia/Karachi", new Date("2026-11-15T15:00:00Z"));
  assert.deepStrictEqual(parts, { date: "2026-11-15", weekday: "Sun", hour: 20 });
});

test("an unknown time zone falls back to UTC", () => {
  assert.strictEqual(localParts("Not/AZone", new Date("2026-11-15T15:00:00Z")).hour, 15);
});

test("weekly report totals the last 7 days and lists top apps", () => {
  const device = {
    history: { "2026-11-15": 120, "2026-11-10": 60, "2026-11-01": 999 },
    appHistory: {
      "2026-11-15": [{ label: "YouTube", minutes: 100 }, { label: "Live TV", minutes: 20 }],
      "2026-11-10": [{ label: "Roblox", minutes: 60 }],
    },
  };
  assert.strictEqual(
    weeklyReport(device, "2026-11-15"),
    "Total 3 h 0 min, about 26 min a day. Top apps: YouTube 1 h 40 min, Roblox 1 h 0 min, Live TV 20 min.",
  );
});

test("minutes format", () => {
  assert.strictEqual(formatMinutes(45), "45 min");
  assert.strictEqual(formatMinutes(125), "2 h 5 min");
});
