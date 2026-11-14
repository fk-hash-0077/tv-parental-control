# TV Guard

Parental controls for a Google TV / Android TV, run from a phone.

- **TV app** (`tv/`) runs in the background as Device Owner.
  - YouTube, YouTube Kids and Roblox are blocked by default. The phone can unblock them for
    the day; they block again at midnight.
  - One daily limit for **all** TV time (separate for weekdays and weekends). Every app on
    screen counts, including Live TV and HDMI inputs; the home screen, screensaver, Settings and
    TV Guard don't. When the limit is used up, outside the allowed hours, and at bedtime, the whole
    TV shows a lock screen. A message warns 5 minutes before any lock.
  - On the lock screen, a parent can type a **one-time arrow code** from the phone app (or a
    single-use emergency code) on the remote to unlock for 30 minutes, and kids can press
    **Ask for more time**.
  - The TV's clock stays automatic, and app installs are blocked unless the phone allows them.
  - It updates itself from GitHub releases when the phone's **Update TV** button is tapped.
- **Phone app** (`phone/`) opens only after the phone's fingerprint or screen lock. It shows
  whether the TV is connected, has **Sync now**, today's TV time per app, a 7-day chart, bonus
  time, limits, allowed hours, bedtime, app and install controls, and a weekly report.
  A second parent's phone can pair only once an already-paired phone approves it.
- The two talk through Firebase Realtime Database, so the phone works away from home Wi-Fi.
  Instant notifications (limit reached, TV offline, ask for more time, unlocks, new phone,
  Sunday report) use Firebase Cloud Functions, which need Firebase's pay-as-you-go plan but
  cost nothing at family use (see step 1b).

Kids use the TV's main profile. Google TV Kids profiles aren't supported, because a Device Owner
app can't manage apps inside another profile.

## How it works

| Piece | What it does |
| --- | --- |
| Device Owner | `setPackagesSuspended` greys out blocked apps; the app blocks its own uninstall, keeps the clock automatic and blocks installs. |
| Lock screen | A full-screen activity pinned with lock task mode, so Home and Back can't leave it. |
| TV time | `UsageStatsManager` foreground events for all apps per local day, minus the home screen, screensaver, Settings and TV Guard. Overlaps count once. |
| Unlock codes | Like an authenticator app: an HMAC of the current 2-minute window with a secret the TV shares with approved phones, shown as 6 arrows. Each code works once, offline too. |
| Background | A foreground service re-checks every 30 seconds and on every change from the phone, and restarts after reboot. |
| Sync | The phone writes `devices/{code}/config`; the TV writes `status`, `apps`, `usage` and `history`. The TV reconnects when it wakes, when Wi-Fi returns and when a listener is cancelled, and a watchdog checks every minute. |
| Alerts | `functions/` turns TV events into push notifications (Firebase Cloud Messaging). |
| Updates | Every push to `main` publishes a GitHub release `v<run number>` with both APKs. |

Rules are checked in this order: bedtime, outside allowed hours, daily limit. Weekends are
Saturday and Sunday. Bonus time only counts on the day it was given.

## 1. Firebase setup (one time, free)

1. Create a project at <https://console.firebase.google.com>.
2. Add two Android apps with package names `com.tvguard.tv` and `com.tvguard.phone`.
3. **Build > Authentication**: turn on the **Anonymous** sign-in provider.
4. **Build > Realtime Database**: create a database, then paste `database.rules.json` into its Rules tab.
5. Only now download `google-services.json` (it covers both apps). Downloaded earlier, it lacks the
   database address and the apps hang when pairing.
6. In the GitHub repo, go to **Settings > Secrets and variables > Actions** and add a secret
   named `GOOGLE_SERVICES_JSON` with the full contents of the file.

Without this the apps still build, and the TV enforces its built-in defaults
(listed apps blocked, 120 minutes on weekdays and 180 on weekends), but the phone can't connect.

