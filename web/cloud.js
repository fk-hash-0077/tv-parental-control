/**
 * Firebase access for the web parent app: anonymous sign-in and the Realtime Database, the
 * same way the Android phone app uses them. Opening the page with ?demo swaps in demo.js,
 * which needs no Firebase (for previews and screenshots).
 */
const SDK = "https://www.gstatic.com/firebasejs/10.14.1";

let app;
let db;
let fb;

/** The web config written by the deploy workflow, else the one Firebase Hosting serves itself. */
async function loadConfig() {
  for (const url of ["/firebase-config.json", "/__/firebase/init.json"]) {
    try {
      const response = await fetch(url, { cache: "no-store" });
      if (response.ok) return await response.json();
    } catch (e) {
      // Try the next one.
    }
  }
  throw new Error("This page can't find its Firebase settings. Deploy it from the GitHub workflow.");
}

export async function init() {
  const [appMod, authMod, dbMod] = await Promise.all([
    import(`${SDK}/firebase-app.js`),
    import(`${SDK}/firebase-auth.js`),
    import(`${SDK}/firebase-database.js`),
  ]);
  fb = { ...appMod, ...authMod, ...dbMod };
  const config = await loadConfig();
  app = fb.initializeApp(config);
  db = fb.getDatabase(app);
  const auth = fb.getAuth(app);
  // Keeps the same anonymous account across visits, so this phone stays paired.
  await fb.setPersistence(auth, fb.indexedDBLocalPersistence).catch(() => {});
  const user = await new Promise((resolve) => {
    const stop = fb.onAuthStateChanged(auth, (u) => {
      stop();
      resolve(u);
    });
  });
  if (user) return user.uid;
  const result = await fb.signInAnonymously(auth);
  return result.user.uid;
}

const ref = (path) => fb.ref(db, path);

export async function get(path) {
  return (await fb.get(ref(path))).val();
}

/** Calls onData with each new value; returns a function that stops listening. */
export function listen(path, onData, onError) {
  return fb.onValue(ref(path), (snapshot) => onData(snapshot.val()), (error) => onError && onError(error));
}

export const set = (path, value) => fb.set(ref(path), value);
export const update = (path, values) => fb.update(ref(path), values);
export const remove = (path) => fb.remove(ref(path));
export const serverTime = () => fb.serverTimestamp();

export function onConnected(callback) {
  return fb.onValue(ref(".info/connected"), (s) => callback(s.val() === true));
}

/** Drops and reopens the connection, like Sync now on Android. */
export function reconnect() {
  fb.goOffline(db);
  fb.goOnline(db);
}

/** A push token for this browser, using the service worker that shows the alerts. */
export async function pushToken(registration) {
  const messaging = await import(`${SDK}/firebase-messaging.js`);
  if (!(await messaging.isSupported())) throw new Error("This browser can't get alerts.");
  return messaging.getToken(messaging.getMessaging(app), { serviceWorkerRegistration: registration });
}
