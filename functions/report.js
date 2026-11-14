/** Date and report helpers, kept apart so they can be tested without Firebase. */

/** Local date, weekday and hour for a time zone, without extra libraries. */
function localParts(timeZone, date) {
  let zone = timeZone || "UTC";
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: zone });
  } catch (e) {
    zone = "UTC";
  }
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-US", {
      timeZone: zone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      weekday: "short",
      hour: "2-digit",
      hourCycle: "h23",
    }).formatToParts(date).map((p) => [p.type, p.value]),
  );
  return { date: `${parts.year}-${parts.month}-${parts.day}`, weekday: parts.weekday, hour: Number(parts.hour) };
}

function formatMinutes(minutes) {
  const m = Math.round(minutes);
  return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60} min` : `${m} min`;
}

/** Builds the Sunday report from the last 7 days of history and per-app history. */
function weeklyReport(device, today) {
  const end = new Date(`${today}T12:00:00Z`);
  const days = [];
  for (let back = 0; back < 7; back++) {
    days.push(new Date(end.getTime() - back * 86400000).toISOString().slice(0, 10));
  }
  const history = device.history || {};
  const total = days.reduce((sum, day) => sum + (Number(history[day]) || 0), 0);
  const perApp = {};
  const appHistory = device.appHistory || {};
  for (const day of days) {
    for (const app of Object.values(appHistory[day] || {})) {
      if (!app || !app.label) continue;
      perApp[app.label] = (perApp[app.label] || 0) + (Number(app.minutes) || 0);
    }
  }
  const top = Object.entries(perApp)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 3)
    .map(([label, minutes]) => `${label} ${formatMinutes(minutes)}`);
  let body = `Total ${formatMinutes(total)}, about ${formatMinutes(total / 7)} a day.`;
  if (top.length > 0) body += ` Top apps: ${top.join(", ")}.`;
  return body;
}

module.exports = { localParts, weeklyReport, formatMinutes };
