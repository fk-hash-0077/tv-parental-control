/**
 * TV Guard parent app for iPhones (and any browser). Does what the Android phone app does,
 * through the same Firebase data at devices/{code}: this app writes config, the TV writes
 * status, apps, usage and history. Keep field names in step with phone/.../MainActivity.kt.
 */
import * as L from "./logic.js";

const demo = new URLSearchParams(location.search).has("demo");
const cloud = await import(demo ? "./demo.js" : "./cloud.js");

const $ = (id) => document.getElementById(id);
const store = {
  get(key) { try { return localStorage.getItem(`tvguard.${key}`); } catch (e) { return null; } },
  set(key, value) { try { localStorage.setItem(`tvguard.${key}`, value); } catch (e) { /* private mode */ } },
  remove(key) { try { localStorage.removeItem(`tvguard.${key}`); } catch (e) { /* private mode */ } },
};

const isIos = /iPhone|iPad|iPod/.test(navigator.userAgent) ||
  (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
const standalone = window.matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;

let uid = null;
let code = store.get("code");
let config = {};
let status = null;
let apps = [];
let usage = null;
let history = null;
let nowPlaying = null;
let request = null;
let requestAnswer = null;
let parents = {};
let phoneOnline = true;
let pingSentAt = 0;
let updateAskedAt = 0;
let updateTextAtAsk = "";
let currentTab = 0;
let stops = [];
let approvalStop = null;
const photoSent = { photo: { at: 0, empty: false }, bedtimePhoto: { at: 0, empty: false } };
let pickingFor = null;

// ---------- Small helpers ----------

let toastTimer;
function toast(text) {
  const t = $("toast");
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 3500);
}

function showNotice(text) { $("notice").textContent = text; $("notice").hidden = !text; }

const formatWhen = (ms) => new Date(ms).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
const today = () => L.localDate();
const num = (key, fallback) => L.num(config, key, fallback);
const device = (path = "") => `devices/${code}${path ? `/${path}` : ""}`;

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([k, v]) => {
    if (k === "class") node.className = v;
    else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
    else node[k] = v;
  });
  children.flat().forEach((c) => c != null && node.append(c));
  return node;
}

function pill(text, tone, small = false) {
  return el("span", { class: `pill tone-${tone}${small ? " sm" : ""}`, textContent: text });
}

async function write(values) {
  if (!code) return;
  try {
    await cloud.update(device("config"), values);
  } catch (e) {
    toast(`Couldn't save: ${e.message}`);
  }
}

// ---------- Sections and tabs ----------

const sections = ["installSection", "pairSection", "waitSection", "controlSection"];
function show(section) {
  sections.forEach((s) => { $(s).hidden = s !== section; });
  const paired = section === "controlSection";
  $("bottomNav").hidden = !paired;
  if (paired) selectTab(currentTab);
  else {
    $("greeting").textContent = "Welcome";
    $("pageTitle").textContent = "TV Guard";
  }
}

const tabs = [
  ["tabHome", ""],
  ["tabLimits", "Limits & hours"],
  ["tabApps", "Apps"],
  ["tabCode", "Unlock code"],
  ["tabFamily", "Family"],
];
function selectTab(index) {
  currentTab = index;
  tabs.forEach(([id], i) => { $(id).hidden = i !== index; });
  document.querySelectorAll("nav button").forEach((b, i) => b.classList.toggle("on", i === index));
  const title = tabs[index][1];
  $("greeting").textContent = title ? "TV Guard" : L.greeting(new Date().getHours());
  $("pageTitle").textContent = title || "Your TV";
  window.scrollTo(0, 0);
  if (index === 3) renderCode();
}

// ---------- Face ID lock (a passkey kept on this phone) ----------

const b64url = {
  encode(bytes) { return btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""); },
  decode(text) {
    const raw = atob(text.replace(/-/g, "+").replace(/_/g, "/"));
    return Uint8Array.from(raw, (c) => c.charCodeAt(0));
  },
};
const random = (n) => crypto.getRandomValues(new Uint8Array(n));
const passkeyId = () => store.get("passkey");
let unlocked = !passkeyId();
let authenticating = false;

