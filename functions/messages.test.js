// Run with: node --test
const test = require("node:test");
const assert = require("node:assert");
const { eventMessages } = require("./messages");

test("parent mode messages say until when", () => {
  const [title, body] = eventMessages({ until: "11:41 PM" }).parentOn;
  assert.strictEqual(title, "Parent mode is on");
  assert.match(body, /^Until 11:41 PM\. /);
  assert.doesNotMatch(eventMessages({}).parentOn[1], /Until/);
});

test("gap message gives how long and how much was watched", () => {
  const [title, body] = eventMessages({ minutes: 52, watched: 38, since: "4:10 PM" }).gap;
  assert.strictEqual(title, "TV Guard was off for 52 min");
  assert.match(body, /38 min of watching happened while it was off \(from 4:10 PM\)/);
});
