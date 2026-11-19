/** Notification texts for the events that carry details from the TV. */
function eventMessages(info) {
  const minutes = Number(info.minutes) || 0;
  const watched = Number(info.watched) || 0;
  return {
    parentOn: [
      "Parent mode is on",
      `${info.until ? `Until ${info.until}. ` : ""}Nothing is locked or blocked, and TV time isn't counted.`,
    ],
    parentOff: ["Parent mode ended", "The kids' TV rules are back on."],
    gap: [
      `TV Guard was off for ${minutes} min`,
      `It's running again. ${watched} min of watching happened while it was off${
        info.since ? ` (from ${info.since})` : ""
      }, and has been counted. See Diagnostics for why it stopped.`,
    ],
  };
}

module.exports = { eventMessages };