async function faceIdAvailable() {
  try {
    return !!window.PublicKeyCredential &&
      await PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable();
  } catch (e) {
    return false;
  }
}

async function turnOnFaceId() {
  try {
    const credential = await navigator.credentials.create({
      publicKey: {
        rp: { name: "TV Guard" },
        user: { id: random(16), name: "TV Guard parent", displayName: "TV Guard" },
        challenge: random(32),
        pubKeyCredParams: [{ type: "public-key", alg: -7 }, { type: "public-key", alg: -257 }],
        authenticatorSelection: { authenticatorAttachment: "platform", userVerification: "required", residentKey: "discouraged" },
        timeout: 60000,
      },
    });
    store.set("passkey", b64url.encode(credential.rawId));
    unlocked = true;
    toast("Face ID lock is on");
  } catch (e) {
    toast("Face ID wasn't turned on.");
  }
  renderFaceId();
}

async function unlock() {
  const id = passkeyId();
  if (!id) { unlocked = true; $("lockOverlay").hidden = true; return; }
  if (authenticating) return;
  authenticating = true;
  try {
    await navigator.credentials.get({
      publicKey: {
        challenge: random(32),
        allowCredentials: [{ type: "public-key", id: b64url.decode(id) }],
        userVerification: "required",
        timeout: 60000,
      },
    });
    unlocked = true;
    $("lockOverlay").hidden = true;
  } catch (e) {
    // Cancelled; the Unlock button stays on screen.
  } finally {
    authenticating = false;
  }
}

/** True while the photo picker is open, which can hide the page without leaving the app. */
let picking = false;

function lockApp() {
  if (!passkeyId() || authenticating || picking) return;
  unlocked = false;
  $("lockOverlay").hidden = false;
}

async function renderFaceId() {
  const on = !!passkeyId();
  const available = await faceIdAvailable();
  $("faceIdLabel").textContent = on
    ? "On. TV Guard asks for Face ID each time it opens."
    : available
      ? "Off. Turn it on so only you can open TV Guard on this phone."
      : "This phone can't use Face ID or Touch ID here. Set a passcode on the phone to keep TV Guard private.";
  $("faceIdButton").textContent = on ? "Turn off" : "Turn on";
  $("faceIdButton").disabled = !on && !available;
}

// ---------- Pairing and approval ----------

async function pair() {
  const clean = L.cleanCode($("codeInput").value);
  if (clean.length !== 12) { toast("The code has 12 letters and numbers."); return; }
  const name = $("nameInput").value.trim() || L.deviceName(navigator.userAgent);
  $("pairButton").disabled = true;
  try {
    const tvUid = await cloud.get(`devices/${clean}/tvUid`);
    if (!tvUid) {
      toast("No TV found with that code. Check the TV is on, online and has the newest TV Guard.");
      return;
    }
    code = clean;
    store.set("code", code);
    store.set("name", name);
    waitForApproval();
  } catch (e) {
    toast(`Couldn't check the code: ${e.message}`);
  } finally {
    $("pairButton").disabled = false;
  }
}

/**
 * This phone can see the TV only once it's in the TV's parents list. The first phone is
 * approved by the TV itself; later phones by a phone that's already approved.
 */
function waitForApproval() {
  if (approvalStop) approvalStop();
  approvalStop = cloud.listen(device(`parents/${uid}`), (value) => {
    if (value) {
      if (stops.length === 0) attach();
    } else {
      detach();
      show("waitSection");
      cloud.set(device(`pending/${uid}`), {
        name: store.get("name") || L.deviceName(navigator.userAgent),
        at: cloud.serverTime(),
      }).catch(() => {});
    }
  }, (error) => showNotice(`Couldn't reach the TV: ${error.message}`));
}

