/**
 * Pretend Firebase for previews (open the page with ?demo). Nothing leaves the browser.
 * ?demo=pair starts unpaired; ?demo=wait waits for approval; ?demo=request shows a request.
 */
const mode = new URLSearchParams(location.search).get("demo") || "home";
const UID = "demo-uid";
const CODE = "PYBKSY6VB52W";
const now = Date.now();
const today = (() => {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
})();
const day = (back) => {
  const d = new Date(Date.now() - back * 86400000);
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
};

const data = {
  devices: {
    [CODE]: {
      tvUid: "tv",
      secret: "AAECAwQFBgcICQoLDA0ODxAREhM=",
      parents: mode === "wait" ? { dad: { name: "Pixel 8" } } : { dad: { name: "Pixel 8" }, [UID]: { name: "Mom's iPhone" } },
      config: { dailyLimitMinutes: 120, weekendLimitMinutes: 180, scheduleEnabled: true, weekdayEnd: 20 * 60 + 30,
        emergencyCode: "012301", lockNowBy: "Pixel 8", lockNowAt: now - 3 * 3600000, lockNowOffBy: "Pixel 8", lockNowOffAt: now - 2 * 3600000,
        ...(mode === "parent" ? { parentFrom: now - 600000, parentUntil: now + 3600000 } : {}),
        ...(mode === "bedtime" ? { bedtimeEnabled: true, bedtimeStart: 21 * 60, bedtimeEnd: 7 * 60 } : {}) },
      status: { updatedAt: now - 20000, usedMinutesToday: 86, limitMinutesToday: 120, reason: "", locked: false,
        versionCode: 40, versionName: "1.0.1", update: "Up to date", parentMinutesToday: mode === "parent" ? 25 : 0, deviceOwner: true, usageAccess: true, photoAt: now - 86400000 },
      nowPlaying: { app: "Netflix", title: "", playing: true, titleAccess: true },
      usage: { date: today, apps: [
        { label: "Netflix", pkg: "com.netflix.ninja", minutes: 52 },
        { label: "Live TV", pkg: "com.tcl.tv", minutes: 22 },
        { label: "YouTube", pkg: "com.google.android.youtube.tv", minutes: 12 }] },
      history: { [day(6)]: 95, [day(5)]: 120, [day(4)]: 80, [day(3)]: 110, [day(2)]: 140, [day(1)]: 175, [today]: 86 },
      apps: [
        { pkg: "com.google.android.youtube.tv", label: "YouTube" },
        { pkg: "com.google.android.youtube.tvkids", label: "YouTube Kids" },
        { pkg: "com.roblox.client", label: "Roblox" },
        { pkg: "com.netflix.ninja", label: "Netflix" },
        { pkg: "com.disney.disneyplus", label: "Disney+" }],
      request: mode === "request" ? { at: now - 60000, reason: "limit" } : null,
      pending: mode === "request" ? { other: { name: "Galaxy A54", at: now } } : null,
    },
  },
};
if (mode === "pair") delete data.devices[CODE].parents[UID];
if (mode !== "pair" && mode !== "wait") {
  try { localStorage.setItem("tvguard.code", CODE); } catch (e) { /* preview only */ }
}

const listeners = new Set();
const parts = (path) => path.split("/").filter(Boolean);
function read(path) {
  return parts(path).reduce((node, key) => (node == null ? null : node[key] ?? null), data);
}
function write(path, value) {
  const keys = parts(path);
  let node = data;
  keys.slice(0, -1).forEach((k) => { node = node[k] = node[k] && typeof node[k] === "object" ? node[k] : {}; });
  if (value === null) delete node[keys[keys.length - 1]];
  else node[keys[keys.length - 1]] = value;
  setTimeout(() => listeners.forEach((l) => l()), 0);
}
const clone = (v) => (v == null ? null : JSON.parse(JSON.stringify(v)));

export async function init() { return UID; }
export async function get(path) { return clone(read(path)); }
export function listen(path, onData) {
  const fire = () => onData(clone(read(path)));
  listeners.add(fire);
  setTimeout(fire, 0);
  return () => listeners.delete(fire);
}
export async function set(path, value) { write(path, clone(value)); }
export async function update(path, values) {
  Object.entries(values).forEach(([k, v]) => write(`${path}/${k}`, clone(v)));
}
export async function remove(path) { write(path, null); }
export const serverTime = () => Date.now();
export function onConnected(callback) { setTimeout(() => callback(true), 0); return () => {}; }
export function reconnect() {
  setTimeout(() => write(`devices/${CODE}/status/pongAt`, Date.now()), 800);
}
export async function pushToken() { return "demo-token"; }