## 1b. Instant notifications (one time)

1. In the Firebase console, click **Upgrade** and choose the **Blaze** (pay-as-you-go) plan, adding
   a card. Billing only starts above the monthly free allowance (about 2 million function calls;
   a family uses a few hundred), so this should cost $0.
2. In Google Cloud **Billing > Budgets & alerts**, create a budget of **$1** with email alerts.
3. In Google Cloud **IAM & Admin > Service accounts**, open the `firebase-adminsdk` account,
   then **Keys > Add key > JSON**. In **IAM**, give that account the **Owner** role (deploying
   functions needs to set up permissions for them).
4. Add the downloaded file's contents as a GitHub secret named `FIREBASE_SERVICE_ACCOUNT`.

The next build on `main` then deploys `database.rules.json` and `functions/`. Without this
secret, paste `database.rules.json` into the database's Rules tab by hand; everything except
notifications works.

## 2. Get the APKs

Every push to `main` builds both apps and publishes them as the latest
[release](../../releases/latest). Pull requests build them too: open the **Actions** tab, pick
the run and download `tv-guard-apks`.

## 3. Set up the TV (one time)

You need a computer with `adb` ([platform-tools](https://developer.android.com/tools/releases/platform-tools))
on the same Wi-Fi as the TV. Nothing is plugged in.

1. Factory reset the TV. During setup, choose **basic TV** / skip signing in to Google.
   Device Owner can only be set while the TV has no accounts and no extra profiles.
2. Turn on developer options: **Settings > System > About**, select **Android TV OS build**
   seven times.
3. In **Settings > System > Developer options**, turn on **Wireless debugging** (or
   **USB debugging**, which on many TVs also allows network connections on port 5555).
   Note the TV's IP address and port.
4. Run:

   ```sh
   # TVs with "Wireless debugging": use "Pair device with pairing code" for the last two arguments
   scripts/setup-tv.sh 192.168.1.50:37000 tv-debug.apk 192.168.1.50:41234 123456

   # TVs where USB debugging allows network adb
   scripts/setup-tv.sh 192.168.1.50:5555 tv-debug.apk
   ```

5. The TV shows TV Guard with three green checks and a pairing code. You can now sign in to
   your Google account on the TV again if you want.
6. Optional: turn developer options back off so kids can't use them.

## 4. Set up the phone

Install `phone-debug.apk` on your Android phone, open it, and type the code from the TV.
The first phone is approved by the TV itself. A second phone waits until an approved phone
taps **Approve** (in the app or on the notification).

If every paired phone is lost, reset the list from a computer, then pair again:

```sh
adb shell am broadcast -a com.tvguard.tv.RESET_PHONES -n com.tvguard.tv/.ResetPhonesReceiver
```

## Updating

Tap **Update TV** in the phone app. The TV downloads the newest `tv-debug.apk` from the latest
release and installs itself, even while installs are blocked. For the phone, tap
**Get newest phone app**.

## Removing protection

In the phone app, use **Remove protection from TV**. The TV unlocks YouTube, gives up Device Owner,
and can then be uninstalled normally. Without the phone app (for example if Firebase isn't set up),
the only way to remove it is a factory reset.

## Known limits

- Anyone who sees the pairing code still needs an approved phone to say yes before they can control the TV.
- TV time is checked every 30 seconds, so a session can run up to about a minute past the limit.
- The 5-minute warning is a short message on screen; some apps may draw over it.
- The Play Store can't be locked like other apps, because Android keeps it running to check installs.
  Use **Allow installing and updating apps** instead. While it's off, apps (YouTube included) don't
  auto-update. TV Guard's own **Update TV** still works.
- A TV that is unplugged or loses Wi-Fi for 20 minutes shows as offline and triggers the offline
  alert once, including when it's switched off at night.
- Developer options and factory reset aren't blocked. A factory reset removes TV Guard.