function attach() {
  detach();
  showNotice("");
  show("controlSection");
  const listen = (path, onData) => stops.push(cloud.listen(device(path), (v) => { onData(v); render(); }, (error) => {
    // Usually this phone was removed by another parent; go back to waiting.
    showNotice(`Sync stopped: ${error.message}`);
    detach();
    setTimeout(waitForApproval, 2000);
  }));
  listen("config", (v) => { config = v || {}; });
  listen("status", (v) => { status = v; });
  listen("apps", (v) => {
    const list = Array.isArray(v) ? v : Object.values(v || {});
    apps = list.filter((a) => a && a.pkg).map((a) => ({ pkg: a.pkg, label: a.label || a.pkg }));
    renderAppChoices();
  });
  listen("history", (v) => { history = v; });
  listen("usage", (v) => { usage = v; });
  listen("nowPlaying", (v) => { nowPlaying = v; });
  listen("secret", (v) => { if (typeof v === "string" && v) store.set("secret", v); });
  listen("request", (v) => { request = v; });
  listen("requestAnswer", (v) => { requestAnswer = v; });
  listen("pending", (v) => renderPending(v || {}));
  listen("parents", (v) => { parents = v || {}; renderPhones(); });
  registerPush(false);
}

function detach() {
  stops.forEach((stop) => stop());
  stops = [];
}

async function unpair(ask = true) {
  if (ask && !confirm("Unpair this phone? It will need approval to pair again.")) return;
  if (code && uid) {
    await Promise.allSettled([
      cloud.remove(device(`pending/${uid}`)),
      cloud.remove(device(`tokens/${uid}`)),
    ]);
    await cloud.remove(device(`parents/${uid}`)).catch(() => {});
  }
  if (approvalStop) approvalStop();
  approvalStop = null;
  detach();
  ["code", "secret"].forEach((k) => store.remove(k));
  code = null;
  config = {};
  status = null;
  usage = null;
  apps = [];
  $("codeInput").value = "";
  show("pairSection");
}

// ---------- Alerts ----------

function pushState() {
  if (!("serviceWorker" in navigator) || !("Notification" in window) || !("PushManager" in window)) {
    return isIos && !standalone ? "install" : "unsupported";
  }
  return Notification.permission; // default, granted, denied
}

/** Gets a push token and saves it for this phone. [ask] shows the iPhone's permission prompt. */
async function registerPush(ask) {
  const state = pushState();
  renderPushCard();
  if (state !== "granted" && !(ask && state === "default")) return;
  try {
    if (ask && (await Notification.requestPermission()) !== "granted") { renderPushCard(); return; }
    const registration = await navigator.serviceWorker.register("/firebase-messaging-sw.js");
    await navigator.serviceWorker.ready;
    const token = await cloud.pushToken(registration);
    if (token && code && uid) await cloud.set(device(`tokens/${uid}`), token);
    if (ask) toast("Alerts are on");
  } catch (e) {
    if (ask) toast(`Couldn't turn on alerts: ${e.message}`);
  }
  renderPushCard();
}

function renderPushCard() {
  const state = pushState();
  const card = $("pushCard");
  card.hidden = state === "granted" || state === "unsupported" || store.get("pushDismissed") === "1";
  if (state === "install") {
    $("pushText").textContent = "Alerts work once TV Guard is on your home screen: tap Share, then Add to Home Screen, and open it from there.";
    $("pushButton").hidden = true;
  } else if (state === "denied") {
    $("pushText").textContent = "Alerts are off for TV Guard. Turn them on in the iPhone's Settings, under Notifications, TV Guard.";
    $("pushButton").hidden = true;
  } else {
    $("pushText").textContent = "Hear about \"more time\" requests, time used up, and unlocks.";
    $("pushButton").hidden = false;
  }
}

// ---------- Rendering ----------

