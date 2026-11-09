# TV Guard

Parental controls for a Google TV / Android TV, run from a phone.

- **TV app** (`tv/`) runs in the background as Device Owner. It locks the blocked apps (YouTube by
  default) when you turn them off, outside the allowed hours, or when today's watch time runs out,
  and it locks the whole TV at bedtime.
- **Phone app** (`phone/`) pairs with the TV using a code shown on screen. From the phone you can:
  - turn the blocked apps on or off
  - give bonus time for today (+15 or +30 minutes)
  - set separate daily limits for Mon–Fri and Sat–Sun
  - allow blocked apps only during set hours, with different hours on weekends
  - set a bedtime when the whole TV shows a lock screen
  - choose which installed TV apps are blocked
  - stop new apps being installed or updated, including from the Play Store
  - see a 7-day chart of watch time
  - get a notification when today's limit is used up or the TV stops checking in
- The two talk through a free Firebase Realtime Database, so the phone works away from home Wi-Fi.

Kids use the TV's main profile. Google TV Kids profiles aren't supported, because a Device Owner
app can't manage apps inside another profile.

## How it works

| Piece | What it does |
| --- | --- |
| Device Owner | `DevicePolicyManager.setPackagesSuspended` greys out blocked apps; the app also blocks its own uninstall. |
| Bedtime | A full-screen lock pinned with lock task mode, so Home and Back can't leave it until bedtime ends. |
| Watch time | `UsageStatsManager` events per local day for the blocked apps. |
| Background | A foreground service re-checks every 30 seconds and on every change from the phone, and restarts after reboot. |
| Sync | The phone writes `devices/{code}/config`; the TV writes `status`, `apps` and `history`. |
| Alerts | The phone checks the TV's status about every 15 minutes in the background (Android's shortest interval). |

Rules are checked in this order: bedtime, apps turned off, outside allowed hours, daily limit.
Weekends are Saturday and Sunday. Bonus time only counts on the day it was given.

Blocked by default: `com.google.android.youtube.tv` and `com.google.android.youtube`. Change the
list with **Choose apps** in the phone app.

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
(YouTube allowed, 60 minutes on weekdays and 120 on weekends), but the phone can't connect.

## 2. Get the APKs

Every push to `main` builds both apps. Open the **Actions** tab, pick the latest
**Build APKs** run and download `tv-guard-apks`.

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

## Removing protection

In the phone app, use **Remove protection from TV**. The TV unlocks YouTube, gives up Device Owner,
and can then be uninstalled normally. Without the phone app (for example if Firebase isn't set up),
the only way to remove it is a factory reset.

## Known limits

- Anyone who sees the pairing code and has the phone app can control the TV. Keep the phone APK to yourself.
- Watch time is counted per minute and checked every 30 seconds, so a session can run up to about
  a minute past the limit.
- Only the apps on the list are locked, except at bedtime when the whole TV is.
- The Play Store can't be locked like other apps, because Android keeps it running to check installs.
  Use **Allow installing and updating apps** instead. While it's off, apps (YouTube included) don't
  auto-update, and TV Guard itself can't be updated over adb until you turn it back on.
- Watch history only counts the apps currently on the blocked list.
- A TV that is unplugged or loses Wi-Fi shows as offline, which triggers the offline alert once.
