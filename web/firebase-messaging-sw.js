/*
 * Shows TV Guard alerts on iPhones and in browsers. The cloud functions send every alert as
 * data with a title and body (see functions/index.js). iPhones require each push to show a
 * notification, so this always shows one.
 */
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

self.addEventListener("push", (event) => {
  let payload = {};
  try {
    payload = event.data ? event.data.json() : {};
  } catch (e) {
    payload = {};
  }
  const data = payload.data || {};
  const note = payload.notification || {};
  const title = note.title || data.title || "TV Guard";
  const body = note.body || data.body || "Open TV Guard to see what changed.";
  event.waitUntil(self.registration.showNotification(title, {
    body,
    icon: "/icons/icon-192.png",
    badge: "/icons/icon-192.png",
    tag: data.type === "pending" ? `pending-${data.uid || ""}` : data.type || undefined,
    data,
  }));
});

// Tapping an alert opens TV Guard, or brings it forward if it's already open.
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    for (const client of windows) {
      if ("focus" in client) return client.focus();
    }
    return self.clients.openWindow("/");
  })());
});