function render() {
  if (stops.length === 0) return;
  const t = today();
  $("scheduleSwitch").checked = config.scheduleEnabled === true;
  $("bedtimeSwitch").checked = config.bedtimeEnabled === true;
  $("installsSwitch").checked = config.installsAllowed === true;
  $("allowSwitch").checked = config.unblockedDate === t;
  if (document.activeElement !== $("weekdayLimit")) $("weekdayLimit").value = num("dailyLimitMinutes", 120);
  if (document.activeElement !== $("weekendLimit")) $("weekendLimit").value = num("weekendLimitMinutes", 180);
  document.querySelectorAll("[data-time]").forEach((input) => {
    if (document.activeElement !== input) input.value = L.toTimeInput(num(input.dataset.time, L.TIME_DEFAULTS[input.dataset.time]));
  });
  const bonus = L.todaysBonus(config, t);
  $("bonusLabel").textContent = bonus > 0 ? `+${bonus} bonus minutes today` : "Tap +15 or +30 to give bonus time today";
  $("bonusClear").hidden = bonus <= 0;
  $("emergencyLabel").textContent = config.emergencyCode
    ? "Set. It works once, then you set a new one."
    : "Off. Set one for when your phone isn't around.";
  renderPhoto("photo", "photoAt", "when weekday TV time is used up");
  renderPhoto("bedtimePhoto", "bedtimePhotoAt", "during bedtime");
  renderRequest();
  renderHome();
  renderUsage();
  renderChart();
  renderTvCard();
  renderAppChoices();
}

function lastSeen() { return (status && typeof status.updatedAt === "number") ? status.updatedAt : 0; }

function renderHome() {
  const s = status || {};
  const used = typeof s.usedMinutesToday === "number" ? s.usedMinutesToday : 0;
  const limit = typeof s.limitMinutesToday === "number" ? s.limitMinutesToday : 0;
  const locked = s.locked === true;
  const left = Math.max(0, limit - used);
  const tone = L.ringTone(used, limit, locked);
  const fraction = limit > 0 ? Math.min(1, used / limit) : 0;
  $("ringArc").style.strokeDasharray = `${(fraction * 339.3).toFixed(1)} 340`;
  $("ringArc").style.stroke = `var(--${tone})`;
  $("ringValue").textContent = L.formatShort(used);
  $("ringLabel").textContent = limit > 0 ? `of ${L.formatShort(limit)} today` : "today, no limit";
  $("leftLabel").textContent = limit > 0 ? "Left today" : "Daily limit";
  $("leftValue").textContent = limit > 0 ? L.formatMinutes(left) : "Off";
  $("leftValue").style.color = limit > 0 ? `var(--${tone})` : "var(--muted)";
  renderNowPlaying(locked);

  const conn = L.connection(phoneOnline, lastSeen(), Date.now(), formatWhen);
  $("connection").textContent = conn.synced;
  const pills = [pill(`● ${conn.pill}`, conn.tone)];
  if (status) {
    const lock = L.lockPill(s.reason);
    pills.push(pill(lock.text, lock.tone));
    if (typeof s.overrideUntil === "number" && s.overrideUntil > Date.now()) {
      pills.push(pill(`Unlocked until ${formatWhen(s.overrideUntil)}`, "warn"));
    }
  }
  if (config.bedtimeEnabled === true && s.reason !== "bedtime") {
    pills.push(pill(`Bedtime ${L.formatTime(num("bedtimeStart", L.TIME_DEFAULTS.bedtimeStart))}`, "warn"));
  }
  $("pills").replaceChildren(...pills);

  const lockedByMe = config.lockNow === true;
  $("lockNowIcon").textContent = lockedByMe ? "🔓" : "🔒";
  $("lockNowLabel").textContent = lockedByMe ? "Unlock TV" : "Lock now";
  const unblocked = config.unblockedDate === today();
  $("unblockIcon").textContent = unblocked ? "■" : "▶";
  $("unblockIcon").className = unblocked ? "warn" : "ok";
  $("unblockLabel").textContent = unblocked ? "Block apps" : "Unblock apps";

  if (pingSentAt > 0 && typeof s.pongAt === "number" && s.pongAt >= pingSentAt) {
    pingSentAt = 0;
    $("syncResult").textContent = "The TV answered and is up to date.";
    $("syncResult").hidden = false;
  }
  const warnings = [];
  if (status && s.released === true) warnings.push("Protection removed. The TV app can now be uninstalled.");
  else if (status) {
    if (s.deviceOwner !== true) warnings.push("⚠ TV app isn't Device Owner, so it can't lock anything. Run the setup again.");
    if (s.usageAccess !== true) warnings.push("⚠ TV app can't measure TV time. Run the setup again.");
  }
  $("warnings").textContent = warnings.join("\n");
  $("warnings").hidden = warnings.length === 0;
}

function renderNowPlaying(locked) {
  const n = nowPlaying;
  const set = (title, detail) => { $("nowTitle").textContent = title; $("nowDetail").textContent = detail; };
  if (!n) { set("Waiting for the TV", "Shows here once the TV has the newest TV Guard."); return; }
  const stale = Date.now() - lastSeen() > 3 * 60000;
  const since = stale && lastSeen() > 0 ? ` (as of ${formatWhen(lastSeen())})` : "";
  const app = n.app || "";
  const title = n.title || "";
  if (locked) set("Nothing, the TV is locked", "");
  else if (!app && !title) set(`Nothing on screen${since}`, "Home screen, standby or TV off");
  else if (title) set(title, [n.subtitle, app, n.playing ? "Playing" : "Paused"].filter(Boolean).join(" · ") + since);
  else set(app + since, n.titleAccess ? "" : "Titles are off on the TV.");
}

function renderRequest() {
  const open = L.openRequest(request, requestAnswer, Date.now());
  $("requestCard").hidden = !open;
  if (open) $("requestLabel").textContent = `Someone on the TV wants more time ${open.why} (${formatWhen(open.at)}).`;
}

function renderUsage() {
  const rows = L.todaysApps(usage, today());
  const list = $("usageList");
  if (rows.length === 0) {
    list.replaceChildren(el("div", { class: "muted", textContent: "Nothing watched yet today" }));
    return;
  }
  const max = Math.max(1, ...rows.map((r) => r.minutes));
  const blocked = new Set(L.packages(config));
  list.replaceChildren(...rows.map((r) => el("div", { class: "usage" },
    el("span", { class: "name", textContent: r.label },
      r.pkg && blocked.has(r.pkg) && !(status && status.appsAllowed === true) ? pill("Blocked", "bad", true) : null),
    el("span", { class: "track" }, el("div", { class: "fill", style: `width:${(r.minutes / max) * 100}%` })),
    el("span", { class: "mins", textContent: L.formatMinutes(r.minutes) }),
  )));
}

function renderChart() {
  const days = L.lastWeek(history, today());
  const max = Math.max(60, ...days.map((d) => d.minutes));
  $("historyChart").replaceChildren(...days.map((d, i) => el("div", { class: `col${i === 6 ? " today" : ""}` },
    el("span", { textContent: d.minutes ? String(d.minutes) : "" }),
    el("div", { class: "barv", style: `height:${(d.minutes / max) * 100}px` }),
    el("span", { textContent: d.label }),
  )));
}

function renderPhoto(node, statusKey, shownWhen) {
  const card = document.querySelector(`[data-photo="${node}"]`);
  const sent = photoSent[node];
  const onTv = status && typeof status[statusKey] === "number" ? status[statusKey] : 0;
  const waiting = sent.at > 0 && (sent.empty ? onTv !== 0 : onTv < sent.at);
  if (sent.at > 0 && !waiting) sent.at = 0;
  card.querySelector(".photo-label").textContent =
    waiting && sent.empty ? "Removing the photo from the TV…"
      : waiting ? "Sending the photo to the TV… It arrives the next time the TV is online."
        : onTv > 0 ? `On the TV. It shows in a round frame ${shownWhen}. It's saved only on the TV.`
          : `None. Choose a photo to show on the TV ${shownWhen}.`;
  card.querySelector(".photo-remove").hidden = !(onTv > 0 || (waiting && !sent.empty));
}

function renderTvCard() {
  const conn = L.connection(phoneOnline, lastSeen(), Date.now(), formatWhen);
  $("diagConnection").textContent = conn.text;
  $("diagConnection").style.color = `var(--${conn.tone})`;
  const s = status || {};
  const update = s.update || "";
  $("updateLabel").textContent = [typeof s.versionCode === "number" ? `Version ${s.versionCode}` : null, update || null]
    .filter(Boolean).join(" · ") || "Waiting for the TV";
  if (updateAskedAt && update !== updateTextAtAsk &&
    /^(Updated to|Up to date|Update failed|No TV update)/.test(update)) {
    updateAskedAt = 0;
    toast(update);
  }
}

let appsDirty = false;
function renderAppChoices() {
  if (appsDirty) return;
  const chosen = new Set(L.packages(config));
  const box = $("appChoices");
  if (apps.length === 0) {
    box.replaceChildren(el("p", { class: "muted", textContent: "The TV hasn't sent its app list yet." }));
    return;
  }
  box.replaceChildren(...apps.map((a) => el("label", { class: "check" },
    el("input", { type: "checkbox", checked: chosen.has(a.pkg), value: a.pkg,
      onchange: () => { appsDirty = true; $("saveApps").hidden = false; } }),
    el("span", { textContent: a.label }))));
}

function renderPending(pending) {
  const entries = Object.entries(pending);
  document.querySelectorAll(".pendingList").forEach((list) => {
    list.replaceChildren(...entries.map(([phoneUid, info]) => {
      const name = (info && info.name) || "A phone";
      return el("div", { class: "card request" },
        el("h2", { textContent: "New phone wants to pair" }),
        el("p", { class: "muted", textContent: `${name} wants to control this TV. Approve it only if it's yours.` }),
        el("div", { class: "row" },
          el("button", { class: "btn", textContent: "Approve", onclick: () => approve(phoneUid, name) }),
          el("button", { class: "btn soft", textContent: "Deny", onclick: () => cloud.remove(device(`pending/${phoneUid}`)) })));
    }));
  });
}

async function approve(phoneUid, name) {
  await cloud.set(device(`parents/${phoneUid}`), { name, addedAt: Date.now() });
  await cloud.remove(device(`pending/${phoneUid}`));
  toast(`${name} can now control the TV`);
}

function renderPhones() {
  $("phonesList").replaceChildren(...Object.entries(parents).map(([phoneUid, info]) => {
    const name = (info && info.name) || "Phone";
    return el("div", { class: "phone" },
      el("span", { textContent: phoneUid === uid ? `${name} (this phone)` : name }),
      phoneUid === uid ? null : el("button", { class: "link", textContent: "Remove", onclick: async () => {
        if (!confirm(`Remove ${name}? It will need approval to pair again.`)) return;
        await cloud.remove(device(`tokens/${phoneUid}`)).catch(() => {});
        await cloud.remove(device(`parents/${phoneUid}`)).catch((e) => toast(`Couldn't remove: ${e.message}`));
      } }));
  }));
}

// ---------- Unlock code ----------

let codeTimer = null;
async function renderCode() {
  clearTimeout(codeTimer);
  if (currentTab !== 3 || $("controlSection").hidden) return;
  const secret = L.decodeSecret(store.get("secret"));
  if (!secret) {
    $("arrows").replaceChildren(el("p", { class: "muted",
      textContent: "The TV hasn't shared its code yet. Make sure the TV is on and has the newest TV Guard." }));
    $("codeTimer").textContent = "";
  } else {
    const now = Date.now();
    const arrows = await L.totpCode(secret, L.counterAt(now));
    $("arrows").replaceChildren(...arrows.map((a) => el("span", { textContent: L.ARROWS[a] })));
    const left = L.secondsLeft(now);
    $("codeTimer").textContent = `New code in ${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}`;
    $("codeBar").style.width = `${(left / L.STEP_SECONDS) * 100}%`;
  }
  codeTimer = setTimeout(renderCode, 1000);
}

// ---------- Actions ----------

async function answerRequest(minutes) {
  const open = L.openRequest(request, requestAnswer, Date.now());
  if (!open) return;
  try {
    const current = (await cloud.get(device("config"))) || {};
    const updates = L.answerUpdates(current, open.reason, minutes, today(), Date.now());
    if (Object.keys(updates).length) await cloud.update(device("config"), updates);
    await cloud.set(device("requestAnswer"), { requestAt: open.at, minutes, at: Date.now() });
    toast(minutes > 0 ? `Gave ${minutes} more minutes` : "Told the TV no");
  } catch (e) {
    toast("Couldn't save the answer");
  }
}

function syncNow() {
  cloud.reconnect();
  pingSentAt = Date.now();
  const sent = pingSentAt;
  $("syncResult").textContent = "Syncing…";
  $("syncResult").hidden = false;
  write({ pingAt: pingSentAt });
  setTimeout(() => {
    if (pingSentAt === sent) {
      pingSentAt = 0;
      $("syncResult").textContent = "The TV didn't answer. It may be off, in standby without Wi-Fi, or offline.";
    }
  }, 20000);
  if (currentTab !== 0) toast("Asked the TV to sync");
}

/** A centre-cropped 480 px square JPEG as Base64, like LockPhoto.kt. */
async function preparePhoto(file) {
  const bitmap = await createImageBitmap(file);
  const side = Math.min(bitmap.width, bitmap.height);
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = 480;
  canvas.getContext("2d").drawImage(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side, 0, 0, 480, 480);
  return canvas.toDataURL("image/jpeg", 0.85).split(",")[1];
}

async function sendPhoto(node, file) {
  const label = document.querySelector(`[data-photo="${node}"] .photo-label`);
  label.textContent = "Preparing the photo…";
  let data;
  try { data = await preparePhoto(file); } catch (e) { data = null; }
  if (!data) { toast("Couldn't read that photo. Try another one."); render(); return; }
  const at = Date.now();
  photoSent[node] = { at, empty: false };
  render();
  cloud.set(device(node), { at, data }).catch((e) => {
    photoSent[node].at = 0;
    render();
    toast(`Couldn't send the photo: ${e.message}`);
  });
}

// Emergency code pad
let typed = "";
function refreshPad() {
  $("padPreview").textContent = typed ? [...typed].map((d) => L.ARROWS[Number(d)]).join("") : "–";
}

// ---------- Wiring ----------

function wire() {
  $("skipInstall").onclick = () => { store.set("skipInstall", "1"); start(); };
  $("codeInput").addEventListener("input", (e) => { e.target.value = L.dashCode(e.target.value); });
  $("pairButton").onclick = pair;
  $("cancelWait").onclick = () => unpair(false);
  document.querySelectorAll("nav button").forEach((b) => { b.onclick = () => selectTab(Number(b.dataset.tab)); });
  document.querySelectorAll("[data-answer]").forEach((b) => { b.onclick = () => answerRequest(Number(b.dataset.answer)); });
  $("pushButton").onclick = () => registerPush(true);

  $("bonus15").onclick = () => addBonus(15);
  $("bonus30").onclick = () => addBonus(30);
  $("bonusClear").onclick = () => write({ bonusDate: "", bonusMinutes: 0 });
  $("lockNow").onclick = () => {
    if (config.lockNow === true) { write({ lockNow: false }); toast("Unlocking the TV"); return; }
    if (!confirm("Lock the TV now? It stays locked until you unlock it here.")) return;
    // Also cancels any extra time given earlier, so the lock takes effect at once.
    write({ lockNow: true, overrideUntil: 0 });
    toast("Locking the TV");
  };
  $("unblockTile").onclick = () => {
    const unblocked = config.unblockedDate === today();
    write({ unblockedDate: unblocked ? "" : today() });
    toast(unblocked ? "Blocked apps are blocked again" : "Blocked apps unblocked until midnight");
  };
  $("connection").onclick = syncNow;
  $("syncNow").onclick = syncNow;

  $("saveLimits").onclick = () => {
    const weekday = Number.parseInt($("weekdayLimit").value, 10);
    const weekend = Number.parseInt($("weekendLimit").value, 10);
    if (!(weekday >= 0) || !(weekend >= 0)) { toast("Enter a number of minutes for both."); return; }
    write({ dailyLimitMinutes: weekday, weekendLimitMinutes: weekend });
    document.activeElement.blur();
    toast("Limits saved.");
  };
  document.querySelectorAll("[data-time]").forEach((input) => {
    input.addEventListener("change", () => {
      const value = L.fromTimeInput(input.value);
      if (value != null) write({ [input.dataset.time]: value });
    });
  });
  $("scheduleSwitch").onchange = (e) => write({ scheduleEnabled: e.target.checked });
  $("bedtimeSwitch").onchange = (e) => write({ bedtimeEnabled: e.target.checked });
  $("installsSwitch").onchange = (e) => write({ installsAllowed: e.target.checked });
  $("allowSwitch").onchange = (e) => write({ unblockedDate: e.target.checked ? today() : "" });
  $("saveApps").onclick = async () => {
    const installed = new Set(apps.map((a) => a.pkg));
    const ticked = [...document.querySelectorAll("#appChoices input:checked")].map((i) => i.value);
    // Keep listed packages that aren't installed, so they stay blocked if installed later.
    const result = [...L.packages(config).filter((p) => !installed.has(p)), ...ticked];
    try {
      await cloud.set(device("config/packages"), result);
      toast("Blocked apps saved");
    } catch (e) {
      toast(`Couldn't save: ${e.message}`);
    }
    appsDirty = false;
    $("saveApps").hidden = true;
    renderAppChoices();
  };

  $("emergencyButton").onclick = () => { typed = ""; refreshPad(); $("sheet").hidden = false; };
  $("padCancel").onclick = () => { $("sheet").hidden = true; };
  document.querySelectorAll("[data-arrow]").forEach((b) => {
    b.onclick = () => {
      if (b.dataset.arrow === "back") typed = typed.slice(0, -1);
      else if (typed.length < 10) typed += b.dataset.arrow;
      refreshPad();
    };
  });
  $("padSave").onclick = () => {
    if (typed.length < 6) { $("padPreview").textContent = "Use at least 6 arrows"; return; }
    write({ emergencyCode: typed, emergencySetAt: Date.now() });
    $("sheet").hidden = true;
    toast("Emergency code saved");
  };

  document.querySelectorAll("[data-photo]").forEach((card) => {
    const node = card.dataset.photo;
    card.querySelector(".photo-choose").onclick = () => {
      pickingFor = node;
      picking = true;
      setTimeout(() => { picking = false; }, 120000);
      $("photoInput").click();
    };
    card.querySelector(".photo-remove").onclick = () => {
      if (!confirm("Remove this photo? The TV deletes it and shows the plain lock screen again.")) return;
      photoSent[node] = { at: Date.now(), empty: true };
      render();
      cloud.set(device(node), { at: photoSent[node].at, data: "" });
    };
  });
  $("photoInput").addEventListener("cancel", () => { picking = false; });
  $("photoInput").onchange = (e) => {
    picking = false;
    const file = e.target.files && e.target.files[0];
    e.target.value = "";
    if (file && pickingFor) sendPhoto(pickingFor, file);
  };
  $("faceIdButton").onclick = () => {
    if (passkeyId()) {
      store.remove("passkey");
      toast("Face ID lock is off");
      renderFaceId();
    } else {
      turnOnFaceId();
    }
  };
  $("updateTv").onclick = () => {
    updateAskedAt = Date.now();
    updateTextAtAsk = (status && status.update) || "";
    write({ updateRequestAt: updateAskedAt });
    toast("Asked the TV to update");
  };
  $("unpairButton").onclick = () => unpair(true);
  $("unlockButton").onclick = unlock;

  // Lock again whenever the app leaves the screen, like the Android app.
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) lockApp();
    else {
      if (!unlocked) unlock();
      render();
      renderCode();
    }
  });
  setInterval(render, 30000);
}

function addBonus(minutes) {
  const total = L.todaysBonus(config, today()) + minutes;
  write({ bonusDate: today(), bonusMinutes: total });
  toast(`${total} extra minutes today`);
}

// ---------- Start ----------

async function start() {
  if (isIos && !standalone && !demo && store.get("skipInstall") !== "1") {
    show("installSection");
    return;
  }
  try {
    uid = await cloud.init();
  } catch (e) {
    showNotice(`Couldn't connect: ${e.message}`);
    show("pairSection");
    $("pairButton").disabled = true;
    return;
  }
  cloud.onConnected((online) => { phoneOnline = online; render(); });
  if (code) waitForApproval();
  else show("pairSection");
}

wire();
renderFaceId();
$("nameInput").placeholder = L.deviceName(navigator.userAgent);
if (!unlocked) { $("lockOverlay").hidden = false; unlock(); }
start();
